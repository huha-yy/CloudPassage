package com.yupi.template.service.impl;

import com.google.gson.reflect.TypeToken;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.vo.ArticleTaskSnapshotVO;
import com.yupi.template.service.ArticleService;
import com.yupi.template.service.ArticleTaskSnapshotService;
import com.yupi.template.service.ArticleWorkflowStateFactory;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Restores workflow runtime state from the persisted article aggregate.
 */
@Service
public class ArticleWorkflowStateFactoryImpl implements ArticleWorkflowStateFactory {

    @Resource
    private ArticleService articleService;

    @Resource
    private ArticleTaskSnapshotService articleTaskSnapshotService;

    @Override
    public ArticleState buildFromArticle(String taskId) {
        Article article = articleService.getByTaskId(taskId);
        if (article == null) {
            throw new RuntimeException("Article not found");
        }

        ArticleState state = new ArticleState();
        state.setTaskId(taskId);
        state.setTopic(article.getTopic());
        state.setStyle(article.getStyle());
        state.setUserDescription(article.getUserDescription());
        state.setErrorMessage(article.getErrorMessage());

        if (article.getEnabledImageMethods() != null) {
            List<String> enabledMethods = GsonUtils.fromJson(article.getEnabledImageMethods(),
                    new TypeToken<List<String>>() {
                    });
            state.setEnabledImageMethods(enabledMethods);
        }

        ArticleTaskSnapshotVO snapshot = articleTaskSnapshotService.getSnapshot(taskId, article);
        if (snapshot != null) {
            overlaySnapshot(state, snapshot);
        }

        if (state.getTitle() == null && (article.getMainTitle() != null || article.getSubTitle() != null)) {
            ArticleState.TitleResult title = new ArticleState.TitleResult();
            title.setMainTitle(article.getMainTitle());
            title.setSubTitle(article.getSubTitle());
            state.setTitle(title);
        }
        return state;
    }

    private void overlaySnapshot(ArticleState state, ArticleTaskSnapshotVO snapshot) {
        if (snapshot == null) {
            return;
        }
        if (snapshot.getTopic() != null) {
            state.setTopic(snapshot.getTopic());
        }
        if (snapshot.getStyle() != null) {
            state.setStyle(snapshot.getStyle());
        }
        if (snapshot.getUserDescription() != null) {
            state.setUserDescription(snapshot.getUserDescription());
        }
        if (snapshot.getPhase() != null) {
            state.setPhase(snapshot.getPhase());
        }
        if (snapshot.getProgress() != null) {
            state.setProgress(snapshot.getProgress());
        }
        if (snapshot.getErrorMessage() != null) {
            state.setErrorMessage(snapshot.getErrorMessage());
        }
        if (snapshot.getTitleOptions() != null) {
            state.setTitleOptions(snapshot.getTitleOptions());
        }
        if (snapshot.getTitle() != null) {
            state.setTitle(snapshot.getTitle());
        }
        if (snapshot.getOutline() != null) {
            ArticleState.OutlineResult outline = new ArticleState.OutlineResult();
            outline.setSections(snapshot.getOutline());
            state.setOutline(outline);
        }
        if (snapshot.getOutlineRaw() != null) {
            state.setOutlineRaw(snapshot.getOutlineRaw());
        }
        if (snapshot.getContent() != null) {
            state.setContent(snapshot.getContent());
        }
        if (snapshot.getFullContent() != null) {
            state.setFullContent(snapshot.getFullContent());
        }
        if (snapshot.getEnabledImageMethods() != null) {
            state.setEnabledImageMethods(snapshot.getEnabledImageMethods());
        }
        if (snapshot.getImageRequirements() != null) {
            state.setImageRequirements(snapshot.getImageRequirements());
        }
        if (snapshot.getImages() != null) {
            state.setImages(snapshot.getImages());
        }
        if (snapshot.getImageFallbackRecords() != null) {
            state.setImageFallbackRecords(snapshot.getImageFallbackRecords());
        }
        if (snapshot.getContentReview() != null) {
            state.setContentReview(snapshot.getContentReview());
        }
    }
}
