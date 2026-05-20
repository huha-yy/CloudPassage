package com.yupi.template.service.impl;

import com.google.gson.reflect.TypeToken;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.service.ArticleService;
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
        if (article.getMainTitle() != null || article.getSubTitle() != null) {
            ArticleState.TitleResult title = new ArticleState.TitleResult();
            title.setMainTitle(article.getMainTitle());
            title.setSubTitle(article.getSubTitle());
            state.setTitle(title);
        }
        if (article.getTitleOptions() != null) {
            state.setTitleOptions(GsonUtils.fromJson(article.getTitleOptions(),
                    new TypeToken<List<ArticleState.TitleOption>>() {
                    }));
        }
        if (article.getOutline() != null) {
            List<ArticleState.OutlineSection> sections = GsonUtils.fromJson(article.getOutline(),
                    new TypeToken<List<ArticleState.OutlineSection>>() {
                    });
            ArticleState.OutlineResult outline = new ArticleState.OutlineResult();
            outline.setSections(sections);
            state.setOutline(outline);
            state.setOutlineRaw(GsonUtils.toJson(Map.of("sections", sections)));
        }
        state.setContent(article.getContent());
        state.setFullContent(article.getFullContent());
        if (article.getImages() != null) {
            state.setImages(GsonUtils.fromJson(article.getImages(),
                    new TypeToken<List<ArticleState.ImageResult>>() {
                    }));
        }
        return state;
    }
}
