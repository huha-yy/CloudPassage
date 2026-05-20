package com.yupi.template.model.dto.article;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Resume or retry an existing article workflow task.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Data
public class ArticleResumeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Recoverable task id.
     */
    private String taskId;
}
