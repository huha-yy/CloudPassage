package com.yupi.template.service;

import com.google.gson.reflect.TypeToken;
import com.yupi.template.agent.ArticleAgentOrchestrator;
import com.yupi.template.agent.config.AgentConfig;
import com.yupi.template.manager.SseEmitterManager;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.enums.ArticlePhaseEnum;
import com.yupi.template.model.enums.ArticleStatusEnum;
import com.yupi.template.model.enums.SseMessageTypeEnum;
import com.yupi.template.model.vo.ArticleSseMessageVO;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Async article workflow service.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Service
@Slf4j
public class ArticleAsyncService {

    @Resource
    private ArticleAgentService articleAgentService;

    @Resource
    private ArticleAgentOrchestrator articleAgentOrchestrator;

    @Resource
    private AgentConfig agentConfig;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Resource
    private ArticleService articleService;

    @Resource
    private ArticleTaskSnapshotService articleTaskSnapshotService;

    @Async("articleExecutor")
    public void executePhase1(String taskId, String topic, String style) {
        boolean useOrchestrator = agentConfig.isOrchestratorEnabled();
        log.info("Phase 1 started, taskId={}, orchestrator={}", taskId, useOrchestrator);

        ArticleState state = new ArticleState();
        state.setTaskId(taskId);
        state.setTopic(topic);
        state.setStyle(style);
        state.setPhase(ArticlePhaseEnum.TITLE_GENERATING.getValue());
        state.setProgress(1);

        try {
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.PROCESSING, null);
            articleService.updatePhase(taskId, ArticlePhaseEnum.TITLE_GENERATING);
            articleTaskSnapshotService.saveSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.TITLE_GENERATING, null);

            if (useOrchestrator) {
                articleAgentOrchestrator.executePhase1_GenerateTitles(state, message -> handleAgentMessage(taskId, message, state));
            } else {
                articleAgentService.executePhase1_GenerateTitles(state, message -> handleAgentMessage(taskId, message, state));
            }

            articleService.saveTitleOptions(taskId, state.getTitleOptions());
            articleService.updatePhase(taskId, ArticlePhaseEnum.TITLE_SELECTING);
            state.setPhase(ArticlePhaseEnum.TITLE_SELECTING.getValue());
            state.setProgress(1);
            articleTaskSnapshotService.saveSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.TITLE_SELECTING, null);
            sendEvent(taskId, state, SseMessageTypeEnum.TITLES_GENERATED,
                    ArticlePhaseEnum.TITLE_SELECTING.getValue(), 1,
                    payload("titleOptions", state.getTitleOptions()));
            log.info("Phase 1 completed, taskId={}", taskId);
        } catch (Exception e) {
            handlePhaseError(taskId, state, e);
        }
    }

    @Async("articleExecutor")
    public void executePhase2(String taskId) {
        boolean useOrchestrator = agentConfig.isOrchestratorEnabled();
        log.info("Phase 2 started, taskId={}, orchestrator={}", taskId, useOrchestrator);

        ArticleState state = buildStateFromArticle(taskId);
        state.setPhase(ArticlePhaseEnum.OUTLINE_GENERATING.getValue());
        state.setProgress(2);

        try {
            articleTaskSnapshotService.saveSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.OUTLINE_GENERATING, null);

            if (useOrchestrator) {
                articleAgentOrchestrator.executePhase2_GenerateOutline(state, message -> handleAgentMessage(taskId, message, state));
            } else {
                articleAgentService.executePhase2_GenerateOutline(state, message -> handleAgentMessage(taskId, message, state));
            }

            Article articleToUpdate = articleService.getByTaskId(taskId);
            articleToUpdate.setOutline(GsonUtils.toJson(state.getOutline().getSections()));
            articleService.updateById(articleToUpdate);
            articleService.updatePhase(taskId, ArticlePhaseEnum.OUTLINE_EDITING);
            state.setPhase(ArticlePhaseEnum.OUTLINE_EDITING.getValue());
            state.setProgress(2);
            state.setOutlineRaw(GsonUtils.toJson(Map.of("sections", state.getOutline().getSections())));
            articleTaskSnapshotService.saveSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.OUTLINE_EDITING, null);
            sendEvent(taskId, state, SseMessageTypeEnum.OUTLINE_GENERATED,
                    ArticlePhaseEnum.OUTLINE_EDITING.getValue(), 2,
                    payload("outline", state.getOutline().getSections()));
            log.info("Phase 2 completed, taskId={}", taskId);
        } catch (Exception e) {
            handlePhaseError(taskId, state, e);
        }
    }

    @Async("articleExecutor")
    public void executePhase3(String taskId) {
        boolean useOrchestrator = agentConfig.isOrchestratorEnabled();
        log.info("Phase 3 started, taskId={}, orchestrator={}", taskId, useOrchestrator);

        ArticleState state = buildStateFromArticle(taskId);
        state.setPhase(ArticlePhaseEnum.CONTENT_GENERATING.getValue());
        state.setProgress(3);

        try {
            articleTaskSnapshotService.saveSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.CONTENT_GENERATING, null);

            if (useOrchestrator) {
                articleAgentOrchestrator.executePhase3_GenerateContent(state, message -> handleAgentMessage(taskId, message, state));
            } else {
                articleAgentService.executePhase3_GenerateContent(state, message -> handleAgentMessage(taskId, message, state));
            }

            articleService.saveArticleContent(taskId, state);
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.COMPLETED, null);
            state.setProgress(6);
            articleTaskSnapshotService.saveSnapshot(state, ArticleStatusEnum.COMPLETED,
                    ArticlePhaseEnum.CONTENT_GENERATING, null);
            sendEvent(taskId, state, SseMessageTypeEnum.ALL_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 6,
                    payload("taskId", taskId));
            sseEmitterManager.complete(taskId);
            log.info("Phase 3 completed, taskId={}", taskId);
        } catch (Exception e) {
            handlePhaseError(taskId, state, e);
        }
    }

    private ArticleState buildStateFromArticle(String taskId) {
        Article article = articleService.getByTaskId(taskId);
        if (article == null) {
            throw new RuntimeException("Article not found");
        }
        ArticleState state = new ArticleState();
        state.setTaskId(taskId);
        state.setTopic(article.getTopic());
        state.setStyle(article.getStyle());
        state.setUserDescription(article.getUserDescription());
        state.setErrorMessage(article.getErrorMessage());

        if (article.getEnabledImageMethods() != null) {
            List<String> enabledMethods = GsonUtils.fromJson(article.getEnabledImageMethods(), new TypeToken<List<String>>() {
            });
            state.setEnabledImageMethods(enabledMethods);
        }
        if (article.getMainTitle() != null || article.getSubTitle() != null) {
            ArticleState.TitleResult title = new ArticleState.TitleResult();
            title.setMainTitle(article.getMainTitle());
            title.setSubTitle(article.getSubTitle());
            state.setTitle(title);
        }
        if (article.getTitleOptions() != null) {
            state.setTitleOptions(GsonUtils.fromJson(article.getTitleOptions(), new TypeToken<List<ArticleState.TitleOption>>() {
            }));
        }
        if (article.getOutline() != null) {
            List<ArticleState.OutlineSection> sections = GsonUtils.fromJson(article.getOutline(),
                    new TypeToken<List<ArticleState.OutlineSection>>() {
                    });
            ArticleState.OutlineResult outline = new ArticleState.OutlineResult();
            outline.setSections(sections);
            state.setOutline(outline);
            state.setOutlineRaw(GsonUtils.toJson(Map.of("sections", sections)));
        }
        state.setContent(article.getContent());
        state.setFullContent(article.getFullContent());
        if (article.getImages() != null) {
            state.setImages(GsonUtils.fromJson(article.getImages(), new TypeToken<List<ArticleState.ImageResult>>() {
            }));
        }
        return state;
    }

    private void handleAgentMessage(String taskId, String message, ArticleState state) {
        ArticleSseMessageVO event = buildMessage(taskId, message, state);
        if (event == null) {
            return;
        }
        state.setPhase(event.getPhase());
        state.setProgress(event.getProgress());
        articleTaskSnapshotService.applyEvent(taskId, event, state);
        sseEmitterManager.send(taskId, GsonUtils.toJson(event));
    }

    private ArticleSseMessageVO buildMessage(String taskId, String message, ArticleState state) {
        String agent2Prefix = SseMessageTypeEnum.AGENT2_STREAMING.getStreamingPrefix();
        String agent3Prefix = SseMessageTypeEnum.AGENT3_STREAMING.getStreamingPrefix();
        String imageCompletePrefix = SseMessageTypeEnum.IMAGE_COMPLETE.getStreamingPrefix();

        if (message.startsWith(agent2Prefix)) {
            String chunk = message.substring(agent2Prefix.length());
            state.setPhase(ArticlePhaseEnum.OUTLINE_GENERATING.getValue());
            state.setProgress(2);
            state.setOutlineRaw(defaultString(state.getOutlineRaw()) + chunk);
            return createEvent(taskId, SseMessageTypeEnum.AGENT2_STREAMING,
                    ArticlePhaseEnum.OUTLINE_GENERATING.getValue(), 2,
                    payload("content", chunk));
        }
        if (message.startsWith(agent3Prefix)) {
            String chunk = message.substring(agent3Prefix.length());
            state.setPhase(ArticlePhaseEnum.CONTENT_GENERATING.getValue());
            state.setProgress(3);
            state.setContent(defaultString(state.getContent()) + chunk);
            return createEvent(taskId, SseMessageTypeEnum.AGENT3_STREAMING,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 3,
                    payload("content", chunk));
        }
        if (message.startsWith(imageCompletePrefix)) {
            String imageJson = message.substring(imageCompletePrefix.length());
            ArticleState.ImageResult image = GsonUtils.fromJson(imageJson, ArticleState.ImageResult.class);
            if (state.getImages() == null) {
                state.setImages(new java.util.ArrayList<>());
            }
            state.getImages().add(image);
            return createEvent(taskId, SseMessageTypeEnum.IMAGE_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 5,
                    payload("image", image));
        }
        return switch (message) {
            case "AGENT1_COMPLETE" -> createEvent(taskId, SseMessageTypeEnum.AGENT1_COMPLETE,
                    ArticlePhaseEnum.TITLE_GENERATING.getValue(), 1,
                    payload("titleOptions", state.getTitleOptions()));
            case "AGENT2_COMPLETE" -> createEvent(taskId, SseMessageTypeEnum.AGENT2_COMPLETE,
                    ArticlePhaseEnum.OUTLINE_GENERATING.getValue(), 2,
                    payload("outline", state.getOutline() == null ? null : state.getOutline().getSections()));
            case "AGENT3_COMPLETE" -> createEvent(taskId, SseMessageTypeEnum.AGENT3_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 3,
                    new HashMap<>());
            case "AGENT4_COMPLETE" -> createEvent(taskId, SseMessageTypeEnum.AGENT4_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 4,
                    payload("imageRequirements", state.getImageRequirements()));
            case "AGENT5_COMPLETE" -> createEvent(taskId, SseMessageTypeEnum.AGENT5_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 5,
                    payload("images", state.getImages()));
            case "MERGE_COMPLETE" -> createEvent(taskId, SseMessageTypeEnum.MERGE_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 6,
                    payload("fullContent", state.getFullContent()));
            default -> null;
        };
    }

    private void sendEvent(String taskId, ArticleState state, SseMessageTypeEnum type,
                           String phase, Integer progress, Map<String, Object> payload) {
        ArticleSseMessageVO event = createEvent(taskId, type, phase, progress, payload);
        state.setPhase(phase);
        state.setProgress(progress);
        articleTaskSnapshotService.applyEvent(taskId, event, state);
        sseEmitterManager.send(taskId, GsonUtils.toJson(event));
    }

    private ArticleSseMessageVO createEvent(String taskId, SseMessageTypeEnum type,
                                            String phase, Integer progress, Map<String, Object> payload) {
        return ArticleSseMessageVO.builder()
                .taskId(taskId)
                .type(type.getValue())
                .phase(phase)
                .progress(progress)
                .timestamp(System.currentTimeMillis())
                .payload(payload)
                .build();
    }

    private void handlePhaseError(String taskId, ArticleState state, Exception e) {
        log.error("Async workflow failed, taskId={}", taskId, e);
        articleService.updateArticleStatus(taskId, ArticleStatusEnum.FAILED, e.getMessage());
        state.setErrorMessage(e.getMessage());
        ArticlePhaseEnum phase = ArticlePhaseEnum.getByValue(state.getPhase());
        articleTaskSnapshotService.saveSnapshot(state, ArticleStatusEnum.FAILED,
                phase == null ? ArticlePhaseEnum.PENDING : phase,
                e.getMessage());
        sendEvent(taskId, state, SseMessageTypeEnum.ERROR,
                state.getPhase(), state.getProgress(), payload("message", e.getMessage()));
        sseEmitterManager.complete(taskId);
    }

    private Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(key, value);
        return payload;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
