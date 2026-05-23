package com.yupi.template.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Optional metadata attached to node execution logs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeExecutionMetadata implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String promptKey;

    private String promptVersion;

    private String model;

    private Double temperature;

    private Integer maxTokens;

    private Double topP;
}
