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

    private MemoryImageStrategyVO imageStrategy;

    private List<MemorySignalVO> qualitySignals;

    private List<MemoryActionVO> manualActions;

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

        private Integer requirementCount;

        private Integer generatedCount;

        private List<String> sources;
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
}
