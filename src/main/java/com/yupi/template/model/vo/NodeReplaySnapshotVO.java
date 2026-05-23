package com.yupi.template.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Replay snapshot for a single node execution attempt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeReplaySnapshotVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String snapshotId;

    private String snapshotVersion;

    private String taskId;

    private String phase;

    private String node;

    private String status;

    private String message;

    private Long startedAt;

    private Long finishedAt;

    private Integer elapsedMs;

    private String promptKey;

    private String promptVersion;

    private String model;

    private Double temperature;

    private Integer maxTokens;

    private Double topP;

    private String decisionSource;

    private String decisionReason;

    private String decisionSummary;

    private String inputSummary;

    private String outputSummary;

    private String errorMessage;

    private Integer retryCount;

    private Boolean replayable;
}
