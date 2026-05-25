package com.yupi.template.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Standardized SSE payload for article generation events.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleSseMessageVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Task id for the current article creation session.
     */
    private String taskId;

    /**
     * Event type, aligned with {@code SseMessageTypeEnum}.
     */
    private String type;

    /**
     * Current article phase when the event is emitted.
     */
    private String phase;

    /**
     * Progress step for the creation timeline.
     */
    private Integer progress;

    /**
     * Event timestamp in milliseconds.
     */
    private Long timestamp;

    /**
     * Event payload. The concrete shape depends on {@code type}.
     */
    private Object payload;
}
