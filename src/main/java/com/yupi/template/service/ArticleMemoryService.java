package com.yupi.template.service;

import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.vo.ArticleMemoryContextVO;
import com.yupi.template.model.vo.ArticleTaskMemoryVO;
import com.yupi.template.model.vo.ImageFallbackDecisionVO;
import com.yupi.template.model.vo.ImageStrategyDecisionVO;
import com.yupi.template.model.vo.UserCreationPreferenceVO;

import java.util.List;

/**
 * Task-level and user-level memory gateway for article creation.
 */
public interface ArticleMemoryService {

    void initializeTaskMemory(Article article);

    void recordTitleConfirmed(Article article);

    void recordOutlineConfirmed(String taskId, List<ArticleState.OutlineSection> outlineSections);

    void recordTaskCompleted(String taskId, ArticleState state);

    void recordTaskFailed(String taskId, String phase, String failedNode, String errorMessage);

    void recordTaskResume(String taskId, String phase, String actionType, String node);

    void recordNodeSnapshot(String taskId, String phase, String node, String status, ArticleState state);

    void recordImageStrategyDecision(String taskId, ArticleState state, ImageStrategyDecisionVO decision);

    void recordImageFallbackDecision(String taskId, ArticleState state, List<ImageFallbackDecisionVO> decisions);

    ArticleTaskMemoryVO getTaskMemory(String taskId);

    UserCreationPreferenceVO getUserPreference(Long userId);

    ArticleMemoryContextVO buildCreationMemoryContext(String taskId, Long userId);
}
