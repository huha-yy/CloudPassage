package com.yupi.template.service;

import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.enums.ArticlePhaseEnum;
import com.yupi.template.model.enums.ArticleStatusEnum;
import com.yupi.template.model.vo.ArticleSseMessageVO;
import com.yupi.template.model.vo.ArticleTaskSnapshotVO;

/**
 * Persist and restore recoverable workflow snapshots.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
public interface ArticleTaskSnapshotService {

    void saveSnapshot(ArticleState state, ArticleStatusEnum status, ArticlePhaseEnum phase, String errorMessage);

    void applyEvent(String taskId, ArticleSseMessageVO event, ArticleState state);

    ArticleTaskSnapshotVO getSnapshot(String taskId, Article article);

    void clearSnapshot(String taskId);
}
