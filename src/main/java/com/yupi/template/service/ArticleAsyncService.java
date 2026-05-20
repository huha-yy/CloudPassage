package com.yupi.template.service;

import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.enums.ArticlePhaseEnum;
import com.yupi.template.model.enums.ArticleStatusEnum;
import com.yupi.template.model.enums.SseMessageTypeEnum;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
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
    private ArticleGenerationWorkflowService articleGenerationWorkflowService;

    @Resource
    private ArticleWorkflowStateFactory articleWorkflowStateFactory;

    @Resource
    private ArticleService articleService;

    @Resource
    private ArticleWorkflowEventService articleWorkflowEventService;

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
                    event -> articleWorkflowEventService.publishWorkflowEvent(taskId, state, event));

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
                    event -> articleWorkflowEventService.publishWorkflowEvent(taskId, state, event));

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
                    event -> articleWorkflowEventService.publishWorkflowEvent(taskId, state, event));

            articleService.saveArticleContent(taskId, state);
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.COMPLETED, null);
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

    private void handlePhaseError(String taskId, ArticleState state, Exception e) {
        log.error("Async workflow failed, taskId={}", taskId, e);
        articleWorkflowEventService.publishNodeInfo(taskId, state.getPhase(), "workflow_error",
                "Async workflow error captured: " + e.getClass().getSimpleName());
        articleService.updateArticleStatus(taskId, ArticleStatusEnum.FAILED, e.getMessage());
        state.setErrorMessage(e.getMessage());
        ArticlePhaseEnum phase = ArticlePhaseEnum.getByValue(state.getPhase());
        articleService.saveTaskSnapshot(state, ArticleStatusEnum.FAILED,
                phase == null ? ArticlePhaseEnum.PENDING : phase,
                e.getMessage());
        articleWorkflowEventService.publishSystemEvent(taskId, state, SseMessageTypeEnum.ERROR,
                state.getPhase(), state.getProgress(), payload("message", e.getMessage()));
        articleWorkflowEventService.complete(taskId);
    }

    private Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(key, value);
        return payload;
    }
}
