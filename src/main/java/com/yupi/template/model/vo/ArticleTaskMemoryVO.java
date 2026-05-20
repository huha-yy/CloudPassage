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
 * Task-level memory for a single article creation run.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleTaskMemoryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private Long userId;

    private String topic;

    private String style;

    private String userDescription;

    private String currentPhase;

    private String lastSuccessNode;

    private String lastFailedNode;

    private Integer retryCount;

    private String recentErrorMessage;

    private ArticleState.TitleResult selectedTitle;

    private String outlineSummary;

    private String contentSummary;

    private String imageStrategy;

    private List<String> qualitySignals;

    private List<String> manualActions;

    private Long updatedAt;
}
