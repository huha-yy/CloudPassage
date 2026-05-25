package com.yupi.template.agent.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Resolved prompt and model profile for a workflow node.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AgentProfile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;

    private String promptKey;

    private String promptVersion;

    private String model;

    private Double temperature;

    private Integer maxTokens;

    private Double topP;

    private Boolean structuredOutput;

    private Boolean streaming;
}
