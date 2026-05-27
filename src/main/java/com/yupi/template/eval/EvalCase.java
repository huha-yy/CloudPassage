package com.yupi.template.eval;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Single evaluation case definition loaded from eval/cases/*.json.
 */
@Data
public class EvalCase implements Serializable {

    private static final long serialVersionUID = 1L;

    private String caseId;

    private String topic;

    private String style;

    private String userDescription;

    private List<String> enabledImageMethods;

    private List<String> tags;

    private Map<String, Object> expectations;

    private String notes;
}
