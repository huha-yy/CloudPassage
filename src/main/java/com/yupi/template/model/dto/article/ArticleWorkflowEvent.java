package com.yupi.template.model.dto.article;

import com.yupi.template.model.enums.SseMessageTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Internal workflow event used to decouple async orchestration from raw string signals.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleWorkflowEvent implements Serializable {

    private SseMessageTypeEnum type;

    /**
     * Streaming chunk for outline/content generation.
     */
    private String chunk;

    /**
     * Single image result emitted during image generation.
     */
    private ArticleState.ImageResult image;

    public static ArticleWorkflowEvent simple(SseMessageTypeEnum type) {
        return ArticleWorkflowEvent.builder()
                .type(type)
                .build();
    }

    public static ArticleWorkflowEvent streaming(SseMessageTypeEnum type, String chunk) {
        return ArticleWorkflowEvent.builder()
                .type(type)
                .chunk(chunk)
                .build();
    }

    public static ArticleWorkflowEvent image(ArticleState.ImageResult image) {
        return ArticleWorkflowEvent.builder()
                .type(SseMessageTypeEnum.IMAGE_COMPLETE)
                .image(image)
                .build();
    }
}
