package com.yupi.template.service.impl;

import com.google.gson.reflect.TypeToken;
import com.mybatisflex.core.query.QueryWrapper;
import com.yupi.template.mapper.ArticleMapper;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.vo.ArticleTaskMemoryVO;
import com.yupi.template.model.vo.NodeExecutionLogVO;
import com.yupi.template.service.ArticleMemoryService;
import com.yupi.template.service.ArticleNodeLogService;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis-backed task memory for article creation.
 */
@Service
public class ArticleMemoryServiceImpl implements ArticleMemoryService {

    private static final String TASK_MEMORY_KEY_PREFIX = "article:task:memory:";
    private static final long TASK_MEMORY_TTL_HOURS = 24L * 7;
    private static final int MAX_ACTIONS = 12;
    private static final int MAX_SIGNALS = 12;
    private static final int MAX_SUMMARY_LENGTH = 200;
    private static final int MAX_HIGHLIGHTS = 4;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private ArticleMapper articleMapper;

    @Resource
    private ArticleNodeLogService articleNodeLogService;

    @Override
    public void initializeTaskMemory(Article article) {
        if (article == null || isBlank(article.getTaskId())) {
            return;
        }
        ArticleTaskMemoryVO memory = getOrCreate(article.getTaskId());
        mergeArticle(memory, article);
        addSignal(memory, buildSignal(
                "task_created",
                "任务已创建",
                "已创建文章任务，等待进入编排流程",
                article.getPhase(),
                null
        ));
        touch(memory);
        persist(memory);
    }

    @Override
    public void recordTitleConfirmed(Article article) {
        if (article == null || isBlank(article.getTaskId())) {
            return;
        }
        ArticleTaskMemoryVO memory = getOrCreate(article.getTaskId());
        mergeArticle(memory, article);
        if (!isBlank(article.getMainTitle()) || !isBlank(article.getSubTitle())) {
            ArticleState.TitleResult titleResult = new ArticleState.TitleResult();
            titleResult.setMainTitle(article.getMainTitle());
            titleResult.setSubTitle(article.getSubTitle());
            memory.setSelectedTitle(titleResult);
        }
        addAction(memory, buildAction(
                "title_confirmed",
                "确认标题",
                buildSelectedTitleDetail(memory.getSelectedTitle()),
                article.getPhase(),
                null
        ));
        addSignal(memory, buildSignal(
                "title_selected",
                "标题已选定",
                buildSelectedTitleDetail(memory.getSelectedTitle()),
                article.getPhase(),
                null
        ));
        touch(memory);
        persist(memory);
    }

    @Override
    public void recordOutlineConfirmed(String taskId, List<ArticleState.OutlineSection> outlineSections) {
        if (isBlank(taskId)) {
            return;
        }
        ArticleTaskMemoryVO memory = getOrCreate(taskId);
        Article article = findArticle(taskId);
        mergeArticle(memory, article);
        memory.setOutlineSummary(buildOutlineSummary(outlineSections, "confirmed_outline"));
        addAction(memory, buildAction(
                "outline_confirmed",
                "确认大纲",
                buildOutlineDetail(outlineSections),
                article != null ? article.getPhase() : memory.getCurrentPhase(),
                null
        ));
        addSignal(memory, buildSignal(
                "outline_locked",
                "大纲已锁定",
                buildOutlineDetail(outlineSections),
                article != null ? article.getPhase() : memory.getCurrentPhase(),
                null
        ));
        touch(memory);
        persist(memory);
    }

    @Override
    public void recordTaskCompleted(String taskId, ArticleState state) {
        if (isBlank(taskId)) {
            return;
        }
        ArticleTaskMemoryVO memory = getOrCreate(taskId);
        mergeArticle(memory, findArticle(taskId));
        mergeState(memory, state);
        mergeLatestNodes(memory, taskId);
        memory.setRecentErrorMessage(null);
        addSignal(memory, buildSignal(
                "task_completed",
                "任务完成",
                buildCompletionDetail(state),
                state != null ? state.getPhase() : memory.getCurrentPhase(),
                memory.getLastSuccessNode()
        ));
        touch(memory);
        persist(memory);
    }

    @Override
    public void recordTaskFailed(String taskId, String phase, String failedNode, String errorMessage) {
        if (isBlank(taskId)) {
            return;
        }
        ArticleTaskMemoryVO memory = getOrCreate(taskId);
        mergeArticle(memory, findArticle(taskId));
        memory.setCurrentPhase(phase);
        memory.setLastFailedNode(failedNode);
        memory.setRecentErrorMessage(errorMessage);
        mergeLatestNodes(memory, taskId);
        addSignal(memory, buildSignal(
                "task_failed",
                "任务失败",
                firstNonBlank(errorMessage, "工作流执行失败"),
                phase,
                failedNode
        ));
        if (!isBlank(phase)) {
            addSignal(memory, buildSignal(
                    "failed_phase",
                    "失败阶段",
                    phase,
                    phase,
                    failedNode
            ));
        }
        touch(memory);
        persist(memory);
    }

    @Override
    public void recordTaskResume(String taskId, String phase, String actionType, String node) {
        if (isBlank(taskId)) {
            return;
        }
        ArticleTaskMemoryVO memory = getOrCreate(taskId);
        mergeArticle(memory, findArticle(taskId));
        memory.setCurrentPhase(phase);
        memory.setRetryCount((memory.getRetryCount() == null ? 0 : memory.getRetryCount()) + 1);
        memory.setRecentErrorMessage(null);
        addAction(memory, buildAction(
                actionType,
                resolveActionLabel(actionType),
                resolveActionDetail(actionType, node, phase),
                phase,
                node
        ));
        addSignal(memory, buildSignal(
                "task_resumed",
                "任务恢复",
                resolveActionDetail(actionType, node, phase),
                phase,
                node
        ));
        mergeLatestNodes(memory, taskId);
        touch(memory);
        persist(memory);
    }

    @Override
    public ArticleTaskMemoryVO getTaskMemory(String taskId) {
        return readMemory(taskId);
    }

    private ArticleTaskMemoryVO getOrCreate(String taskId) {
        ArticleTaskMemoryVO memory = readMemory(taskId);
        if (memory != null) {
            ensureCollections(memory);
            return memory;
        }
        return ArticleTaskMemoryVO.builder()
                .taskId(taskId)
                .retryCount(0)
                .qualitySignals(new ArrayList<>())
                .manualActions(new ArrayList<>())
                .build();
    }

    private void ensureCollections(ArticleTaskMemoryVO memory) {
        if (memory.getQualitySignals() == null) {
            memory.setQualitySignals(new ArrayList<>());
        }
        if (memory.getManualActions() == null) {
            memory.setManualActions(new ArrayList<>());
        }
        if (memory.getRetryCount() == null) {
            memory.setRetryCount(0);
        }
    }

    private void mergeArticle(ArticleTaskMemoryVO memory, Article article) {
        if (memory == null || article == null) {
            return;
        }
        memory.setTaskId(firstNonBlank(memory.getTaskId(), article.getTaskId()));
        memory.setUserId(memory.getUserId() != null ? memory.getUserId() : article.getUserId());
        memory.setTopic(firstNonBlank(memory.getTopic(), article.getTopic()));
        memory.setStyle(firstNonBlank(memory.getStyle(), article.getStyle()));
        memory.setUserDescription(firstNonBlank(article.getUserDescription(), memory.getUserDescription()));
        memory.setCurrentPhase(firstNonBlank(article.getPhase(), memory.getCurrentPhase()));
        if (!isBlank(article.getMainTitle()) || !isBlank(article.getSubTitle())) {
            ArticleState.TitleResult titleResult = new ArticleState.TitleResult();
            titleResult.setMainTitle(article.getMainTitle());
            titleResult.setSubTitle(article.getSubTitle());
            memory.setSelectedTitle(titleResult);
        }
        if ((!hasImageStrategy(memory)) && !isBlank(article.getEnabledImageMethods())) {
            List<String> methods = GsonUtils.fromJsonSafe(article.getEnabledImageMethods(),
                    new TypeToken<List<String>>() {
                    });
            if (methods != null && !methods.isEmpty()) {
                memory.setImageStrategy(ArticleTaskMemoryVO.MemoryImageStrategyVO.builder()
                        .methods(methods)
                        .sources(new ArrayList<>(methods))
                        .build());
            }
        }
        if (memory.getOutlineSummary() == null && !isBlank(article.getOutline())) {
            List<ArticleState.OutlineSection> outlineSections = GsonUtils.fromJsonSafe(article.getOutline(),
                    new TypeToken<List<ArticleState.OutlineSection>>() {
                    });
            memory.setOutlineSummary(buildOutlineSummary(outlineSections, "article_outline"));
        }
        if (memory.getContentSummary() == null || isBlank(memory.getContentSummary().getText())) {
            memory.setContentSummary(buildContentSummary(article.getFullContent(), article.getContent(), "article_content"));
        }
    }

    private void mergeState(ArticleTaskMemoryVO memory, ArticleState state) {
        if (memory == null || state == null) {
            return;
        }
        memory.setCurrentPhase(firstNonBlank(state.getPhase(), memory.getCurrentPhase()));
        memory.setUserDescription(firstNonBlank(state.getUserDescription(), memory.getUserDescription()));
        if (state.getTitle() != null) {
            memory.setSelectedTitle(state.getTitle());
        }
        if (state.getOutline() != null) {
            memory.setOutlineSummary(buildOutlineSummary(state.getOutline().getSections(), "workflow_outline"));
        }
        memory.setContentSummary(buildContentSummary(state.getFullContent(), state.getContent(), "workflow_content"));
        mergeImageStrategy(memory, state);
    }

    private void mergeImageStrategy(ArticleTaskMemoryVO memory, ArticleState state) {
        ArticleTaskMemoryVO.MemoryImageStrategyVO current = memory.getImageStrategy();
        List<String> methods = state.getEnabledImageMethods() == null
                ? new ArrayList<>()
                : state.getEnabledImageMethods().stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toList());
        List<String> sources = state.getImageRequirements() == null
                ? new ArrayList<>()
                : state.getImageRequirements().stream()
                .map(ArticleState.ImageRequirement::getImageSource)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toList());
        int requirementCount = state.getImageRequirements() == null ? 0 : state.getImageRequirements().size();
        int generatedCount = state.getImages() == null ? 0 : state.getImages().size();
        if (methods.isEmpty() && sources.isEmpty() && requirementCount == 0 && generatedCount == 0 && current != null) {
            return;
        }
        if (methods.isEmpty() && current != null && current.getMethods() != null) {
            methods = current.getMethods();
        }
        if (sources.isEmpty() && current != null && current.getSources() != null) {
            sources = current.getSources();
        }
        memory.setImageStrategy(ArticleTaskMemoryVO.MemoryImageStrategyVO.builder()
                .methods(methods.isEmpty() ? null : methods)
                .sources(sources.isEmpty() ? null : sources)
                .requirementCount(requirementCount > 0 ? requirementCount : currentValue(current != null ? current.getRequirementCount() : null))
                .generatedCount(generatedCount > 0 ? generatedCount : currentValue(current != null ? current.getGeneratedCount() : null))
                .build());
    }

    private void mergeLatestNodes(ArticleTaskMemoryVO memory, String taskId) {
        List<NodeExecutionLogVO> logs = articleNodeLogService.getLogs(taskId);
        if (logs == null || logs.isEmpty()) {
            return;
        }
        String successNode = null;
        String failedNode = null;
        for (int i = logs.size() - 1; i >= 0; i--) {
            NodeExecutionLogVO logVO = logs.get(i);
            if (successNode == null && "SUCCESS".equals(logVO.getStatus())) {
                successNode = logVO.getNode();
            }
            if (failedNode == null && "FAILED".equals(logVO.getStatus())) {
                failedNode = logVO.getNode();
            }
            if (successNode != null && failedNode != null) {
                break;
            }
        }
        if (successNode != null) {
            memory.setLastSuccessNode(successNode);
        }
        if (failedNode != null) {
            memory.setLastFailedNode(failedNode);
        }
    }

    private ArticleTaskMemoryVO readMemory(String taskId) {
        if (isBlank(taskId)) {
            return null;
        }
        Object cached = redisTemplate.opsForValue().get(buildKey(taskId));
        if (cached == null) {
            return null;
        }
        if (cached instanceof ArticleTaskMemoryVO memoryVO) {
            ensureCollections(memoryVO);
            return memoryVO;
        }
        ArticleTaskMemoryVO memory = null;
        if (cached instanceof String json) {
            memory = GsonUtils.fromJsonSafe(json, ArticleTaskMemoryVO.class);
        } else {
            memory = GsonUtils.fromJsonSafe(GsonUtils.toJson(cached), ArticleTaskMemoryVO.class);
        }
        if (memory == null) {
            return null;
        }
        ensureCollections(memory);
        normalizeLegacyMemory(memory, cached);
        return memory;
    }

    private void normalizeLegacyMemory(ArticleTaskMemoryVO memory, Object rawCached) {
        if (memory == null) {
            return;
        }
        if (memory.getOutlineSummary() == null || memory.getContentSummary() == null
                || memory.getImageStrategy() == null || memory.getQualitySignals() == null
                || memory.getManualActions() == null) {
            String json = rawCached instanceof String ? (String) rawCached : GsonUtils.toJson(rawCached);
            LegacyTaskMemoryVO legacy = GsonUtils.fromJsonSafe(json, LegacyTaskMemoryVO.class);
            if (legacy != null) {
                if (memory.getOutlineSummary() == null && !isBlank(legacy.getOutlineSummary())) {
                    memory.setOutlineSummary(buildPlainSummary(legacy.getOutlineSummary(), "legacy_outline"));
                }
                if (memory.getContentSummary() == null && !isBlank(legacy.getContentSummary())) {
                    memory.setContentSummary(buildPlainSummary(legacy.getContentSummary(), "legacy_content"));
                }
                if (memory.getImageStrategy() == null && !isBlank(legacy.getImageStrategy())) {
                    List<String> sources = splitCsv(legacy.getImageStrategy());
                    memory.setImageStrategy(ArticleTaskMemoryVO.MemoryImageStrategyVO.builder()
                            .methods(sources.isEmpty() ? null : sources)
                            .sources(sources.isEmpty() ? null : sources)
                            .build());
                }
                if ((memory.getQualitySignals() == null || memory.getQualitySignals().isEmpty())
                        && legacy.getQualitySignals() != null) {
                    memory.setQualitySignals(legacy.getQualitySignals().stream()
                            .filter(Objects::nonNull)
                            .filter(value -> !value.isBlank())
                            .map(code -> buildSignal(code, mapSignalLabel(code), code, memory.getCurrentPhase(), null))
                            .collect(Collectors.toCollection(ArrayList::new)));
                }
                if ((memory.getManualActions() == null || memory.getManualActions().isEmpty())
                        && legacy.getManualActions() != null) {
                    memory.setManualActions(legacy.getManualActions().stream()
                            .filter(Objects::nonNull)
                            .filter(value -> !value.isBlank())
                            .map(code -> buildAction(code, mapActionLabel(code), code, memory.getCurrentPhase(), null))
                            .collect(Collectors.toCollection(ArrayList::new)));
                }
            }
        }
        touch(memory);
    }

    private void persist(ArticleTaskMemoryVO memory) {
        redisTemplate.opsForValue().set(
                buildKey(memory.getTaskId()),
                GsonUtils.toJson(memory),
                TASK_MEMORY_TTL_HOURS,
                TimeUnit.HOURS
        );
    }

    private Article findArticle(String taskId) {
        if (isBlank(taskId)) {
            return null;
        }
        return articleMapper.selectOneByQuery(QueryWrapper.create().eq("taskId", taskId));
    }

    private String buildKey(String taskId) {
        return TASK_MEMORY_KEY_PREFIX + taskId;
    }

    private void touch(ArticleTaskMemoryVO memory) {
        memory.setUpdatedAt(System.currentTimeMillis());
        ensureCollections(memory);
    }

    private void addSignal(ArticleTaskMemoryVO memory, ArticleTaskMemoryVO.MemorySignalVO signal) {
        if (memory == null || signal == null) {
            return;
        }
        deduplicateAndPrepend(memory.getQualitySignals(), signal, MAX_SIGNALS);
    }

    private void addAction(ArticleTaskMemoryVO memory, ArticleTaskMemoryVO.MemoryActionVO action) {
        if (memory == null || action == null) {
            return;
        }
        deduplicateAndPrepend(memory.getManualActions(), action, MAX_ACTIONS);
    }

    private void deduplicateAndPrepend(List<ArticleTaskMemoryVO.MemorySignalVO> items,
                                       ArticleTaskMemoryVO.MemorySignalVO value,
                                       int maxSize) {
        items.removeIf(item -> Objects.equals(item.getCode(), value.getCode())
                && Objects.equals(item.getDetail(), value.getDetail())
                && Objects.equals(item.getPhase(), value.getPhase())
                && Objects.equals(item.getNode(), value.getNode()));
        items.add(0, value);
        while (items.size() > maxSize) {
            items.remove(items.size() - 1);
        }
    }

    private void deduplicateAndPrepend(List<ArticleTaskMemoryVO.MemoryActionVO> items,
                                       ArticleTaskMemoryVO.MemoryActionVO value,
                                       int maxSize) {
        items.removeIf(item -> Objects.equals(item.getType(), value.getType())
                && Objects.equals(item.getDetail(), value.getDetail())
                && Objects.equals(item.getPhase(), value.getPhase())
                && Objects.equals(item.getNode(), value.getNode()));
        items.add(0, value);
        while (items.size() > maxSize) {
            items.remove(items.size() - 1);
        }
    }

    private ArticleTaskMemoryVO.MemorySummaryVO buildOutlineSummary(List<ArticleState.OutlineSection> outlineSections,
                                                                   String sourceType) {
        if (outlineSections == null || outlineSections.isEmpty()) {
            return null;
        }
        List<String> highlights = outlineSections.stream()
                .limit(MAX_HIGHLIGHTS)
                .map(section -> section.getSection() + "." + firstNonBlank(section.getTitle(), "未命名章节"))
                .collect(Collectors.toList());
        return ArticleTaskMemoryVO.MemorySummaryVO.builder()
                .text(String.join(" | ", highlights))
                .sourceCount(outlineSections.size())
                .highlights(highlights)
                .sourceType(sourceType)
                .build();
    }

    private ArticleTaskMemoryVO.MemorySummaryVO buildContentSummary(String preferred,
                                                                   String fallback,
                                                                   String sourceType) {
        String content = !isBlank(preferred) ? preferred : fallback;
        if (isBlank(content)) {
            return null;
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        String summary = normalized.length() <= MAX_SUMMARY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_SUMMARY_LENGTH) + "...";
        return ArticleTaskMemoryVO.MemorySummaryVO.builder()
                .text(summary)
                .sourceCount(normalized.length())
                .highlights(extractHighlightsFromContent(normalized))
                .sourceType(sourceType)
                .build();
    }

    private ArticleTaskMemoryVO.MemorySummaryVO buildPlainSummary(String text, String sourceType) {
        if (isBlank(text)) {
            return null;
        }
        List<String> highlights = splitSummaryHighlights(text);
        return ArticleTaskMemoryVO.MemorySummaryVO.builder()
                .text(text)
                .sourceCount(highlights.isEmpty() ? 1 : highlights.size())
                .highlights(highlights.isEmpty() ? null : highlights)
                .sourceType(sourceType)
                .build();
    }

    private List<String> extractHighlightsFromContent(String content) {
        if (isBlank(content)) {
            return Collections.emptyList();
        }
        List<String> parts = new ArrayList<>();
        String[] candidates = content.split("(?<=[。！？!?.])\\s+|\\n+");
        for (String candidate : candidates) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            parts.add(trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 60) + "...");
            if (parts.size() >= MAX_HIGHLIGHTS) {
                break;
            }
        }
        return parts;
    }

    private List<String> splitSummaryHighlights(String text) {
        if (isBlank(text)) {
            return Collections.emptyList();
        }
        return List.of(text.split("\\s*\\|\\s*")).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .limit(MAX_HIGHLIGHTS)
                .collect(Collectors.toList());
    }

    private ArticleTaskMemoryVO.MemorySignalVO buildSignal(String code,
                                                           String label,
                                                           String detail,
                                                           String phase,
                                                           String node) {
        return ArticleTaskMemoryVO.MemorySignalVO.builder()
                .code(code)
                .label(label)
                .detail(detail)
                .phase(phase)
                .node(node)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private ArticleTaskMemoryVO.MemoryActionVO buildAction(String type,
                                                           String label,
                                                           String detail,
                                                           String phase,
                                                           String node) {
        return ArticleTaskMemoryVO.MemoryActionVO.builder()
                .type(type)
                .label(label)
                .detail(detail)
                .phase(phase)
                .node(node)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private String buildSelectedTitleDetail(ArticleState.TitleResult titleResult) {
        if (titleResult == null) {
            return "未记录标题内容";
        }
        return firstNonBlank(titleResult.getMainTitle(), "--") + " / "
                + firstNonBlank(titleResult.getSubTitle(), "--");
    }

    private String buildOutlineDetail(List<ArticleState.OutlineSection> outlineSections) {
        if (outlineSections == null || outlineSections.isEmpty()) {
            return "未记录大纲章节";
        }
        return "章节数=" + outlineSections.size() + "，首章="
                + firstNonBlank(outlineSections.get(0).getTitle(), "未命名章节");
    }

    private String buildCompletionDetail(ArticleState state) {
        if (state == null) {
            return "任务已完成";
        }
        int imageCount = state.getImages() == null ? 0 : state.getImages().size();
        int requirementCount = state.getImageRequirements() == null ? 0 : state.getImageRequirements().size();
        return "正文已生成，图片需求=" + requirementCount + "，已生成图片=" + imageCount;
    }

    private String resolveActionLabel(String actionType) {
        return switch (actionType) {
            case "phase_resume" -> "阶段续跑";
            case "node_retry" -> "节点重试";
            default -> actionType;
        };
    }

    private String resolveActionDetail(String actionType, String node, String phase) {
        if ("node_retry".equals(actionType) && !isBlank(node)) {
            return "重试节点=" + node + "，阶段=" + firstNonBlank(phase, "--");
        }
        if ("phase_resume".equals(actionType)) {
            return "从阶段继续执行：" + firstNonBlank(phase, "--");
        }
        return firstNonBlank(node, firstNonBlank(phase, actionType));
    }

    private String mapSignalLabel(String code) {
        return switch (code) {
            case "task_created" -> "任务已创建";
            case "title_selected" -> "标题已选定";
            case "outline_locked" -> "大纲已锁定";
            case "task_completed" -> "任务完成";
            case "task_failed" -> "任务失败";
            case "task_resumed" -> "任务恢复";
            default -> code;
        };
    }

    private String mapActionLabel(String code) {
        return switch (code) {
            case "title_confirmed" -> "确认标题";
            case "outline_confirmed" -> "确认大纲";
            case "phase_resume" -> "阶段续跑";
            default -> code.startsWith("node_retry") ? "节点重试" : code;
        };
    }

    private List<String> splitCsv(String raw) {
        if (isBlank(raw)) {
            return Collections.emptyList();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return new ArrayList<>(values);
    }

    private boolean hasImageStrategy(ArticleTaskMemoryVO memory) {
        return memory.getImageStrategy() != null
                && ((memory.getImageStrategy().getMethods() != null && !memory.getImageStrategy().getMethods().isEmpty())
                || (memory.getImageStrategy().getSources() != null && !memory.getImageStrategy().getSources().isEmpty()));
    }

    private Integer currentValue(Integer value) {
        return value == null ? 0 : value;
    }

    private String firstNonBlank(String candidate, String fallback) {
        return !isBlank(candidate) ? candidate : fallback;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @lombok.Data
    private static class LegacyTaskMemoryVO {
        private String outlineSummary;
        private String contentSummary;
        private String imageStrategy;
        private List<String> qualitySignals;
        private List<String> manualActions;
    }
}
