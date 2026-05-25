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

    private MemorySummaryVO outlineSummary;

    private MemorySummaryVO contentSummary;

    private MemoryContentReviewVO contentReview;

    private MemoryImageStrategyVO imageStrategy;

    private List<MemorySignalVO> qualitySignals;

    private List<MemoryActionVO> manualActions;

    private List<NodeSnapshotVO> nodeSnapshots;

    private Long updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemorySummaryVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String text;

        private Integer sourceCount;

        private List<String> highlights;

        private String sourceType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryImageStrategyVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private List<String> methods;

        private Boolean needImages;

        private String decisionReason;

        private String decisionSource;

        private Integer requirementCount;

        private Integer generatedCount;

        private List<String> sources;

        private Integer fallbackCount;

        private String fallbackSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryContentReviewVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Boolean needsRevision;

        private Boolean revised;

        private Integer issueCount;

        private String summary;

        private List<String> issues;

        private List<String> qualitySignals;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemorySignalVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String code;

        private String label;

        private String detail;

        private String phase;

        private String node;

        private Long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryActionVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String type;

        private String label;

        private String detail;

        private String phase;

        private String node;

        private Long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeSnapshotVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String node;

        private String phase;

        private String label;

        private String status;

        private String summary;

        private String detail;

        private List<String> highlights;

        private Long timestamp;
    }
}
