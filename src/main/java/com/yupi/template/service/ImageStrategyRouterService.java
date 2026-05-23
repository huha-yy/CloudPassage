package com.yupi.template.service;

import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.vo.ImageStrategyDecisionVO;
import com.yupi.template.model.vo.NodeExecutionMetadata;

/**
 * Lightweight image strategy router.
 */
public interface ImageStrategyRouterService {

    ImageStrategyDecisionVO route(ArticleState state, NodeExecutionMetadata metadata);
}
