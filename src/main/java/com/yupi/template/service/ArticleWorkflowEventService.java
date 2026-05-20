package com.yupi.template.service;

import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.dto.article.ArticleWorkflowEvent;
import com.yupi.template.model.enums.SseMessageTypeEnum;

import java.util.Map;

/**
 * Handles workflow event translation, snapshot updates, and SSE publishing.
 */
public interface ArticleWorkflowEventService {

    void publishWorkflowEvent(String taskId, ArticleState state, ArticleWorkflowEvent workflowEvent);

    void publishSystemEvent(String taskId, ArticleState state, SseMessageTypeEnum type,
                            String phase, Integer progress, Map<String, Object> payload);

    void publishNodeInfo(String taskId, String phase, String node, String message);

    void complete(String taskId);
}
