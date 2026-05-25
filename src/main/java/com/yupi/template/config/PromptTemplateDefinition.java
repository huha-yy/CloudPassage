package com.yupi.template.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Configurable prompt template definition.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptTemplateDefinition implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String key;

    private String version;

    private String content;

    private String description;
}
