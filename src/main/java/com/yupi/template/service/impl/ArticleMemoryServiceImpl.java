package com.yupi.template.service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.mybatisflex.core.query.QueryWrapper;
import com.yupi.template.mapper.ArticleMapper;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.enums.ArticleStatusEnum;
import com.yupi.template.model.vo.ArticleMemoryContextVO;
import com.yupi.template.model.vo.ArticleTaskMemoryVO;
import com.yupi.template.model.vo.ImageFallbackDecisionVO;
import com.yupi.template.model.vo.ImageStrategyDecisionVO;
import com.yupi.template.model.vo.NodeExecutionLogVO;
import com.yupi.template.model.vo.UserCreationPreferenceVO;
import com.yupi.template.service.ArticleMemoryService;
import com.yupi.template.service.ArticleNodeLogService;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private static final String USER_PREFERENCE_KEY_PREFIX = "article:user:preference:";
    private static final long TASK_MEMORY_TTL_HOURS = 24L * 7;
    private static final long USER_PREFERENCE_TTL_HOURS = 24L * 30;
    private static final int MAX_ACTIONS = 12;
    private static final int MAX_SIGNALS = 12;
    private static final int MAX_NODE_SNAPSHOTS = 12;
    private static final int MAX_SUMMARY_LENGTH = 200;
    private static final int MAX_HIGHLIGHTS = 4;
    private static final int MAX_FAILURE_TAGS = 8;
    private static final int MAX_RECALLED_CASES = 3;

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
        Article article = findArticle(taskId);
        mergeArticle(memory, article);
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
        persistUserPreference(memory, article, state);
    }

    @Override
    public void recordTaskFailed(String taskId, String phase, String failedNode, String errorMessage) {
        if (isBlank(taskId)) {
            return;
        }
        ArticleTaskMemoryVO memory = getOrCreate(taskId);
        Article article = findArticle(taskId);
        mergeArticle(memory, article);
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
        persistFailurePreference(memory, article, phase, failedNode, errorMessage);
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
    public void recordNodeSnapshot(String taskId, String phase, String node, String status, ArticleState state) {
        if (isBlank(taskId) || isBlank(node)) {
            return;
        }
        ArticleTaskMemoryVO memory = getOrCreate(taskId);
        mergeArticle(memory, findArticle(taskId));
        mergeState(memory, state);
        mergeLatestNodes(memory, taskId);
        addNodeSnapshot(memory, buildNodeSnapshot(node, phase, status, state));
        touch(memory);
        persist(memory);
    }

    @Override
    public void recordImageStrategyDecision(String taskId, ArticleState state, ImageStrategyDecisionVO decision) {
        if (isBlank(taskId) || decision == null) {
            return;
        }
        ArticleTaskMemoryVO memory = getOrCreate(taskId);
        mergeArticle(memory, findArticle(taskId));
        mergeState(memory, state);
        ArticleTaskMemoryVO.MemoryImageStrategyVO current = memory.getImageStrategy();
        List<String> methods = decision.getPreferredMethods() == null || decision.getPreferredMethods().isEmpty()
                ? (current == null ? null : current.getMethods())
                : decision.getPreferredMethods();
        memory.setImageStrategy(ArticleTaskMemoryVO.MemoryImageStrategyVO.builder()
                .methods(methods)
                .needImages(decision.getNeedImages())
                .decisionReason(decision.getReason())
                .decisionSource(decision.getSource())
                .sources(current == null ? null : current.getSources())
                .requirementCount(current == null ? null : current.getRequirementCount())
                .generatedCount(current == null ? null : current.getGeneratedCount())
                .build());
        addSignal(memory, buildSignal(
                "image_strategy_routed",
                "图片策略已路由",
                "来源=" + firstNonBlank(decision.getSource(), "--")
                        + "，方法=" + firstNonBlank(joinValues(methods), "--"),
                state != null ? state.getPhase() : memory.getCurrentPhase(),
                "image_strategy_router"
        ));
        touch(memory);
        persist(memory);
    }

    @Override
    public void recordImageFallbackDecision(String taskId, ArticleState state, List<ImageFallbackDecisionVO> decisions) {
        if (isBlank(taskId) || decisions == null || decisions.isEmpty()) {
            return;
        }
        ArticleTaskMemoryVO memory = getOrCreate(taskId);
        mergeArticle(memory, findArticle(taskId));
        mergeState(memory, state);
        ArticleTaskMemoryVO.MemoryImageStrategyVO current = memory.getImageStrategy();
        int fallbackCount = (int) decisions.stream()
                .filter(Objects::nonNull)
                .filter(decision -> Boolean.TRUE.equals(decision.getFallbackApplied()))
                .count();
        String fallbackSummary = decisions.stream()
                .filter(Objects::nonNull)
                .limit(3)
                .map(decision -> firstNonBlank(decision.getRequestedMethod(), "--")
                        + "->" + firstNonBlank(decision.getFinalMethod(), "--"))
                .collect(Collectors.joining(", "));
        memory.setImageStrategy(ArticleTaskMemoryVO.MemoryImageStrategyVO.builder()
                .methods(current == null ? null : current.getMethods())
                .needImages(current == null ? null : current.getNeedImages())
                .decisionReason(current == null ? null : current.getDecisionReason())
                .decisionSource(current == null ? null : current.getDecisionSource())
                .requirementCount(current == null ? null : current.getRequirementCount())
                .generatedCount(current == null ? null : current.getGeneratedCount())
                .sources(current == null ? null : current.getSources())
                .fallbackCount(fallbackCount)
                .fallbackSummary(isBlank(fallbackSummary) ? null : fallbackSummary)
                .build());
        addSignal(memory, buildSignal(
                "image_fallback_applied",
                "图片降级已触发",
                "降级次数=" + fallbackCount + "，链路=" + firstNonBlank(fallbackSummary, "--"),
                state != null ? state.getPhase() : memory.getCurrentPhase(),
                "agent5_generate_images"
        ));
        touch(memory);
        persist(memory);
    }

    @Override
    public ArticleTaskMemoryVO getTaskMemory(String taskId) {
        return readMemory(taskId);
    }

    @Override
    public UserCreationPreferenceVO getUserPreference(Long userId) {
        if (userId == null) {
            return null;
        }
        return readUserPreference(userId);
    }

    @Override
    public ArticleMemoryContextVO buildCreationMemoryContext(String taskId, Long userId) {
        ArticleTaskMemoryVO currentMemory = getTaskMemory(taskId);
        Long resolvedUserId = userId != null ? userId : currentMemory == null ? null : currentMemory.getUserId();
        UserCreationPreferenceVO preference = resolvedUserId == null ? null : getUserPreference(resolvedUserId);
        List<ArticleMemoryContextVO.RecalledMemoryCaseVO> successCases = recallSimilarCases(
                taskId, resolvedUserId, currentMemory, ArticleStatusEnum.COMPLETED.getValue(), MAX_RECALLED_CASES
        );
        List<ArticleMemoryContextVO.RecalledMemoryCaseVO> failureCases = recallSimilarCases(
                taskId, resolvedUserId, currentMemory, ArticleStatusEnum.FAILED.getValue(), MAX_RECALLED_CASES
        );

        return ArticleMemoryContextVO.builder()
                .taskId(taskId)
                .userId(resolvedUserId)
                .currentTaskMemory(currentMemory)
                .userPreference(preference)
                .recalledSuccessCases(successCases)
                .recalledFailureCases(failureCases)
                .preferredImageMethods(resolveContextPreferredImageMethods(currentMemory, preference, successCases))
                .avoidImageMethods(resolveContextAvoidImageMethods(failureCases))
                .qualityHints(resolveQualityHints(currentMemory, successCases))
                .failureHints(resolveFailureHints(preference, failureCases))
                .updatedAt(System.currentTimeMillis())
                .build();
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
                .nodeSnapshots(new ArrayList<>())
                .build();
    }

    private void ensureCollections(ArticleTaskMemoryVO memory) {
        if (memory.getQualitySignals() == null) {
            memory.setQualitySignals(new ArrayList<>());
        }
        if (memory.getManualActions() == null) {
            memory.setManualActions(new ArrayList<>());
        }
        if (memory.getNodeSnapshots() == null) {
            memory.setNodeSnapshots(new ArrayList<>());
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
        mergeContentReview(memory, state);
        mergeImageStrategy(memory, state);
    }

    private void mergeContentReview(ArticleTaskMemoryVO memory, ArticleState state) {
        if (memory == null || state == null || state.getContentReview() == null) {
            return;
        }
        ArticleState.ContentReviewResult review = state.getContentReview();
        boolean revised = !isBlank(review.getRevisedContent())
                && !Objects.equals(review.getRevisedContent(), state.getContent());
        memory.setContentReview(ArticleTaskMemoryVO.MemoryContentReviewVO.builder()
                .needsRevision(review.getNeedsRevision())
                .revised(revised || Boolean.TRUE.equals(review.getNeedsRevision()))
                .issueCount(review.getIssues() == null ? 0 : review.getIssues().size())
                .summary(review.getSummary())
                .issues(normalizeStringList(review.getIssues()))
                .qualitySignals(normalizeStringList(review.getQualitySignals()))
                .build());
        mergeContentReviewSignals(memory, review, state.getPhase());
    }

    private void mergeContentReviewSignals(ArticleTaskMemoryVO memory,
                                           ArticleState.ContentReviewResult review,
                                           String phase) {
        if (memory == null || review == null) {
            return;
        }
        addSignal(memory, buildSignal(
                "content_reviewed",
                mapSignalLabel("content_reviewed"),
                firstNonBlank(review.getSummary(), "\u5df2\u5b8c\u6210\u6b63\u6587\u8bc4\u5ba1"),
                phase,
                "agent3_review_content"
        ));
        if (Boolean.TRUE.equals(review.getNeedsRevision())) {
            addSignal(memory, buildSignal(
                    "content_revised",
                    mapSignalLabel("content_revised"),
                    firstNonBlank(review.getSummary(), "\u6b63\u6587\u5df2\u6309\u8bc4\u5ba1\u610f\u89c1\u6700\u5c0f\u4fee\u8ba2"),
                    phase,
                    "agent3_review_content"
            ));
        }
        for (String signalCode : normalizeStringList(review.getQualitySignals())) {
            addSignal(memory, buildSignal(
                    signalCode,
                    mapSignalLabel(signalCode),
                    firstNonBlank(review.getSummary(), signalCode),
                    phase,
                    "agent3_review_content"
            ));
        }
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
        int fallbackCount = state.getImageFallbackRecords() == null ? 0 : (int) state.getImageFallbackRecords().stream()
                .filter(Objects::nonNull)
                .filter(record -> Boolean.TRUE.equals(record.getFallbackApplied()))
                .count();
        String fallbackSummary = state.getImageFallbackRecords() == null ? null : state.getImageFallbackRecords().stream()
                .filter(Objects::nonNull)
                .filter(record -> Boolean.TRUE.equals(record.getFallbackApplied()))
                .limit(3)
                .map(record -> firstNonBlank(record.getRequestedMethod(), "--")
                        + "->" + firstNonBlank(record.getFinalMethod(), "--"))
                .collect(Collectors.joining(", "));
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
                .needImages(current == null ? null : current.getNeedImages())
                .decisionReason(current == null ? null : current.getDecisionReason())
                .decisionSource(current == null ? null : current.getDecisionSource())
                .sources(sources.isEmpty() ? null : sources)
                .requirementCount(requirementCount > 0 ? requirementCount : currentValue(current != null ? current.getRequirementCount() : null))
                .generatedCount(generatedCount > 0 ? generatedCount : currentValue(current != null ? current.getGeneratedCount() : null))
                .fallbackCount(fallbackCount > 0 ? fallbackCount : currentValue(current != null ? current.getFallbackCount() : null))
                .fallbackSummary(!isBlank(fallbackSummary) ? fallbackSummary : current == null ? null : current.getFallbackSummary())
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

    private List<ArticleMemoryContextVO.RecalledMemoryCaseVO> recallSimilarCases(String taskId,
                                                                                  Long userId,
                                                                                  ArticleTaskMemoryVO currentMemory,
                                                                                  String expectedStatus,
                                                                                  int limit) {
        if (userId == null || limit <= 0) {
            return Collections.emptyList();
        }
        List<Article> articles = articleMapper.selectListByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("isDelete", 0)
                .eq("status", expectedStatus)
                .orderBy("updateTime", false));
        if (articles == null || articles.isEmpty()) {
            return Collections.emptyList();
        }
        List<ArticleMemoryContextVO.RecalledMemoryCaseVO> cases = new ArrayList<>();
        for (Article article : articles) {
            if (article == null || isBlank(article.getTaskId()) || Objects.equals(article.getTaskId(), taskId)) {
                continue;
            }
            ArticleTaskMemoryVO memory = readMemory(article.getTaskId());
            ArticleMemoryContextVO.RecalledMemoryCaseVO recalled = buildRecalledCase(article, memory, currentMemory);
            if (recalled != null) {
                cases.add(recalled);
            }
        }
        return cases.stream()
                .sorted((left, right) -> Integer.compare(currentValue(right.getScore()), currentValue(left.getScore())))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private ArticleMemoryContextVO.RecalledMemoryCaseVO buildRecalledCase(Article article,
                                                                          ArticleTaskMemoryVO memory,
                                                                          ArticleTaskMemoryVO currentMemory) {
        if (article == null) {
            return null;
        }
        String status = article.getStatus();
        List<String> imageMethods = resolveMemoryImageMethods(memory, article);
        List<String> qualitySignals = memory == null || memory.getQualitySignals() == null
                ? Collections.emptyList()
                : memory.getQualitySignals().stream()
                .filter(Objects::nonNull)
                .map(ArticleTaskMemoryVO.MemorySignalVO::getCode)
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_HIGHLIGHTS)
                .collect(Collectors.toList());
        String summary = buildRecalledCaseSummary(memory, article, status);
        int score = scoreRecalledCase(article, memory, currentMemory);
        return ArticleMemoryContextVO.RecalledMemoryCaseVO.builder()
                .taskId(article.getTaskId())
                .status(status)
                .topic(article.getTopic())
                .style(article.getStyle())
                .summary(summary)
                .failedNode(memory == null ? null : memory.getLastFailedNode())
                .imageMethods(imageMethods.isEmpty() ? null : imageMethods)
                .qualitySignals(qualitySignals.isEmpty() ? null : qualitySignals)
                .score(score)
                .updatedAt(resolveArticleUpdatedAt(article))
                .build();
    }

    private int scoreRecalledCase(Article article,
                                  ArticleTaskMemoryVO memory,
                                  ArticleTaskMemoryVO currentMemory) {
        int score = 0;
        if (article == null || currentMemory == null) {
            return score;
        }
        if (!isBlank(currentMemory.getStyle()) && Objects.equals(currentMemory.getStyle(), article.getStyle())) {
            score += 3;
        }
        if (containsIgnoreCase(article.getTopic(), currentMemory.getTopic())
                || containsIgnoreCase(currentMemory.getTopic(), article.getTopic())) {
            score += 2;
        }
        if (!isBlank(currentMemory.getUserDescription())
                && containsIgnoreCase(article.getUserDescription(), currentMemory.getUserDescription())) {
            score += 1;
        }
        if (memory != null && currentMemory.getImageStrategy() != null
                && memory.getImageStrategy() != null
                && hasIntersect(currentMemory.getImageStrategy().getMethods(), memory.getImageStrategy().getMethods())) {
            score += 2;
        }
        return score;
    }

    private String buildRecalledCaseSummary(ArticleTaskMemoryVO memory, Article article, String status) {
        if (memory != null && memory.getContentReview() != null && !isBlank(memory.getContentReview().getSummary())) {
            return shorten(memory.getContentReview().getSummary(), 120);
        }
        if (memory != null && memory.getContentSummary() != null && !isBlank(memory.getContentSummary().getText())) {
            return shorten(memory.getContentSummary().getText(), 120);
        }
        if (ArticleStatusEnum.FAILED.getValue().equals(status) && memory != null && !isBlank(memory.getRecentErrorMessage())) {
            return shorten(memory.getRecentErrorMessage(), 120);
        }
        return buildTopicSummary(article);
    }

    private String buildTopicSummary(Article article) {
        if (article == null) {
            return null;
        }
        String topic = firstNonBlank(article.getTopic(), "--");
        String style = firstNonBlank(article.getStyle(), "--");
        return "topic=" + topic + ", style=" + style;
    }

    private List<String> resolveContextPreferredImageMethods(ArticleTaskMemoryVO currentMemory,
                                                             UserCreationPreferenceVO preference,
                                                             List<ArticleMemoryContextVO.RecalledMemoryCaseVO> successCases) {
        LinkedHashSet<String> methods = new LinkedHashSet<>();
        if (currentMemory != null && currentMemory.getImageStrategy() != null) {
            addAllNonBlank(methods, currentMemory.getImageStrategy().getMethods());
        }
        if (preference != null) {
            addAllNonBlank(methods, preference.getPreferredImageMethods());
        }
        if (successCases != null) {
            for (ArticleMemoryContextVO.RecalledMemoryCaseVO successCase : successCases) {
                addAllNonBlank(methods, successCase.getImageMethods());
            }
        }
        return new ArrayList<>(methods);
    }

    private List<String> resolveContextAvoidImageMethods(List<ArticleMemoryContextVO.RecalledMemoryCaseVO> failureCases) {
        LinkedHashSet<String> methods = new LinkedHashSet<>();
        if (failureCases == null) {
            return new ArrayList<>();
        }
        for (ArticleMemoryContextVO.RecalledMemoryCaseVO failureCase : failureCases) {
            addAllNonBlank(methods, failureCase.getImageMethods());
        }
        return new ArrayList<>(methods);
    }

    private List<String> resolveQualityHints(ArticleTaskMemoryVO currentMemory,
                                             List<ArticleMemoryContextVO.RecalledMemoryCaseVO> successCases) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (currentMemory != null && currentMemory.getContentReview() != null) {
            addAllNonBlank(hints, currentMemory.getContentReview().getQualitySignals());
        }
        if (successCases != null) {
            for (ArticleMemoryContextVO.RecalledMemoryCaseVO successCase : successCases) {
                addAllNonBlank(hints, successCase.getQualitySignals());
            }
        }
        return new ArrayList<>(hints);
    }

    private List<String> resolveFailureHints(UserCreationPreferenceVO preference,
                                             List<ArticleMemoryContextVO.RecalledMemoryCaseVO> failureCases) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (preference != null) {
            addAllNonBlank(hints, preference.getRecentFailureTags());
        }
        if (failureCases != null) {
            for (ArticleMemoryContextVO.RecalledMemoryCaseVO failureCase : failureCases) {
                if (!isBlank(failureCase.getFailedNode())) {
                    hints.add(failureCase.getFailedNode());
                }
            }
        }
        return new ArrayList<>(hints);
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
        ArticleTaskMemoryVO memory = cached instanceof String json
                ? GsonUtils.fromJsonSafe(json, ArticleTaskMemoryVO.class)
                : GsonUtils.fromJsonSafe(GsonUtils.toJson(cached), ArticleTaskMemoryVO.class);
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
                || memory.getManualActions() == null || memory.getNodeSnapshots() == null) {
            String json = rawCached instanceof String ? (String) rawCached : GsonUtils.toJson(rawCached);
            LegacyTaskMemoryVO legacy = buildLegacyMemory(json);
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
                if ((memory.getNodeSnapshots() == null || memory.getNodeSnapshots().isEmpty())
                        && legacy.getNodeSnapshots() != null) {
                    memory.setNodeSnapshots(new ArrayList<>(legacy.getNodeSnapshots()));
                }
            }
        }
        touch(memory);
    }

    private LegacyTaskMemoryVO buildLegacyMemory(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            JsonElement rootElement = JsonParser.parseString(json);
            if (!rootElement.isJsonObject()) {
                return null;
            }
            JsonObject root = rootElement.getAsJsonObject();
            LegacyTaskMemoryVO legacy = new LegacyTaskMemoryVO();
            legacy.setOutlineSummary(getPrimitiveString(root, "outlineSummary"));
            legacy.setContentSummary(getPrimitiveString(root, "contentSummary"));
            legacy.setImageStrategy(getPrimitiveString(root, "imageStrategy"));
            legacy.setQualitySignals(getStringList(root, "qualitySignals"));
            legacy.setManualActions(getStringList(root, "manualActions"));
            legacy.setNodeSnapshots(getNodeSnapshotList(root, "nodeSnapshots"));
            return legacy;
        } catch (Exception e) {
            return null;
        }
    }

    private String getPrimitiveString(JsonObject root, String fieldName) {
        JsonElement element = root.get(fieldName);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        return null;
    }

    private List<String> getStringList(JsonObject root, String fieldName) {
        JsonElement element = root.get(fieldName);
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        List<String> result = new ArrayList<>();
        for (JsonElement item : array) {
            if (item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                result.add(item.getAsString());
            }
        }
        return result;
    }

    private List<ArticleTaskMemoryVO.NodeSnapshotVO> getNodeSnapshotList(JsonObject root, String fieldName) {
        JsonElement element = root.get(fieldName);
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        return GsonUtils.fromJsonSafe(
                element.toString(),
                new TypeToken<List<ArticleTaskMemoryVO.NodeSnapshotVO>>() {
                }
        );
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

    private String buildUserPreferenceKey(Long userId) {
        return USER_PREFERENCE_KEY_PREFIX + userId;
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

    private void addNodeSnapshot(ArticleTaskMemoryVO memory, ArticleTaskMemoryVO.NodeSnapshotVO snapshot) {
        if (memory == null || snapshot == null) {
            return;
        }
        deduplicateAndPrependNodeSnapshot(memory.getNodeSnapshots(), snapshot, MAX_NODE_SNAPSHOTS);
    }

    private void deduplicateAndPrepend(List<ArticleTaskMemoryVO.MemorySignalVO> items,
                                       ArticleTaskMemoryVO.MemorySignalVO value,
                                       int maxSize) {
        items.removeIf(item -> Objects.equals(item.getCode(), value.getCode())
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

    private void deduplicateAndPrependNodeSnapshot(List<ArticleTaskMemoryVO.NodeSnapshotVO> items,
                                                   ArticleTaskMemoryVO.NodeSnapshotVO value,
                                                   int maxSize) {
        items.removeIf(item -> Objects.equals(item.getNode(), value.getNode())
                && Objects.equals(item.getPhase(), value.getPhase()));
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
        String[] candidates = content.split("(?<=[。！？!?])\\s+|\\n+");
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

    private ArticleTaskMemoryVO.NodeSnapshotVO buildNodeSnapshot(String node,
                                                                 String phase,
                                                                 String status,
                                                                 ArticleState state) {
        return switch (node) {
            case "workflow_phase_1", "agent1_generate_titles" -> buildTitleNodeSnapshot(node, phase, status, state);
            case "workflow_phase_2", "agent2_generate_outline", "ai_modify_outline" ->
                    buildOutlineNodeSnapshot(node, phase, status, state);
            case "workflow_phase_3", "agent3_generate_content" -> buildContentNodeSnapshot(node, phase, status, state);
            case "agent3_review_content" -> buildContentReviewSnapshot(node, phase, status, state);
            case "image_strategy_router" -> buildImageStrategySnapshot(node, phase, status, state);
            case "agent4_analyze_image_requirements" -> buildImageRequirementSnapshot(node, phase, status, state);
            case "agent5_generate_images" -> buildImageGenerationSnapshot(node, phase, status, state);
            case "agent6_merge_content" -> buildMergeSnapshot(node, phase, status, state);
            default -> ArticleTaskMemoryVO.NodeSnapshotVO.builder()
                    .node(node)
                    .phase(phase)
                    .label(node)
                    .status(status)
                    .summary("节点已执行")
                    .timestamp(System.currentTimeMillis())
                    .build();
        };
    }

    private ArticleTaskMemoryVO.NodeSnapshotVO buildTitleNodeSnapshot(String node,
                                                                      String phase,
                                                                      String status,
                                                                      ArticleState state) {
        List<String> titles = state == null || state.getTitleOptions() == null
                ? Collections.emptyList()
                : state.getTitleOptions().stream()
                .filter(Objects::nonNull)
                .map(title -> firstNonBlank(title.getMainTitle(), "--"))
                .limit(MAX_HIGHLIGHTS)
                .collect(Collectors.toList());
        String summary = titles.isEmpty() ? "尚未产出标题候选" : "已生成 " + titles.size() + " 个标题候选";
        return ArticleTaskMemoryVO.NodeSnapshotVO.builder()
                .node(node)
                .phase(phase)
                .label("标题候选生成")
                .status(status)
                .summary(summary)
                .detail(state != null && state.getTitleOptions() != null
                        ? "候选标题总数=" + state.getTitleOptions().size()
                        : null)
                .highlights(titles.isEmpty() ? null : titles)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private ArticleTaskMemoryVO.NodeSnapshotVO buildOutlineNodeSnapshot(String node,
                                                                        String phase,
                                                                        String status,
                                                                        ArticleState state) {
        List<ArticleState.OutlineSection> sections = state == null || state.getOutline() == null
                ? Collections.emptyList()
                : state.getOutline().getSections();
        List<String> highlights = sections.stream()
                .filter(Objects::nonNull)
                .map(section -> section.getSection() + "." + firstNonBlank(section.getTitle(), "未命名章节"))
                .limit(MAX_HIGHLIGHTS)
                .collect(Collectors.toList());
        return ArticleTaskMemoryVO.NodeSnapshotVO.builder()
                .node(node)
                .phase(phase)
                .label("文章大纲生成")
                .status(status)
                .summary(sections.isEmpty() ? "尚未产出大纲章节" : "已生成 " + sections.size() + " 个大纲章节")
                .detail(sections.isEmpty() ? null : "大纲首章=" + firstNonBlank(sections.get(0).getTitle(), "未命名章节"))
                .highlights(highlights.isEmpty() ? null : highlights)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private ArticleTaskMemoryVO.NodeSnapshotVO buildContentNodeSnapshot(String node,
                                                                        String phase,
                                                                        String status,
                                                                        ArticleState state) {
        String content = state == null ? null : firstNonBlank(state.getContent(), state.getFullContent());
        List<String> highlights = extractHighlightsFromContent(content);
        int length = content == null ? 0 : content.length();
        return ArticleTaskMemoryVO.NodeSnapshotVO.builder()
                .node(node)
                .phase(phase)
                .label("正文生成")
                .status(status)
                .summary(length > 0 ? "已生成正文，长度约 " + length + " 字符" : "正文尚未生成")
                .detail(length > 0 ? shorten(content, 120) : null)
                .highlights(highlights.isEmpty() ? null : highlights)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private ArticleTaskMemoryVO.NodeSnapshotVO buildImageRequirementSnapshot(String node,
                                                                             String phase,
                                                                             String status,
                                                                             ArticleState state) {
        List<ArticleState.ImageRequirement> requirements = state == null || state.getImageRequirements() == null
                ? Collections.emptyList()
                : state.getImageRequirements();
        List<String> highlights = requirements.stream()
                .filter(Objects::nonNull)
                .map(req -> firstNonBlank(req.getImageSource(), "UNKNOWN") + " / "
                        + firstNonBlank(req.getSectionTitle(), firstNonBlank(req.getKeywords(), "未命名图片")))
                .limit(MAX_HIGHLIGHTS)
                .collect(Collectors.toList());
        return ArticleTaskMemoryVO.NodeSnapshotVO.builder()
                .node(node)
                .phase(phase)
                .label("图片需求分析")
                .status(status)
                .summary(requirements.isEmpty() ? "未识别到图片需求" : "已识别 " + requirements.size() + " 条图片需求")
                .detail(requirements.isEmpty() ? null : "首个来源=" + firstNonBlank(requirements.get(0).getImageSource(), "--"))
                .highlights(highlights.isEmpty() ? null : highlights)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private ArticleTaskMemoryVO.NodeSnapshotVO buildImageStrategySnapshot(String node,
                                                                          String phase,
                                                                          String status,
                                                                          ArticleState state) {
        List<String> methods = state == null || state.getEnabledImageMethods() == null
                ? Collections.emptyList()
                : state.getEnabledImageMethods();
        return ArticleTaskMemoryVO.NodeSnapshotVO.builder()
                .node(node)
                .phase(phase)
                .label("图片策略路由")
                .status(status)
                .summary(methods.isEmpty() ? "未命中图片策略方法" : "已路由 " + methods.size() + " 个候选图片方法")
                .detail(methods.isEmpty() ? null : "方法=" + joinValues(methods))
                .highlights(methods.isEmpty() ? null : methods.stream().limit(MAX_HIGHLIGHTS).collect(Collectors.toList()))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private ArticleTaskMemoryVO.NodeSnapshotVO buildContentReviewSnapshot(String node,
                                                                          String phase,
                                                                          String status,
                                                                          ArticleState state) {
        ArticleState.ContentReviewResult review = state == null ? null : state.getContentReview();
        List<String> highlights = review == null || review.getIssues() == null
                ? Collections.emptyList()
                : review.getIssues().stream().limit(MAX_HIGHLIGHTS).collect(Collectors.toList());
        return ArticleTaskMemoryVO.NodeSnapshotVO.builder()
                .node(node)
                .phase(phase)
                .label("正文评审")
                .status(status)
                .summary(review == null ? "正文评审未产出结果"
                        : firstNonBlank(review.getSummary(),
                        Boolean.TRUE.equals(review.getNeedsRevision()) ? "正文已完成最小修订" : "正文评审通过"))
                .detail(review == null ? null : "needsRevision=" + review.getNeedsRevision())
                .highlights(highlights.isEmpty() ? null : highlights)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private ArticleTaskMemoryVO.NodeSnapshotVO buildImageGenerationSnapshot(String node,
                                                                            String phase,
                                                                            String status,
                                                                            ArticleState state) {
        List<ArticleState.ImageResult> images = state == null || state.getImages() == null
                ? Collections.emptyList()
                : state.getImages();
        List<String> highlights = images.stream()
                .filter(Objects::nonNull)
                .map(image -> firstNonBlank(image.getMethod(), "UNKNOWN") + " / "
                        + firstNonBlank(image.getSectionTitle(), firstNonBlank(image.getKeywords(), "未命名图片")))
                .limit(MAX_HIGHLIGHTS)
                .collect(Collectors.toList());
        return ArticleTaskMemoryVO.NodeSnapshotVO.builder()
                .node(node)
                .phase(phase)
                .label("图片生成")
                .status(status)
                .summary(buildImageGenerationSummary(images, state == null ? null : state.getImageFallbackRecords()))
                .detail(buildImageGenerationDetail(images, state == null ? null : state.getImageFallbackRecords()))
                .highlights(highlights.isEmpty() ? null : highlights)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private String buildImageGenerationSummary(List<ArticleState.ImageResult> images,
                                               List<ArticleState.ImageFallbackRecord> fallbackRecords) {
        if (images == null || images.isEmpty()) {
            return "尚未生成图片";
        }
        long fallbackCount = fallbackRecords == null ? 0 : fallbackRecords.stream()
                .filter(Objects::nonNull)
                .filter(record -> Boolean.TRUE.equals(record.getFallbackApplied()))
                .count();
        if (fallbackCount <= 0) {
            return "已生成 " + images.size() + " 张图片";
        }
        return "已生成 " + images.size() + " 张图片，其中 " + fallbackCount + " 张触发降级";
    }

    private String buildImageGenerationDetail(List<ArticleState.ImageResult> images,
                                              List<ArticleState.ImageFallbackRecord> fallbackRecords) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        String firstMethod = "首张图片方法=" + firstNonBlank(images.get(0).getMethod(), "--");
        if (fallbackRecords == null) {
            return firstMethod;
        }
        String fallbackSummary = fallbackRecords.stream()
                .filter(Objects::nonNull)
                .filter(record -> Boolean.TRUE.equals(record.getFallbackApplied()))
                .limit(2)
                .map(record -> firstNonBlank(record.getRequestedMethod(), "--")
                        + "->" + firstNonBlank(record.getFinalMethod(), "--"))
                .collect(Collectors.joining(", "));
        return isBlank(fallbackSummary) ? firstMethod : firstMethod + "，降级链路=" + fallbackSummary;
    }

    private ArticleTaskMemoryVO.NodeSnapshotVO buildMergeSnapshot(String node,
                                                                  String phase,
                                                                  String status,
                                                                  ArticleState state) {
        String fullContent = state == null ? null : state.getFullContent();
        List<String> highlights = extractHighlightsFromContent(fullContent);
        return ArticleTaskMemoryVO.NodeSnapshotVO.builder()
                .node(node)
                .phase(phase)
                .label("图文合并")
                .status(status)
                .summary(isBlank(fullContent) ? "尚未生成最终图文" : "图文已合并完成")
                .detail(isBlank(fullContent) ? null : shorten(fullContent, 120))
                .highlights(highlights.isEmpty() ? null : highlights)
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
            case "task_created" -> "\u4efb\u52a1\u5df2\u521b\u5efa";
            case "title_selected" -> "\u6807\u9898\u5df2\u9009\u5b9a";
            case "outline_locked" -> "\u5927\u7eb2\u5df2\u9501\u5b9a";
            case "content_reviewed" -> "\u6b63\u6587\u5df2\u8bc4\u5ba1";
            case "content_revised" -> "\u6b63\u6587\u5df2\u4fee\u8ba2";
            case "duplicate_reduced" -> "\u91cd\u590d\u8868\u8fbe\u5df2\u6536\u655b";
            case "ending_strengthened" -> "\u7ed3\u5c3e\u6536\u675f\u5df2\u589e\u5f3a";
            case "outline_alignment_checked" -> "\u5927\u7eb2\u5bf9\u9f50\u5df2\u68c0\u67e5";
            case "task_completed" -> "\u4efb\u52a1\u5b8c\u6210";
            case "task_failed" -> "\u4efb\u52a1\u5931\u8d25";
            case "task_resumed" -> "\u4efb\u52a1\u6062\u590d";
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

    private List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .collect(Collectors.toList());
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

    private String shorten(String text, int maxLength) {
        if (isBlank(text)) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String firstNonBlank(String candidate, String fallback) {
        return !isBlank(candidate) ? candidate : fallback;
    }

    private List<String> resolveMemoryImageMethods(ArticleTaskMemoryVO memory, Article article) {
        if (memory != null && memory.getImageStrategy() != null
                && memory.getImageStrategy().getMethods() != null
                && !memory.getImageStrategy().getMethods().isEmpty()) {
            return memory.getImageStrategy().getMethods().stream()
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .collect(Collectors.toList());
        }
        if (article != null && !isBlank(article.getEnabledImageMethods())) {
            List<String> methods = GsonUtils.fromJsonSafe(article.getEnabledImageMethods(),
                    new TypeToken<List<String>>() {
                    });
            if (methods != null) {
                return methods.stream()
                        .filter(Objects::nonNull)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    private Long resolveArticleUpdatedAt(Article article) {
        if (article == null) {
            return null;
        }
        if (article.getUpdateTime() != null) {
            return article.getUpdateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (article.getCompletedTime() != null) {
            return article.getCompletedTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return article.getCreateTime() == null ? null
                : article.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        if (isBlank(text) || isBlank(keyword)) {
            return false;
        }
        return text.toLowerCase().contains(keyword.toLowerCase());
    }

    private boolean hasIntersect(List<String> left, List<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        Set<String> rightSet = right.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
        return left.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .anyMatch(rightSet::contains);
    }

    private void addAllNonBlank(Set<String> target, List<String> values) {
        if (target == null || values == null || values.isEmpty()) {
            return;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                target.add(value);
            }
        }
    }

    private String joinValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(", "));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void persistUserPreference(ArticleTaskMemoryVO memory, Article article, ArticleState state) {
        Long userId = memory != null ? memory.getUserId() : (article != null ? article.getUserId() : null);
        if (userId == null) {
            return;
        }
        UserCreationPreferenceVO preference = readUserPreference(userId);
        if (preference == null) {
            preference = UserCreationPreferenceVO.builder()
                    .userId(userId)
                    .styleUsage(new HashMap<>())
                    .imageMethodUsage(new HashMap<>())
                    .recentFailureTags(new ArrayList<>())
                    .completedTaskCount(0)
                    .build();
        }
        ensurePreferenceCollections(preference);

        String preferredStyle = resolvePreferredStyle(memory, article, state);
        if (!isBlank(preferredStyle)) {
            preference.setPreferredStyle(preferredStyle);
            incrementCounter(preference.getStyleUsage(), preferredStyle);
        }

        List<String> preferredMethods = resolvePreferredImageMethods(memory, article, state);
        if (!preferredMethods.isEmpty()) {
            preference.setPreferredImageMethods(preferredMethods);
            for (String method : preferredMethods) {
                incrementCounter(preference.getImageMethodUsage(), method);
            }
        }

        int completedCount = preference.getCompletedTaskCount() == null ? 0 : preference.getCompletedTaskCount();
        preference.setCompletedTaskCount(completedCount + 1);
        preference.setUpdatedAt(System.currentTimeMillis());

        redisTemplate.opsForValue().set(
                buildUserPreferenceKey(userId),
                GsonUtils.toJson(preference),
                USER_PREFERENCE_TTL_HOURS,
                TimeUnit.HOURS
        );
    }

    private void persistFailurePreference(ArticleTaskMemoryVO memory,
                                          Article article,
                                          String phase,
                                          String failedNode,
                                          String errorMessage) {
        Long userId = memory != null ? memory.getUserId() : (article != null ? article.getUserId() : null);
        if (userId == null) {
            return;
        }
        UserCreationPreferenceVO preference = readUserPreference(userId);
        if (preference == null) {
            preference = UserCreationPreferenceVO.builder()
                    .userId(userId)
                    .styleUsage(new HashMap<>())
                    .imageMethodUsage(new HashMap<>())
                    .recentFailureTags(new ArrayList<>())
                    .completedTaskCount(0)
                    .build();
        }
        ensurePreferenceCollections(preference);
        String tag = firstNonBlank(failedNode, firstNonBlank(phase, firstNonBlank(errorMessage, "workflow_failure")));
        if (!isBlank(tag)) {
            List<String> tags = new ArrayList<>(preference.getRecentFailureTags());
            tags.removeIf(item -> Objects.equals(item, tag));
            tags.add(0, tag);
            while (tags.size() > MAX_FAILURE_TAGS) {
                tags.remove(tags.size() - 1);
            }
            preference.setRecentFailureTags(tags);
            preference.setUpdatedAt(System.currentTimeMillis());
            redisTemplate.opsForValue().set(
                    buildUserPreferenceKey(userId),
                    GsonUtils.toJson(preference),
                    USER_PREFERENCE_TTL_HOURS,
                    TimeUnit.HOURS
            );
        }
    }

    private UserCreationPreferenceVO readUserPreference(Long userId) {
        Object cached = redisTemplate.opsForValue().get(buildUserPreferenceKey(userId));
        if (cached == null) {
            return null;
        }
        UserCreationPreferenceVO preference = cached instanceof UserCreationPreferenceVO userPreference
                ? userPreference
                : cached instanceof String json
                ? GsonUtils.fromJsonSafe(json, UserCreationPreferenceVO.class)
                : GsonUtils.fromJsonSafe(GsonUtils.toJson(cached), UserCreationPreferenceVO.class);
        if (preference == null) {
            return null;
        }
        ensurePreferenceCollections(preference);
        return preference;
    }

    private void ensurePreferenceCollections(UserCreationPreferenceVO preference) {
        if (preference.getStyleUsage() == null) {
            preference.setStyleUsage(new HashMap<>());
        }
        if (preference.getImageMethodUsage() == null) {
            preference.setImageMethodUsage(new HashMap<>());
        }
        if (preference.getRecentFailureTags() == null) {
            preference.setRecentFailureTags(new ArrayList<>());
        }
        if (preference.getCompletedTaskCount() == null) {
            preference.setCompletedTaskCount(0);
        }
    }

    private String resolvePreferredStyle(ArticleTaskMemoryVO memory, Article article, ArticleState state) {
        if (memory != null && !isBlank(memory.getStyle())) {
            return memory.getStyle();
        }
        if (article != null && !isBlank(article.getStyle())) {
            return article.getStyle();
        }
        return state != null ? state.getStyle() : null;
    }

    private List<String> resolvePreferredImageMethods(ArticleTaskMemoryVO memory, Article article, ArticleState state) {
        if (memory != null && memory.getImageStrategy() != null
                && memory.getImageStrategy().getMethods() != null
                && !memory.getImageStrategy().getMethods().isEmpty()) {
            return memory.getImageStrategy().getMethods().stream()
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .collect(Collectors.toList());
        }
        if (state != null && state.getEnabledImageMethods() != null && !state.getEnabledImageMethods().isEmpty()) {
            return state.getEnabledImageMethods().stream()
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .collect(Collectors.toList());
        }
        if (article != null && !isBlank(article.getEnabledImageMethods())) {
            List<String> methods = GsonUtils.fromJsonSafe(article.getEnabledImageMethods(),
                    new TypeToken<List<String>>() {
                    });
            if (methods != null) {
                return methods.stream()
                        .filter(Objects::nonNull)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    private void incrementCounter(Map<String, Integer> counterMap, String key) {
        if (counterMap == null || isBlank(key)) {
            return;
        }
        counterMap.put(key, counterMap.getOrDefault(key, 0) + 1);
    }

    @lombok.Data
    private static class LegacyTaskMemoryVO {
        private String outlineSummary;
        private String contentSummary;
        private String imageStrategy;
        private List<String> qualitySignals;
        private List<String> manualActions;
        private List<ArticleTaskMemoryVO.NodeSnapshotVO> nodeSnapshots;
    }
}
