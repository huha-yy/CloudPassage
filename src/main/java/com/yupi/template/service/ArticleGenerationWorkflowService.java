package com.yupi.template.service;

import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.dto.article.ArticleWorkflowEvent;

import java.util.function.Consumer;

/**
 * Unified article generation workflow entry.
 */
public interface ArticleGenerationWorkflowService {

    void generateTitles(ArticleState state, Consumer<ArticleWorkflowEvent> eventConsumer);

    void generateOutline(ArticleState state, Consumer<ArticleWorkflowEvent> eventConsumer);

    void generateContent(ArticleState state, Consumer<ArticleWorkflowEvent> eventConsumer);

    String getWorkflowMode();

    void emitNodeStart(String taskId, String phase, String node);

    void emitNodeSuccess(String taskId, String phase, String node);

    void emitNodeFailure(String taskId, String phase, String node, Exception exception);
}
