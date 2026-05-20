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
import java.util.List;
import java.util.Objects;
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
        ArticleTaskMemoryVO memory = readMemory(article.getTaskId());
        if (memory == null) {
            memory = ArticleTaskMemoryVO.builder()
                    .taskId(article.getTaskId())
                    .retryCount(0)
                    .qualitySignals(new ArrayList<>())
                    .manualActions(new ArrayList<>())
                    .build();
        }
        mergeArticle(memory, article);
        addUnique(memory.getQualitySignals(), "task_created", MAX_SIGNALS);
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
        addUnique(memory.getManualActions(), "title_confirmed", MAX_ACTIONS);
        addUnique(memory.getQualitySignals(), "title_selected", MAX_SIGNALS);
        touch(memory);
        persist(memory);
    }

    @Override
    public void recordOutlineConfirmed(String taskId, List<ArticleState.OutlineSection> outlineSections) {
        if (isBlank(taskId)) {
            return;
        }
        ArticleTaskMemoryVO memory = getOrCreate(taskId);
        mergeArticle(memory, findArticle(taskId));
        memory.setOutlineSummary(buildOutlineSummary(outlineSections));
        addUnique(memory.getManualActions(), "outline_confirmed", MAX_ACTIONS);
        addUnique(memory.getQualitySignals(), "outline_locked", MAX_SIGNALS);
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
        addUnique(memory.getQualitySignals(), "task_completed", MAX_SIGNALS);
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
        addUnique(memory.getQualitySignals(), "task_failed", MAX_SIGNALS);
        if (!isBlank(phase)) {
            addUnique(memory.getQualitySignals(), "failed_phase:" + phase, MAX_SIGNALS);
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
        String action = isBlank(node) ? actionType : actionType + ":" + node;
        addUnique(memory.getManualActions(), action, MAX_ACTIONS);
        addUnique(memory.getQualitySignals(), "task_resumed", MAX_SIGNALS);
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
            if (memory.getQualitySignals() == null) {
                memory.setQualitySignals(new ArrayList<>());
            }
            if (memory.getManualActions() == null) {
                memory.setManualActions(new ArrayList<>());
            }
            if (memory.getRetryCount() == null) {
                memory.setRetryCount(0);
            }
            return memory;
        }
        return ArticleTaskMemoryVO.builder()
                .taskId(taskId)
                .retryCount(0)
                .qualitySignals(new ArrayList<>())
                .manualActions(new ArrayList<>())
                .build();
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
        if ((!isBlank(article.getMainTitle()) || !isBlank(article.getSubTitle())) && memory.getSelectedTitle() == null) {
            ArticleState.TitleResult titleResult = new ArticleState.TitleResult();
            titleResult.setMainTitle(article.getMainTitle());
            titleResult.setSubTitle(article.getSubTitle());
            memory.setSelectedTitle(titleResult);
        }
        if (isBlank(memory.getImageStrategy()) && !isBlank(article.getEnabledImageMethods())) {
            List<String> methods = GsonUtils.fromJsonSafe(article.getEnabledImageMethods(),
                    new TypeToken<List<String>>() {
                    });
            if (methods != null && !methods.isEmpty()) {
                memory.setImageStrategy(String.join(",", methods));
            }
        }
        if (isBlank(memory.getOutlineSummary()) && !isBlank(article.getOutline())) {
            List<ArticleState.OutlineSection> outlineSections = GsonUtils.fromJsonSafe(article.getOutline(),
                    new TypeToken<List<ArticleState.OutlineSection>>() {
                    });
            memory.setOutlineSummary(buildOutlineSummary(outlineSections));
        }
        if (isBlank(memory.getContentSummary())) {
            memory.setContentSummary(buildContentSummary(article.getFullContent(), article.getContent()));
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
            memory.setOutlineSummary(buildOutlineSummary(state.getOutline().getSections()));
        }
        memory.setContentSummary(buildContentSummary(state.getFullContent(), state.getContent()));
        if (state.getEnabledImageMethods() != null && !state.getEnabledImageMethods().isEmpty()) {
            memory.setImageStrategy(String.join(",", state.getEnabledImageMethods()));
        } else if (state.getImageRequirements() != null && !state.getImageRequirements().isEmpty()) {
            List<String> sources = state.getImageRequirements().stream()
                    .map(ArticleState.ImageRequirement::getImageSource)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (!sources.isEmpty()) {
                memory.setImageStrategy(String.join(",", sources));
            }
        }
    }

    private void mergeLatestNodes(ArticleTaskMemoryVO memory, String taskId) {
        List<NodeExecutionLogVO> logs = articleNodeLogService.getLogs(taskId);
        if (logs == null || logs.isEmpty()) {
            return;
        }
        for (int i = logs.size() - 1; i >= 0; i--) {
            NodeExecutionLogVO logVO = logs.get(i);
            if (memory.getLastSuccessNode() == null && "SUCCESS".equals(logVO.getStatus())) {
                memory.setLastSuccessNode(logVO.getNode());
            }
            if (memory.getLastFailedNode() == null && "FAILED".equals(logVO.getStatus())) {
                memory.setLastFailedNode(logVO.getNode());
            }
            if (memory.getLastSuccessNode() != null && memory.getLastFailedNode() != null) {
                break;
            }
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
            return memoryVO;
        }
        if (cached instanceof String json) {
            return GsonUtils.fromJsonSafe(json, ArticleTaskMemoryVO.class);
        }
        return GsonUtils.fromJsonSafe(GsonUtils.toJson(cached), ArticleTaskMemoryVO.class);
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

    private void addUnique(List<String> items, String value, int maxSize) {
        if (items == null || isBlank(value)) {
            return;
        }
        items.remove(value);
        items.add(0, value);
        while (items.size() > maxSize) {
            items.remove(items.size() - 1);
        }
    }

    private String buildOutlineSummary(List<ArticleState.OutlineSection> outlineSections) {
        if (outlineSections == null || outlineSections.isEmpty()) {
            return null;
        }
        return outlineSections.stream()
                .limit(5)
                .map(section -> section.getSection() + "." + section.getTitle())
                .collect(Collectors.joining(" | "));
    }

    private String buildContentSummary(String preferred, String fallback) {
        String content = !isBlank(preferred) ? preferred : fallback;
        if (isBlank(content)) {
            return null;
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_SUMMARY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_SUMMARY_LENGTH) + "...";
    }

    private String firstNonBlank(String current, String candidate) {
        return !isBlank(current) ? current : candidate;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
