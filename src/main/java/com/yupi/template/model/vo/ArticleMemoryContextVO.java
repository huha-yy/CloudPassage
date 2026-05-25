package com.yupi.template.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Aggregated memory context that can be reused by agent decisions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleMemoryContextVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private Long userId;

    private ArticleTaskMemoryVO currentTaskMemory;

    private UserCreationPreferenceVO userPreference;

    private List<RecalledMemoryCaseVO> recalledSuccessCases;

    private List<RecalledMemoryCaseVO> recalledFailureCases;

    private List<String> preferredImageMethods;

    private List<String> avoidImageMethods;

    private List<String> qualityHints;

    private List<String> failureHints;

    private Long updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecalledMemoryCaseVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String taskId;

        private String status;

        private String topic;

        private String style;

        private String summary;

        private String failedNode;

        private List<String> imageMethods;

        private List<String> qualitySignals;

        private Integer score;

        private Long updatedAt;
    }
}
