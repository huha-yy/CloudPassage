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
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.PROCESSING, null);
            articleService.updatePhase(taskId, ArticlePhaseEnum.TITLE_GENERATING);
            articleService.saveTaskSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.TITLE_GENERATING, null);

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
            log.info("Phase 1 completed, taskId={}", taskId);
        } catch (Exception e) {
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
            articleService.saveTaskSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.OUTLINE_GENERATING, null);

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
            log.info("Phase 2 completed, taskId={}", taskId);
        } catch (Exception e) {
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
            articleService.saveTaskSnapshot(state, ArticleStatusEnum.PROCESSING,
                    ArticlePhaseEnum.CONTENT_GENERATING, null);

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
            articleWorkflowEventService.complete(taskId);
            log.info("Phase 3 completed, taskId={}", taskId);
        } catch (Exception e) {
            handlePhaseError(taskId, state, e);
        }
    }

    private void handlePhaseError(String taskId, ArticleState state, Exception e) {
        log.error("Async workflow failed, taskId={}", taskId, e);
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
