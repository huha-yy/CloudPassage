package com.yupi.template.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Lightweight node-level execution log for workflow observability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeExecutionLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;

    private String phase;

    private String node;

    private String status;

    private String message;

    private Integer elapsedMs;

    private Long timestamp;

    private String promptKey;

    private String promptVersion;

    private String model;

    private Double temperature;

    private Integer maxTokens;

    private Double topP;

    private String decisionSource;

    private String decisionReason;

    private String decisionSummary;

    private String fallbackSource;

    private String fallbackReason;

    private String fallbackSummary;
}
