package com.yupi.template.model.enums;

import lombok.Getter;

/**
 * Workflow node execution status.
 */
@Getter
public enum NodeExecutionStatusEnum {

    RUNNING("RUNNING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    INFO("INFO");

    private final String value;

    NodeExecutionStatusEnum(String value) {
        this.value = value;
    }
}
