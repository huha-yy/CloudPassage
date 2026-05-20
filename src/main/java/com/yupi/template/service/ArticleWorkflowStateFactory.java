package com.yupi.template.service;

import com.yupi.template.model.dto.article.ArticleState;

/**
 * Rebuilds workflow runtime state from persisted article data.
 */
public interface ArticleWorkflowStateFactory {

    ArticleState buildFromArticle(String taskId);
}
