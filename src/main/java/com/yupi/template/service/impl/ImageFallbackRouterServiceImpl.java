package com.yupi.template.service.impl;

import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.enums.ImageMethodEnum;
import com.yupi.template.model.vo.ImageFallbackDecisionVO;
import com.yupi.template.service.ImageFallbackRouterService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rule-based fallback router for image generation failures.
 */
@Service
public class ImageFallbackRouterServiceImpl implements ImageFallbackRouterService {

    @Override
    public ImageFallbackDecisionVO route(ArticleState.ImageRequirement requirement, List<String> enabledMethods) {
        String requestedMethod = requirement == null ? null : requirement.getImageSource();
        LinkedHashSet<String> attempted = new LinkedHashSet<>();
        addIfValid(attempted, requestedMethod);

        if (isDiagramLike(requestedMethod)) {
            addIfCompatible(attempted, enabledMethods, ImageMethodEnum.MERMAID.getValue());
            addIfCompatible(attempted, enabledMethods, ImageMethodEnum.SVG_DIAGRAM.getValue());
        } else if (isRealisticLike(requestedMethod)) {
            addIfCompatible(attempted, enabledMethods, ImageMethodEnum.NANO_BANANA.getValue());
            addIfCompatible(attempted, enabledMethods, ImageMethodEnum.PEXELS.getValue());
        } else if (ImageMethodEnum.ICONIFY.getValue().equals(requestedMethod)) {
            addIfCompatible(attempted, enabledMethods, ImageMethodEnum.ICONIFY.getValue());
            addIfCompatible(attempted, enabledMethods, ImageMethodEnum.PEXELS.getValue());
        } else if (ImageMethodEnum.EMOJI_PACK.getValue().equals(requestedMethod)) {
            addIfCompatible(attempted, enabledMethods, ImageMethodEnum.EMOJI_PACK.getValue());
            addIfCompatible(attempted, enabledMethods, ImageMethodEnum.PEXELS.getValue());
        } else {
            addIfCompatible(attempted, enabledMethods, ImageMethodEnum.PEXELS.getValue());
            addIfCompatible(attempted, enabledMethods, ImageMethodEnum.ICONIFY.getValue());
        }
        addIfValid(attempted, ImageMethodEnum.getFallbackMethod().getValue());

        List<String> attemptedMethods = new ArrayList<>(attempted);
        String finalMethod = attemptedMethods.isEmpty() ? ImageMethodEnum.getFallbackMethod().getValue() : attemptedMethods.get(attemptedMethods.size() - 1);
        boolean fallbackApplied = attemptedMethods.size() > 1;
        String fallbackReason = resolveReason(requestedMethod, attemptedMethods);

        return ImageFallbackDecisionVO.builder()
                .requestedMethod(requestedMethod)
                .finalMethod(finalMethod)
                .fallbackApplied(fallbackApplied)
                .fallbackReason(fallbackReason)
                .attemptedMethods(attemptedMethods)
                .build();
    }

    private boolean isDiagramLike(String method) {
        return ImageMethodEnum.MERMAID.getValue().equals(method)
                || ImageMethodEnum.SVG_DIAGRAM.getValue().equals(method);
    }

    private boolean isRealisticLike(String method) {
        return ImageMethodEnum.NANO_BANANA.getValue().equals(method)
                || ImageMethodEnum.PEXELS.getValue().equals(method);
    }

    private void addIfCompatible(Set<String> attempted, List<String> enabledMethods, String candidate) {
        if (enabledMethods == null || enabledMethods.isEmpty() || enabledMethods.contains(candidate)) {
            addIfValid(attempted, candidate);
        }
    }

    private void addIfValid(Set<String> attempted, String method) {
        if (method == null || method.isBlank()) {
            return;
        }
        ImageMethodEnum methodEnum = ImageMethodEnum.getByValue(method);
        if (methodEnum != null) {
            attempted.add(methodEnum.getValue());
        }
    }

    private String resolveReason(String requestedMethod, List<String> attemptedMethods) {
        if (requestedMethod == null || requestedMethod.isBlank()) {
            return "missing_requested_method";
        }
        if (attemptedMethods == null || attemptedMethods.size() <= 1) {
            return "single_method_only";
        }
        if (ImageMethodEnum.MERMAID.getValue().equals(requestedMethod)) {
            return "diagram_render_failed";
        }
        if (ImageMethodEnum.SVG_DIAGRAM.getValue().equals(requestedMethod)) {
            return "svg_generation_failed";
        }
        if (ImageMethodEnum.NANO_BANANA.getValue().equals(requestedMethod)) {
            return "ai_image_generation_failed";
        }
        if (ImageMethodEnum.PEXELS.getValue().equals(requestedMethod)) {
            return "stock_image_search_failed";
        }
        if (ImageMethodEnum.ICONIFY.getValue().equals(requestedMethod)) {
            return "icon_search_failed";
        }
        if (ImageMethodEnum.EMOJI_PACK.getValue().equals(requestedMethod)) {
            return "emoji_search_failed";
        }
        return "fallback_route_applied";
    }
}
