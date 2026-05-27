package com.yupi.template.eval;

import cn.hutool.core.date.DateUtil;
import com.google.gson.reflect.TypeToken;
import com.mybatisflex.core.query.QueryWrapper;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.AgentLog;
import com.yupi.template.model.entity.User;
import com.yupi.template.model.enums.ArticlePhaseEnum;
import com.yupi.template.model.enums.ArticleStatusEnum;
import com.yupi.template.model.vo.AgentExecutionStats;
import com.yupi.template.model.vo.ArticleMemoryContextVO;
import com.yupi.template.model.vo.ArticleTaskSnapshotVO;
import com.yupi.template.model.vo.ArticleVO;
import com.yupi.template.model.vo.NodeExecutionLogVO;
import com.yupi.template.model.vo.NodeReplaySnapshotVO;
import com.yupi.template.service.AgentLogService;
import com.yupi.template.service.ArticleAsyncService;
import com.yupi.template.service.ArticleService;
import com.yupi.template.service.UserService;
import com.yupi.template.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Local batch evaluation runner triggered by explicit config.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@EnableConfigurationProperties(EvalRunProperties.class)
public class EvalRunner implements CommandLineRunner {

    private final EvalRunProperties properties;

    private final UserService userService;

    private final ArticleService articleService;

    private final ArticleAsyncService articleAsyncService;

    private final AgentLogService agentLogService;

    @Override
    public void run(String... args) throws Exception {
        if (!properties.isEnabled()) {
            return;
        }
        log.info("Eval runner enabled, casesDir={}, runsDir={}", properties.getCasesDir(), properties.getRunsDir());
        User evalUser = loadEvalUser();
        List<EvalCase> cases = loadCases();
        if (cases.isEmpty()) {
            log.warn("No eval cases found under {}", properties.getCasesDir());
            return;
        }
        Path runDir = createRunDirectory();
        List<EvalCaseResult> results = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            results.add(executeCase(evalCase, evalUser, runDir));
        }
        writeSummary(runDir, results);
        log.info("Eval runner completed, runDir={}, caseCount={}", runDir, results.size());
    }

    private User loadEvalUser() {
        User evalUser = userService.getOne(QueryWrapper.create().eq("userAccount", properties.getUserAccount()));
        if (evalUser == null) {
            throw new IllegalStateException("Eval user not found: " + properties.getUserAccount());
        }
        return evalUser;
    }

    private List<EvalCase> loadCases() throws IOException {
        Path casesDir = Paths.get(properties.getCasesDir());
        if (!Files.exists(casesDir)) {
            return List.of();
        }
        try (var stream = Files.list(casesDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::readCase)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    private EvalCase readCase(Path path) {
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return GsonUtils.fromJson(json, EvalCase.class);
        } catch (Exception e) {
            log.error("Failed to read eval case: {}", path, e);
            return null;
        }
    }

    private Path createRunDirectory() throws IOException {
        String runId = DateUtil.format(DateUtil.date(), "yyyy-MM-dd-HHmmss");
        Path runDir = Paths.get(properties.getRunsDir(), runId);
        Files.createDirectories(runDir.resolve("cases"));
        return runDir;
    }

    private EvalCaseResult executeCase(EvalCase evalCase, User evalUser, Path runDir) {
        EvalCaseResult result = new EvalCaseResult();
        result.setCaseId(evalCase.getCaseId());
        result.setTopic(evalCase.getTopic());
        result.setStyle(evalCase.getStyle());
        result.setTags(evalCase.getTags());
        try {
            String taskId = articleService.createArticleTaskWithQuotaCheck(
                    evalCase.getTopic(),
                    evalCase.getStyle(),
                    evalCase.getEnabledImageMethods(),
                    evalUser
            );
            result.setTaskId(taskId);

            articleAsyncService.executePhase1(taskId, evalCase.getTopic(), evalCase.getStyle());
            waitForPhase(taskId, ArticlePhaseEnum.TITLE_SELECTING.getValue());

            ArticleTaskSnapshotVO titleSnapshot = articleService.getTaskSnapshot(taskId, evalUser);
            confirmTitle(taskId, titleSnapshot, evalCase, evalUser);

            waitForPhase(taskId, ArticlePhaseEnum.OUTLINE_EDITING.getValue());

            ArticleTaskSnapshotVO outlineSnapshot = articleService.getTaskSnapshot(taskId, evalUser);
            confirmOutline(taskId, outlineSnapshot, evalUser);

            waitForTerminal(taskId, evalUser);

            ArticleTaskSnapshotVO finalSnapshot = articleService.getTaskSnapshot(taskId, evalUser);
            ArticleVO article = articleService.getArticleDetail(taskId, evalUser);
            AgentExecutionStats executionStats = agentLogService.getExecutionStats(taskId);
            List<NodeReplaySnapshotVO> replaySnapshots = articleService.getNodeReplaySnapshots(taskId, evalUser);
            ArticleMemoryContextVO memoryContext = articleService.getCreationMemoryContext(taskId, evalUser);

            result.setStatus(finalSnapshot.getStatus());
            result.setPhase(finalSnapshot.getPhase());
            result.setErrorMessage(finalSnapshot.getErrorMessage());
            result.setProgress(finalSnapshot.getProgress());
            result.setFullContentLength(article != null && article.getFullContent() != null ? article.getFullContent().length() : 0);
            result.setImageCount(article != null && article.getImages() != null ? article.getImages().size() : 0);
            result.setNodeCount(executionStats == null ? 0 : executionStats.getNodeCount());
            result.setOverallStatus(executionStats == null ? null : executionStats.getOverallStatus());
            result.setTotalDurationMs(executionStats == null ? null : executionStats.getTotalDurationMs());
            result.setFallbackCount(countFallbacks(finalSnapshot));
            result.setMemorySummary(buildMemorySummary(memoryContext));
            result.setDecisionSources(summarizeDecisionSources(executionStats));
            result.setFailedNodes(summarizeFailedNodes(executionStats));
            result.setReplaySnapshotCount(replaySnapshots == null ? 0 : replaySnapshots.size());
            result.setExpectations(evalCase.getExpectations());
            result.setChecks(buildChecks(evalCase, finalSnapshot));

            writeCaseResult(runDir, result, article, finalSnapshot, executionStats, replaySnapshots, memoryContext);
        } catch (Exception e) {
            result.setStatus(ArticleStatusEnum.FAILED.getValue());
            result.setErrorMessage(e.getMessage());
            log.error("Eval case failed: {}", evalCase.getCaseId(), e);
            try {
                writeCaseResult(runDir, result, null, null, null, null, null);
            } catch (Exception ioException) {
                log.error("Failed to write eval case result: {}", evalCase.getCaseId(), ioException);
            }
        }
        return result;
    }

    private void confirmTitle(String taskId, ArticleTaskSnapshotVO snapshot, EvalCase evalCase, User evalUser) {
        if (snapshot == null || snapshot.getTitleOptions() == null || snapshot.getTitleOptions().isEmpty()) {
            throw new IllegalStateException("No title options generated for task " + taskId);
        }
        ArticleState.TitleOption option = snapshot.getTitleOptions().get(0);
        articleService.confirmTitle(taskId, option.getMainTitle(), option.getSubTitle(),
                evalCase.getUserDescription(), evalUser);
        articleAsyncService.executePhase2(taskId);
    }

    private void confirmOutline(String taskId, ArticleTaskSnapshotVO snapshot, User evalUser) {
        if (snapshot == null || snapshot.getOutline() == null || snapshot.getOutline().isEmpty()) {
            throw new IllegalStateException("No outline generated for task " + taskId);
        }
        articleService.confirmOutline(taskId, snapshot.getOutline(), evalUser);
        articleAsyncService.executePhase3(taskId);
    }

    private void waitForPhase(String taskId, String targetPhase) throws InterruptedException {
        long deadline = System.currentTimeMillis() + properties.getTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            var article = articleService.getByTaskId(taskId);
            if (article == null) {
                throw new IllegalStateException("Article not found for task " + taskId);
            }
            if (ArticleStatusEnum.FAILED.getValue().equals(article.getStatus())) {
                throw new IllegalStateException("Task failed before reaching phase " + targetPhase + ": " + article.getErrorMessage());
            }
            if (targetPhase.equals(article.getPhase())) {
                return;
            }
            Thread.sleep(properties.getPollIntervalMs());
        }
        throw new IllegalStateException("Timeout waiting phase " + targetPhase + " for task " + taskId);
    }

    private void waitForTerminal(String taskId, User evalUser) throws InterruptedException {
        long deadline = System.currentTimeMillis() + properties.getTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            ArticleTaskSnapshotVO snapshot = articleService.getTaskSnapshot(taskId, evalUser);
            if (snapshot == null) {
                throw new IllegalStateException("Task snapshot missing for " + taskId);
            }
            if (ArticleStatusEnum.COMPLETED.getValue().equals(snapshot.getStatus())
                    || ArticleStatusEnum.FAILED.getValue().equals(snapshot.getStatus())) {
                return;
            }
            Thread.sleep(properties.getPollIntervalMs());
        }
        throw new IllegalStateException("Timeout waiting terminal state for task " + taskId);
    }

    private int countFallbacks(ArticleTaskSnapshotVO snapshot) {
        if (snapshot == null || snapshot.getImageFallbackRecords() == null) {
            return 0;
        }
        return (int) snapshot.getImageFallbackRecords().stream()
                .filter(Objects::nonNull)
                .filter(item -> Boolean.TRUE.equals(item.getFallbackApplied()))
                .count();
    }

    private Map<String, Object> buildMemorySummary(ArticleMemoryContextVO context) {
        if (context == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("preferredImageMethods", sizeOf(context.getPreferredImageMethods()));
        summary.put("avoidImageMethods", sizeOf(context.getAvoidImageMethods()));
        summary.put("qualityHints", sizeOf(context.getQualityHints()));
        summary.put("failureHints", sizeOf(context.getFailureHints()));
        summary.put("successCases", sizeOf(context.getRecalledSuccessCases()));
        summary.put("failureCases", sizeOf(context.getRecalledFailureCases()));
        return summary;
    }

    private Map<String, Long> summarizeDecisionSources(AgentExecutionStats stats) {
        if (stats == null || stats.getNodeLogs() == null) {
            return Map.of();
        }
        return stats.getNodeLogs().stream()
                .map(NodeExecutionLogVO::getDecisionSource)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(item -> item, LinkedHashMap::new, Collectors.counting()));
    }

    private List<String> summarizeFailedNodes(AgentExecutionStats stats) {
        if (stats == null || stats.getNodeLogs() == null) {
            return List.of();
        }
        return stats.getNodeLogs().stream()
                .filter(Objects::nonNull)
                .filter(item -> ArticleStatusEnum.FAILED.getValue().equals(item.getStatus()))
                .map(NodeExecutionLogVO::getNode)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildChecks(EvalCase evalCase, ArticleTaskSnapshotVO snapshot) {
        Map<String, Object> checks = new LinkedHashMap<>();
        if (evalCase == null || evalCase.getExpectations() == null) {
            return checks;
        }
        Map<String, Object> expectations = evalCase.getExpectations();
        checks.put("titleCount", sizeOf(snapshot == null ? null : snapshot.getTitleOptions()));
        checks.put("outlineSections", sizeOf(snapshot == null ? null : snapshot.getOutline()));
        checks.put("imageCount", sizeOf(snapshot == null ? null : snapshot.getImages()));
        checks.put("reviewGenerated", snapshot != null && snapshot.getContentReview() != null);
        if (expectations.get("minTitleCount") instanceof Number number) {
            checks.put("titleCountPass", sizeOf(snapshot == null ? null : snapshot.getTitleOptions()) >= number.intValue());
        }
        if (expectations.get("minOutlineSections") instanceof Number number) {
            checks.put("outlineCountPass", sizeOf(snapshot == null ? null : snapshot.getOutline()) >= number.intValue());
        }
        if (expectations.get("minImageCount") instanceof Number number) {
            checks.put("imageCountPass", sizeOf(snapshot == null ? null : snapshot.getImages()) >= number.intValue());
        }
        return checks;
    }

    private void writeCaseResult(Path runDir,
                                 EvalCaseResult result,
                                 ArticleVO article,
                                 ArticleTaskSnapshotVO snapshot,
                                 AgentExecutionStats stats,
                                 List<NodeReplaySnapshotVO> replaySnapshots,
                                 ArticleMemoryContextVO memoryContext) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("result", result);
        payload.put("article", buildArticlePayload(article));
        payload.put("snapshot", snapshot);
        payload.put("executionStats", buildExecutionStatsPayload(stats));
        payload.put("replaySnapshots", replaySnapshots);
        payload.put("memoryContext", memoryContext);
        Path caseFile = runDir.resolve("cases").resolve(result.getCaseId() + ".json");
        Files.writeString(caseFile, GsonUtils.toJson(payload), StandardCharsets.UTF_8);
    }

    private Map<String, Object> buildArticlePayload(ArticleVO article) {
        if (article == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", article.getId());
        payload.put("taskId", article.getTaskId());
        payload.put("userId", article.getUserId());
        payload.put("topic", article.getTopic());
        payload.put("userDescription", article.getUserDescription());
        payload.put("mainTitle", article.getMainTitle());
        payload.put("subTitle", article.getSubTitle());
        payload.put("titleOptions", article.getTitleOptions());
        payload.put("outline", article.getOutline());
        payload.put("contentLength", article.getContent() == null ? 0 : article.getContent().length());
        payload.put("fullContentLength", article.getFullContent() == null ? 0 : article.getFullContent().length());
        payload.put("coverImage", article.getCoverImage());
        payload.put("images", article.getImages());
        payload.put("status", article.getStatus());
        payload.put("phase", article.getPhase());
        payload.put("errorMessage", article.getErrorMessage());
        payload.put("createTime", formatTime(article.getCreateTime()));
        payload.put("completedTime", formatTime(article.getCompletedTime()));
        payload.put("updateTime", formatTime(article.getUpdateTime()));
        return payload;
    }

    private Map<String, Object> buildExecutionStatsPayload(AgentExecutionStats stats) {
        if (stats == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", stats.getTaskId());
        payload.put("totalDurationMs", stats.getTotalDurationMs());
        payload.put("agentCount", stats.getAgentCount());
        payload.put("agentDurations", stats.getAgentDurations());
        payload.put("nodeCount", stats.getNodeCount());
        payload.put("nodeDurations", stats.getNodeDurations());
        payload.put("overallStatus", stats.getOverallStatus());
        payload.put("logs", buildAgentLogPayload(stats.getLogs()));
        payload.put("nodeLogs", stats.getNodeLogs());
        return payload;
    }

    private List<Map<String, Object>> buildAgentLogPayload(List<AgentLog> logs) {
        if (logs == null) {
            return null;
        }
        return logs.stream()
                .filter(Objects::nonNull)
                .map(logItem -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("id", logItem.getId());
                    payload.put("taskId", logItem.getTaskId());
                    payload.put("agentName", logItem.getAgentName());
                    payload.put("startTime", formatTime(logItem.getStartTime()));
                    payload.put("endTime", formatTime(logItem.getEndTime()));
                    payload.put("durationMs", logItem.getDurationMs());
                    payload.put("status", logItem.getStatus());
                    payload.put("errorMessage", logItem.getErrorMessage());
                    payload.put("prompt", logItem.getPrompt());
                    payload.put("inputData", logItem.getInputData());
                    payload.put("outputData", logItem.getOutputData());
                    payload.put("createTime", formatTime(logItem.getCreateTime()));
                    payload.put("updateTime", formatTime(logItem.getUpdateTime()));
                    return payload;
                })
                .collect(Collectors.toList());
    }

    private String formatTime(java.time.LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    private void writeSummary(Path runDir, List<EvalCaseResult> results) throws IOException {
        long successCount = results.stream()
                .filter(item -> ArticleStatusEnum.COMPLETED.getValue().equals(item.getStatus()))
                .count();
        long failedCount = results.stream()
                .filter(item -> ArticleStatusEnum.FAILED.getValue().equals(item.getStatus()))
                .count();
        int totalFallbackCount = results.stream()
                .map(EvalCaseResult::getFallbackCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        double avgDuration = results.stream()
                .map(EvalCaseResult::getTotalDurationMs)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0D);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseCount", results.size());
        summary.put("successCount", successCount);
        summary.put("failedCount", failedCount);
        summary.put("totalFallbackCount", totalFallbackCount);
        summary.put("avgDurationMs", avgDuration);
        summary.put("results", results);
        Files.writeString(runDir.resolve("summary.json"), GsonUtils.toJson(summary), StandardCharsets.UTF_8);
    }

    private int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    @lombok.Data
    private static class EvalCaseResult implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String caseId;
        private String taskId;
        private String topic;
        private String style;
        private List<String> tags;
        private String status;
        private String phase;
        private Integer progress;
        private String overallStatus;
        private String errorMessage;
        private Integer totalDurationMs;
        private Integer nodeCount;
        private Integer imageCount;
        private Integer fullContentLength;
        private Integer fallbackCount;
        private Integer replaySnapshotCount;
        private Map<String, Object> expectations;
        private Map<String, Object> checks;
        private Map<String, Object> memorySummary;
        private Map<String, Long> decisionSources;
        private List<String> failedNodes;
    }
}
