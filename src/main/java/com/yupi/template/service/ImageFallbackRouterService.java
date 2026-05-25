package com.yupi.template.service;

import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.vo.ImageFallbackDecisionVO;

import java.util.List;

/**
 * Routes fallback image methods after a primary image method fails.
 */
public interface ImageFallbackRouterService {

    ImageFallbackDecisionVO route(ArticleState.ImageRequirement requirement, List<String> enabledMethods);
}
