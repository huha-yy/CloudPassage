package com.yupi.template.service.impl;

import com.yupi.template.manager.SseEmitterManager;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.dto.article.ArticleWorkflowEvent;
import com.yupi.template.model.enums.ArticlePhaseEnum;
import com.yupi.template.model.enums.SseMessageTypeEnum;
import com.yupi.template.model.vo.ArticleSseMessageVO;
import com.yupi.template.service.ArticleNodeLogService;
import com.yupi.template.service.ArticleTaskSnapshotService;
import com.yupi.template.service.ArticleWorkflowEventService;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Default workflow event bridge for async article generation.
 */
@Service
public class ArticleWorkflowEventServiceImpl implements ArticleWorkflowEventService {

    @Resource
    private ArticleNodeLogService articleNodeLogService;

    @Resource
    private ArticleTaskSnapshotService articleTaskSnapshotService;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Override
    public void publishWorkflowEvent(String taskId, ArticleState state, ArticleWorkflowEvent workflowEvent) {
        ArticleSseMessageVO event = buildMessage(taskId, workflowEvent, state);
        if (event == null) {
            return;
        }
        publish(taskId, state, event);
    }

    @Override
    public void publishSystemEvent(String taskId, ArticleState state, SseMessageTypeEnum type,
                                   String phase, Integer progress, Map<String, Object> payload) {
        publish(taskId, state, createEvent(taskId, type, phase, progress, payload));
    }

    @Override
    public void publishNodeInfo(String taskId, String phase, String node, String message) {
        articleNodeLogService.info(taskId, phase, node, message);
    }

    @Override
    public void complete(String taskId) {
        sseEmitterManager.complete(taskId);
    }

    private void publish(String taskId, ArticleState state, ArticleSseMessageVO event) {
        state.setPhase(event.getPhase());
        state.setProgress(event.getProgress());
        articleTaskSnapshotService.applyEvent(taskId, event, state);
        sseEmitterManager.send(taskId, GsonUtils.toJson(event));
    }

    private ArticleSseMessageVO buildMessage(String taskId, ArticleWorkflowEvent workflowEvent, ArticleState state) {
        if (workflowEvent == null || workflowEvent.getType() == null) {
            return null;
        }
        if (workflowEvent.getType() == SseMessageTypeEnum.AGENT2_STREAMING) {
            String chunk = defaultString(workflowEvent.getChunk());
            state.setOutlineRaw(defaultString(state.getOutlineRaw()) + chunk);
            return createEvent(taskId, SseMessageTypeEnum.AGENT2_STREAMING,
                    ArticlePhaseEnum.OUTLINE_GENERATING.getValue(), 2,
                    payload("content", chunk));
        }
        if (workflowEvent.getType() == SseMessageTypeEnum.AGENT3_STREAMING) {
            String chunk = defaultString(workflowEvent.getChunk());
            state.setContent(defaultString(state.getContent()) + chunk);
            return createEvent(taskId, SseMessageTypeEnum.AGENT3_STREAMING,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 3,
                    payload("content", chunk));
        }
        if (workflowEvent.getType() == SseMessageTypeEnum.IMAGE_COMPLETE) {
            ArticleState.ImageResult image = workflowEvent.getImage();
            if (state.getImages() == null) {
                state.setImages(new java.util.ArrayList<>());
            }
            state.getImages().add(image);
            return createEvent(taskId, SseMessageTypeEnum.IMAGE_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 5,
                    payload("image", image));
        }
        return switch (workflowEvent.getType()) {
            case AGENT1_COMPLETE -> createEvent(taskId, SseMessageTypeEnum.AGENT1_COMPLETE,
                    ArticlePhaseEnum.TITLE_GENERATING.getValue(), 1,
                    payload("titleOptions", state.getTitleOptions()));
            case AGENT2_COMPLETE -> createEvent(taskId, SseMessageTypeEnum.AGENT2_COMPLETE,
                    ArticlePhaseEnum.OUTLINE_GENERATING.getValue(), 2,
                    payload("outline", state.getOutline() == null ? null : state.getOutline().getSections()));
            case AGENT3_COMPLETE -> createEvent(taskId, SseMessageTypeEnum.AGENT3_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 3,
                    new HashMap<>());
            case AGENT3_REVIEW_COMPLETE -> createEvent(taskId, SseMessageTypeEnum.AGENT3_REVIEW_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 3,
                    payload(
                            "content", state.getContent(),
                            "contentReview", state.getContentReview()
                    ));
            case AGENT4_COMPLETE -> createEvent(taskId, SseMessageTypeEnum.AGENT4_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 4,
                    payload("imageRequirements", state.getImageRequirements()));
            case AGENT5_COMPLETE -> createEvent(taskId, SseMessageTypeEnum.AGENT5_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 5,
                    payload(
                            "images", state.getImages(),
                            "imageFallbackRecords", state.getImageFallbackRecords()
                    ));
            case MERGE_COMPLETE -> createEvent(taskId, SseMessageTypeEnum.MERGE_COMPLETE,
                    ArticlePhaseEnum.CONTENT_GENERATING.getValue(), 6,
                    payload("fullContent", state.getFullContent()));
            default -> null;
        };
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

    private Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(key, value);
        return payload;
    }

    private Map<String, Object> payload(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(key1, value1);
        payload.put(key2, value2);
        return payload;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
