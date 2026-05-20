package com.yupi.template.model.dto.article;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Retry a failed workflow node.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Data
public class ArticleRetryNodeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private String node;
}
