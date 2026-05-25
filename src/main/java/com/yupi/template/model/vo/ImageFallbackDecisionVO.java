package com.yupi.template.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Image fallback decision for a single generation attempt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageFallbackDecisionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String requestedMethod;

    private String finalMethod;

    private Boolean fallbackApplied;

    private String fallbackReason;

    private List<String> attemptedMethods;
}
