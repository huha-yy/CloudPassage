package com.yupi.template.agent.agents;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.yupi.template.agent.tools.ImageGenerationTool;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图文合成 Agent。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentMergerAgent implements NodeAction {

    public static final String INPUT_CONTENT = "content";
    public static final String INPUT_IMAGES = "images";
    public static final String OUTPUT_FULL_CONTENT = "fullContent";

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String content = state.value(INPUT_CONTENT)
                .map(Object::toString)
                .orElseThrow(() -> new IllegalArgumentException("缺少正文内容参数"));

        @SuppressWarnings("unchecked")
        List<ArticleState.ImageResult> images = state.value(INPUT_IMAGES)
                .map(v -> {
                    if (v instanceof List<?>) {
                        List<?> list = (List<?>) v;
                        if (list.isEmpty()) {
                            return new ArrayList<ArticleState.ImageResult>();
                        }
                        if (list.get(0) instanceof ArticleState.ImageResult) {
                            return (List<ArticleState.ImageResult>) v;
                        }
                        return convertToImageResults(list);
                    }
                    return new ArrayList<ArticleState.ImageResult>();
                })
                .orElse(new ArrayList<>());

        log.info("ContentMergerAgent 开始执行: 正文长度={}, 图片数量={}", content.length(), images.size());
        String fullContent = mergeImagesIntoContent(content, images);
        log.info("ContentMergerAgent 执行完成: 完整内容长度={}", fullContent.length());
        return Map.of(OUTPUT_FULL_CONTENT, fullContent);
    }

    private String mergeImagesIntoContent(String content, List<ArticleState.ImageResult> images) {
        if (images == null || images.isEmpty()) {
            return content;
        }

        String fullContent = content;
        for (ArticleState.ImageResult image : images) {
            String placeholder = image.getPlaceholderId();
            log.info("处理图片: position={}, placeholderId={}, url={}",
                    image.getPosition(), placeholder, image.getUrl());

            if (placeholder == null || placeholder.isEmpty()) {
                if (Integer.valueOf(1).equals(image.getPosition())) {
                    log.debug("position=1 的封面图无需占位符");
                } else {
                    log.warn("图片 position={} 的 placeholderId 为空", image.getPosition());
                }
                continue;
            }

            String description = image.getDescription() != null ? image.getDescription() : "配图";
            String imageMarkdown = "![" + description + "](" + image.getUrl() + ")";

            if (fullContent.contains(placeholder)) {
                fullContent = fullContent.replace(placeholder, imageMarkdown);
                log.info("成功替换占位符: {} -> {}",
                        placeholder,
                        imageMarkdown.substring(0, Math.min(50, imageMarkdown.length())));
            } else {
                log.warn("正文中未找到占位符: {}", placeholder);
            }
        }

        return fullContent;
    }

    private List<ArticleState.ImageResult> convertToImageResults(List<?> list) {
        List<ArticleState.ImageResult> results = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof ArticleState.ImageResult imageResult) {
                results.add(imageResult);
            } else if (item instanceof ImageGenerationTool.ImageGenerationResult genResult) {
                if (genResult.isSuccess()) {
                    ArticleState.ImageResult imageResult = new ArticleState.ImageResult();
                    imageResult.setPosition(genResult.getPosition());
                    imageResult.setUrl(genResult.getUrl());
                    imageResult.setMethod(genResult.getMethod());
                    imageResult.setKeywords(genResult.getKeywords());
                    imageResult.setSectionTitle(genResult.getSectionTitle());
                    imageResult.setDescription(genResult.getDescription());
                    imageResult.setPlaceholderId(genResult.getPlaceholderId());
                    results.add(imageResult);
                }
            } else if (item instanceof Map) {
                String json = GsonUtils.toJson(item);
                ArticleState.ImageResult imageResult = GsonUtils.fromJson(json, ArticleState.ImageResult.class);
                if (imageResult.getUrl() != null) {
                    results.add(imageResult);
                }
            }
        }
        return results;
    }
}
