package com.yupi.template.service;

import com.yupi.template.agent.agents.AgentPromptSupport;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.dto.article.ArticleWorkflowEvent;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.enums.ArticlePhaseEnum;
import com.yupi.template.model.enums.ArticleStatusEnum;
import com.yupi.template.model.enums.SseMessageTypeEnum;
import com.yupi.template.model.vo.ImageStrategyDecisionVO;
import com.yupi.template.model.vo.NodeExecutionMetadata;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Async article workflow service.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Service
@Slf4j
public class ArticleAsyncService {

    @Resource
    private ArticleGenerationWorkflowService articleGenerationWorkflowService;

    @Resource
    private ArticleWorkflowStateFactory articleWorkflowStateFactory;

    @Resource
    private ArticleService articleService;

    @Resource
    private ArticleWorkflowEventService articleWorkflowEventService;

    @Resource
    private ArticleMemoryService articleMemoryService;

    @Resource
    private ArticleAgentService articleAgentService;

    @Resource
    private AgentPromptSupport agentPromptSupport;

    @Resource
    private ImageStrategyRouterService imageStrategyRouterService;

    @Async("articleExecutor")
    public void executePhase1(String taskId, String topic, String style) {
        log.info("Phase 1 started, taskId={}, mode={}", taskId, articleGenerationWorkflowService.getWorkflowMode());

        ArticleState state = new ArticleState();
        state.setTaskId(taskId);
        state.setTopic(topic);
        state.setStyle(style);
        state.setPhase(ArticlePhaseEnum.TITLE_GENERATING.getValue());
        state.setProgress(1);

        try {
            articleGenerationWorkflowService.emitNodeStart(taskId, ArticlePhaseEnum.TITLE_GENERATING.getValue(), "workflow_phase_1");
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.PROCESSING, null);
            articleService.updatePhase(taskId, ArticlePhaseEnum.TITLE_GENERATING);
            articleService.saveTaskSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.TITLE_GENERATING, null);
            articleWorkflowEventService.publishNodeInfo(taskId, ArticlePhaseEnum.TITLE_GENERATING.getValue(),
                    "workflow_phase_1", "Article task entered title generation");

            articleGenerationWorkflowService.generateTitles(state,
                    event -> handleWorkflowEvent(taskId, state, event));
            articleMemoryService.recordNodeSnapshot(taskId, ArticlePhaseEnum.TITLE_GENERATING.getValue(),
                    "workflow_phase_1", "SUCCESS", state);

            articleService.saveTitleOptions(taskId, state.getTitleOptions());
            articleService.updatePhase(taskId, ArticlePhaseEnum.TITLE_SELECTING);
            state.setPhase(ArticlePhaseEnum.TITLE_SELECTING.getValue());
            state.setProgress(1);
            articleService.saveTaskSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.TITLE_SELECTING, null);
            articleWorkflowEventService.publishSystemEvent(taskId, state, SseMessageTypeEnum.TITLES_GENERATED,
                    ArticlePhaseEnum.TITLE_SELECTING.getValue(), 1,
                    payload("titleOptions", state.getTitleOptions()));
            articleGenerationWorkflowService.emitNodeSuccess(taskId, ArticlePhaseEnum.TITLE_GENERATING.getValue(), "workflow_phase_1");
            log.info("Phase 1 completed, taskId={}", taskId);
        } catch (Exception e) {
            articleGenerationWorkflowService.emitNodeFailure(taskId, ArticlePhaseEnum.TITLE_GENERATING.getValue(), "workflow_phase_1", e);
            handlePhaseError(taskId, state, e);
        }
    }

    @Async("articleExecutor")
    public void executePhase2(String taskId) {
        log.info("Phase 2 started, taskId={}, mode={}", taskId, articleGenerationWorkflowService.getWorkflowMode());

        ArticleState state = articleWorkflowStateFactory.buildFromArticle(taskId);
        state.setPhase(ArticlePhaseEnum.OUTLINE_GENERATING.getValue());
        state.setProgress(2);

        try {
            articleGenerationWorkflowService.emitNodeStart(taskId, ArticlePhaseEnum.OUTLINE_GENERATING.getValue(), "workflow_phase_2");
            articleService.saveTaskSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.OUTLINE_GENERATING, null);
            articleWorkflowEventService.publishNodeInfo(taskId, ArticlePhaseEnum.OUTLINE_GENERATING.getValue(),
                    "workflow_phase_2", "Outline workflow restored from article state");

            articleGenerationWorkflowService.generateOutline(state,
                    event -> handleWorkflowEvent(taskId, state, event));
            articleMemoryService.recordNodeSnapshot(taskId, ArticlePhaseEnum.OUTLINE_GENERATING.getValue(),
                    "workflow_phase_2", "SUCCESS", state);

            Article articleToUpdate = articleService.getByTaskId(taskId);
            articleToUpdate.setOutline(GsonUtils.toJson(state.getOutline().getSections()));
            articleService.updateById(articleToUpdate);
            articleService.updatePhase(taskId, ArticlePhaseEnum.OUTLINE_EDITING);
            state.setPhase(ArticlePhaseEnum.OUTLINE_EDITING.getValue());
            state.setProgress(2);
            state.setOutlineRaw(GsonUtils.toJson(Map.of("sections", state.getOutline().getSections())));
            articleService.saveTaskSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.OUTLINE_EDITING, null);
            articleWorkflowEventService.publishSystemEvent(taskId, state, SseMessageTypeEnum.OUTLINE_GENERATED,
                    ArticlePhaseEnum.OUTLINE_EDITING.getValue(), 2,
                    payload("outline", state.getOutline().getSections()));
            articleGenerationWorkflowService.emitNodeSuccess(taskId, ArticlePhaseEnum.OUTLINE_GENERATING.getValue(), "workflow_phase_2");
            log.info("Phase 2 completed, taskId={}", taskId);
        } catch (Exception e) {
            articleGenerationWorkflowService.emitNodeFailure(taskId, ArticlePhaseEnum.OUTLINE_GENERATING.getValue(), "workflow_phase_2", e);
            handlePhaseError(taskId, state, e);
        }
    }

    @Async("articleExecutor")
    public void executePhase3(String taskId) {
        log.info("Phase 3 started, taskId={}, mode={}", taskId, articleGenerationWorkflowService.getWorkflowMode());

        ArticleState state = articleWorkflowStateFactory.buildFromArticle(taskId);
        state.setPhase(ArticlePhaseEnum.CONTENT_GENERATING.getValue());
        state.setProgress(3);

        try {
            articleGenerationWorkflowService.emitNodeStart(taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(), "workflow_phase_3");
            articleService.saveTaskSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.CONTENT_GENERATING, null);
            articleWorkflowEventService.publishNodeInfo(taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(),
                    "workflow_phase_3", "Content workflow restored from article state");

            articleGenerationWorkflowService.generateContent(state,
                    event -> handleWorkflowEvent(taskId, state, event));
            articleMemoryService.recordNodeSnapshot(taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(),
                    "workflow_phase_3", "SUCCESS", state);

            articleService.saveArticleContent(taskId, state);
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.COMPLETED, null);
            articleMemoryService.recordTaskCompleted(taskId, state);
            state.setProgress(6);
            articleService.saveTaskSnapshot(state, ArticleStatusEnum.COMPLETED,
                    ArticlePhaseEnum.CONTENT_GENERATING, null);
            articleWorkflowEventService.publishSystemEvent(taskId, state, SseMessageTypeEnum.ALL_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 6,
                    payload("taskId", taskId));
            articleGenerationWorkflowService.emitNodeSuccess(taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(), "workflow_phase_3");
            articleWorkflowEventService.complete(taskId);
            log.info("Phase 3 completed, taskId={}", taskId);
        } catch (Exception e) {
            articleGenerationWorkflowService.emitNodeFailure(taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(), "workflow_phase_3", e);
            handlePhaseError(taskId, state, e);
        }
    }

    @Async("articleExecutor")
    public void resumeTask(String taskId) {
        ArticleState state = articleWorkflowStateFactory.buildFromArticle(taskId);
        String phase = state.getPhase();
        log.info("Resume workflow requested, taskId={}, phase={}", taskId, phase);

        if (phase == null || phase.isBlank() || ArticlePhaseEnum.PENDING.getValue().equals(phase)
                || ArticlePhaseEnum.TITLE_GENERATING.getValue().equals(phase)
                || ArticlePhaseEnum.TITLE_SELECTING.getValue().equals(phase)) {
            executePhase1(taskId, state.getTopic(), state.getStyle());
            return;
        }
        if (ArticlePhaseEnum.OUTLINE_GENERATING.getValue().equals(phase)
                || ArticlePhaseEnum.OUTLINE_EDITING.getValue().equals(phase)) {
            executePhase2(taskId);
            return;
        }
        if (ArticlePhaseEnum.CONTENT_GENERATING.getValue().equals(phase)) {
            executePhase3(taskId);
            return;
        }

        throw new IllegalStateException("Unsupported resume phase: " + phase);
    }

    @Async("articleExecutor")
    public void resumeNodeReplay(String taskId, String node) {
        ArticleState state = articleWorkflowStateFactory.buildFromArticle(taskId);
        String phase = state.getPhase();
        log.info("Node replay requested, taskId={}, phase={}, node={}", taskId, phase, node);

        if (!ArticlePhaseEnum.CONTENT_GENERATING.getValue().equals(phase) || !isContentReplayNode(node)) {
            log.info("Fallback to phase resume because node replay is not supported yet, taskId={}, phase={}, node={}",
                    taskId, phase, node);
            resumeTask(taskId);
            return;
        }

        state.setPhase(ArticlePhaseEnum.CONTENT_GENERATING.getValue());
        state.setProgress(resolveReplayProgress(node));

        try {
            articleWorkflowEventService.publishNodeInfo(taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(),
                    "node_replay", "Start replay from node " + node);
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.PROCESSING, null);
            articleService.updatePhase(taskId, ArticlePhaseEnum.CONTENT_GENERATING);
            articleService.saveTaskSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.CONTENT_GENERATING, null);

            executeContentNodeReplay(taskId, state, node);
            finishContentReplay(taskId, state, node);
            log.info("Node replay completed, taskId={}, node={}", taskId, node);
        } catch (Exception e) {
            articleWorkflowEventService.publishNodeInfo(taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(),
                    "node_replay", "Node replay failed at " + node + ": " + e.getClass().getSimpleName());
            handlePhaseError(taskId, state, e);
        }
    }

    private void handlePhaseError(String taskId, ArticleState state, Exception e) {
        log.error("Async workflow failed, taskId={}", taskId, e);
        articleWorkflowEventService.publishNodeInfo(taskId, state.getPhase(), "workflow_error",
                "Async workflow error captured: " + e.getClass().getSimpleName());
        articleService.updateArticleStatus(taskId, ArticleStatusEnum.FAILED, e.getMessage());
        state.setErrorMessage(e.getMessage());
        articleMemoryService.recordTaskFailed(taskId, state.getPhase(), "workflow_error", e.getMessage());
        ArticlePhaseEnum phase = ArticlePhaseEnum.getByValue(state.getPhase());
        articleService.saveTaskSnapshot(state, ArticleStatusEnum.FAILED,
                phase == null ? ArticlePhaseEnum.PENDING : phase,
                e.getMessage());
        articleWorkflowEventService.publishSystemEvent(taskId, state, SseMessageTypeEnum.ERROR,
                state.getPhase(), state.getProgress(), payload("message", e.getMessage()));
        articleWorkflowEventService.complete(taskId);
    }

    private void executeContentNodeReplay(String taskId, ArticleState state, String node) {
        Consumer<String> streamHandler = rawMessage -> {
            ArticleWorkflowEvent event = toWorkflowEvent(rawMessage);
            if (event != null) {
                handleWorkflowEvent(taskId, state, event);
            }
        };

        switch (node) {
            case "agent3_generate_content" -> {
                resetStateForReplay(state, node);
                articleAgentService.agent3GenerateContent(state, streamHandler, buildReplayMetadata(node));
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.AGENT3_COMPLETE));
                articleAgentService.agent3ReviewContent(state, buildReplayMetadata("agent3_review_content"));
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.AGENT3_REVIEW_COMPLETE));

                applyImageStrategyRouter(state);
                articleAgentService.agent4AnalyzeImageRequirements(state, buildReplayMetadata("agent4_analyze_image_requirements"));
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.AGENT4_COMPLETE));

                articleAgentService.agent5GenerateImages(state, streamHandler, buildImageGenerationMetadata());
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.AGENT5_COMPLETE));

                articleAgentService.mergeImagesIntoContent(state);
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.MERGE_COMPLETE));
            }
            case "agent3_review_content" -> {
                resetStateForReplay(state, node);
                articleAgentService.agent3ReviewContent(state, buildReplayMetadata(node));
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.AGENT3_REVIEW_COMPLETE));

                applyImageStrategyRouter(state);
                articleAgentService.agent4AnalyzeImageRequirements(state, buildReplayMetadata("agent4_analyze_image_requirements"));
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.AGENT4_COMPLETE));

                articleAgentService.agent5GenerateImages(state, streamHandler, buildImageGenerationMetadata());
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.AGENT5_COMPLETE));

                articleAgentService.mergeImagesIntoContent(state);
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.MERGE_COMPLETE));
            }
            case "agent4_analyze_image_requirements" -> {
                resetStateForReplay(state, node);
                applyImageStrategyRouter(state);
                articleAgentService.agent4AnalyzeImageRequirements(state, buildReplayMetadata(node));
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.AGENT4_COMPLETE));

                articleAgentService.agent5GenerateImages(state, streamHandler, buildImageGenerationMetadata());
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.AGENT5_COMPLETE));

                articleAgentService.mergeImagesIntoContent(state);
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.MERGE_COMPLETE));
            }
            case "agent5_generate_images" -> {
                resetStateForReplay(state, node);
                articleAgentService.agent5GenerateImages(state, streamHandler, buildImageGenerationMetadata());
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.AGENT5_COMPLETE));

                articleAgentService.mergeImagesIntoContent(state);
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.MERGE_COMPLETE));
            }
            case "agent6_merge_content" -> {
                resetStateForReplay(state, node);
                articleAgentService.mergeImagesIntoContent(state);
                handleWorkflowEvent(taskId, state, ArticleWorkflowEvent.simple(SseMessageTypeEnum.MERGE_COMPLETE));
            }
            default -> throw new IllegalStateException("Unsupported replay node: " + node);
        }
    }

    private void finishContentReplay(String taskId, ArticleState state, String node) {
        articleService.saveArticleContent(taskId, state);
        articleService.updateArticleStatus(taskId, ArticleStatusEnum.COMPLETED, null);
        state.setProgress(6);
        state.setErrorMessage(null);
        articleMemoryService.recordTaskCompleted(taskId, state);
        articleService.saveTaskSnapshot(state, ArticleStatusEnum.COMPLETED,
                ArticlePhaseEnum.CONTENT_GENERATING, null);
        articleWorkflowEventService.publishSystemEvent(taskId, state, SseMessageTypeEnum.ALL_COMPLETE,
                ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 6,
                payload("taskId", taskId));
        articleWorkflowEventService.publishNodeInfo(taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(),
                "node_replay", "Replay finished from node " + node);
        articleWorkflowEventService.complete(taskId);
    }

    private void handleWorkflowEvent(String taskId, ArticleState state, com.yupi.template.model.dto.article.ArticleWorkflowEvent event) {
        articleWorkflowEventService.publishWorkflowEvent(taskId, state, event);
        if (event == null || event.getType() == null) {
            return;
        }
        switch (event.getType()) {
            case AGENT1_COMPLETE -> articleMemoryService.recordNodeSnapshot(
                    taskId, ArticlePhaseEnum.TITLE_GENERATING.getValue(), "agent1_generate_titles", "SUCCESS", state);
            case AGENT2_COMPLETE -> articleMemoryService.recordNodeSnapshot(
                    taskId, ArticlePhaseEnum.OUTLINE_GENERATING.getValue(), "agent2_generate_outline", "SUCCESS", state);
            case AGENT3_COMPLETE -> articleMemoryService.recordNodeSnapshot(
                    taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(), "agent3_generate_content", "SUCCESS", state);
            case AGENT3_REVIEW_COMPLETE -> articleMemoryService.recordNodeSnapshot(
                    taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(), "agent3_review_content", "SUCCESS", state);
            case AGENT4_COMPLETE -> articleMemoryService.recordNodeSnapshot(
                    taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(), "agent4_analyze_image_requirements", "SUCCESS", state);
            case AGENT5_COMPLETE -> articleMemoryService.recordNodeSnapshot(
                    taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(), "agent5_generate_images", "SUCCESS", state);
            case MERGE_COMPLETE -> articleMemoryService.recordNodeSnapshot(
                    taskId, ArticlePhaseEnum.CONTENT_GENERATING.getValue(), "agent6_merge_content", "SUCCESS", state);
            default -> {
            }
        }
    }

    private Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(key, value);
        return payload;
    }

    private ArticleWorkflowEvent toWorkflowEvent(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return null;
        }
        String agent2Prefix = SseMessageTypeEnum.AGENT2_STREAMING.getStreamingPrefix();
        String agent3Prefix = SseMessageTypeEnum.AGENT3_STREAMING.getStreamingPrefix();
        String imageCompletePrefix = SseMessageTypeEnum.IMAGE_COMPLETE.getStreamingPrefix();

        if (rawMessage.startsWith(agent2Prefix)) {
            return ArticleWorkflowEvent.streaming(SseMessageTypeEnum.AGENT2_STREAMING,
                    rawMessage.substring(agent2Prefix.length()));
        }
        if (rawMessage.startsWith(agent3Prefix)) {
            return ArticleWorkflowEvent.streaming(SseMessageTypeEnum.AGENT3_STREAMING,
                    rawMessage.substring(agent3Prefix.length()));
        }
        if (rawMessage.startsWith(imageCompletePrefix)) {
            ArticleState.ImageResult image = GsonUtils.fromJson(
                    rawMessage.substring(imageCompletePrefix.length()),
                    ArticleState.ImageResult.class
            );
            return ArticleWorkflowEvent.image(image);
        }
        SseMessageTypeEnum type = SseMessageTypeEnum.fromValue(rawMessage);
        return type == null ? null : ArticleWorkflowEvent.simple(type);
    }

    private NodeExecutionMetadata buildReplayMetadata(String node) {
        return switch (node) {
            case "agent3_generate_content" -> agentPromptSupport.toMetadata(
                    agentPromptSupport.resolveProfile("content-generator", "agent3_content", true, false)
            );
            case "agent3_review_content" -> agentPromptSupport.toMetadata(
                    agentPromptSupport.resolveProfile("content-reviewer", "agent3_content_review", false, true)
            );
            case "agent4_analyze_image_requirements" -> agentPromptSupport.toMetadata(
                    agentPromptSupport.resolveProfile("image-analyzer", "agent4_image", false, true)
            );
            default -> null;
        };
    }

    private void resetStateForReplay(ArticleState state, String node) {
        switch (node) {
            case "agent3_generate_content" -> {
                state.setContent(null);
                state.setContentReview(null);
                state.setEnabledImageMethods(null);
                state.setImageRequirements(null);
                state.setImages(new ArrayList<>());
                state.setImageFallbackRecords(new ArrayList<>());
                state.setFullContent(null);
            }
            case "agent3_review_content" -> {
                state.setContentReview(null);
                state.setEnabledImageMethods(null);
                state.setImageRequirements(null);
                state.setImages(new ArrayList<>());
                state.setImageFallbackRecords(new ArrayList<>());
                state.setFullContent(null);
            }
            case "agent4_analyze_image_requirements" -> {
                state.setEnabledImageMethods(null);
                state.setImageRequirements(null);
                state.setImages(new ArrayList<>());
                state.setImageFallbackRecords(new ArrayList<>());
                state.setFullContent(null);
            }
            case "agent5_generate_images" -> {
                state.setImages(new ArrayList<>());
                state.setImageFallbackRecords(new ArrayList<>());
                state.setFullContent(null);
            }
            case "agent6_merge_content" -> state.setFullContent(null);
            default -> {
            }
        }
    }

    private boolean isContentReplayNode(String node) {
        return "agent3_generate_content".equals(node)
                || "agent3_review_content".equals(node)
                || "agent4_analyze_image_requirements".equals(node)
                || "agent5_generate_images".equals(node)
                || "agent6_merge_content".equals(node);
    }

    private int resolveReplayProgress(String node) {
        return switch (node) {
            case "agent3_review_content" -> 3;
            case "agent4_analyze_image_requirements" -> 4;
            case "agent5_generate_images" -> 5;
            case "agent6_merge_content" -> 6;
            default -> 3;
        };
    }

    private void applyImageStrategyRouter(ArticleState state) {
        if (state == null) {
            return;
        }
        NodeExecutionMetadata metadata = buildImageRouterMetadata();
        ImageStrategyDecisionVO decision = imageStrategyRouterService.route(state, metadata);
        if (decision == null || decision.getPreferredMethods() == null || decision.getPreferredMethods().isEmpty()) {
            return;
        }
        state.setEnabledImageMethods(decision.getPreferredMethods());
        articleMemoryService.recordImageStrategyDecision(state.getTaskId(), state, decision);
        articleMemoryService.recordNodeSnapshot(state.getTaskId(),
                ArticlePhaseEnum.CONTENT_GENERATING.getValue(), "image_strategy_router", "SUCCESS", state);
        articleWorkflowEventService.publishNodeInfo(state.getTaskId(),
                ArticlePhaseEnum.CONTENT_GENERATING.getValue(),
                "image_strategy_router",
                "图片策略已路由：source=" + decision.getSource() + ", methods=" + decision.getPreferredMethods());
    }

    private NodeExecutionMetadata buildImageRouterMetadata() {
        return NodeExecutionMetadata.builder()
                .promptKey("image_strategy_router")
                .promptVersion("rule-v1")
                .model("rule-engine")
                .temperature(0D)
                .maxTokens(0)
                .topP(0D)
                .build();
    }

    private NodeExecutionMetadata buildImageGenerationMetadata() {
        return NodeExecutionMetadata.builder()
                .promptKey("image_fallback_router")
                .promptVersion("rule-v1")
                .model("tool-router")
                .temperature(0D)
                .maxTokens(0)
                .topP(0D)
                .build();
    }
}
