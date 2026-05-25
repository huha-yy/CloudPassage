package com.yupi.template.model.vo;

import com.yupi.template.model.dto.article.ArticleState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Recoverable task snapshot for the article creation workflow.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleTaskSnapshotVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private String topic;

    private String style;

    private String userDescription;

    private String status;

    private String phase;

    private Integer progress;

    private String errorMessage;

    private List<ArticleState.TitleOption> titleOptions;

    private ArticleState.TitleResult title;

    private List<ArticleState.OutlineSection> outline;

    private String outlineRaw;

    private String content;

    private String fullContent;

    private List<String> enabledImageMethods;

    private List<ArticleState.ImageRequirement> imageRequirements;

    private List<ArticleState.ImageResult> images;

    private List<ArticleState.ImageFallbackRecord> imageFallbackRecords;

    private ArticleState.ContentReviewResult contentReview;

    private Long updatedAt;
}
