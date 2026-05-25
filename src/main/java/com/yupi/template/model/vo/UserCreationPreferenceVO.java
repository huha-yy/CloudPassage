package com.yupi.template.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * User-level creation preference memory.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreationPreferenceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;

    private String preferredStyle;

    private List<String> preferredImageMethods;

    private Map<String, Integer> styleUsage;

    private Map<String, Integer> imageMethodUsage;

    private List<String> recentFailureTags;

    private Integer completedTaskCount;

    private Long updatedAt;
}
