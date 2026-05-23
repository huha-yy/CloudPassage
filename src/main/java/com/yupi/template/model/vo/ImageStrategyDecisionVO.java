package com.yupi.template.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Router decision for image strategy selection.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageStrategyDecisionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Boolean needImages;

    private List<String> preferredMethods;

    private String source;

    private String reason;

    private Boolean diagramPreferred;

    private Boolean realisticPhotoPreferred;
}
