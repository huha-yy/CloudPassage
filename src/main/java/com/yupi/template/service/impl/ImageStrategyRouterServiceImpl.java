package com.yupi.template.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.yupi.template.annotation.AgentExecution;
import com.yupi.template.mapper.ArticleMapper;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.entity.Article;
import com.yupi.template.model.enums.ImageMethodEnum;
import com.yupi.template.model.vo.ArticleMemoryContextVO;
import com.yupi.template.model.vo.ArticleTaskMemoryVO;
import com.yupi.template.model.vo.ImageStrategyDecisionVO;
import com.yupi.template.model.vo.NodeExecutionMetadata;
import com.yupi.template.model.vo.UserCreationPreferenceVO;
import com.yupi.template.service.ArticleMemoryService;
import com.yupi.template.service.ImageStrategyRouterService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Rule-based image strategy router.
 */
@Service
public class ImageStrategyRouterServiceImpl implements ImageStrategyRouterService {

    private static final List<String> DEFAULT_NON_VIP_METHODS = List.of(
            ImageMethodEnum.PEXELS.getValue(),
            ImageMethodEnum.MERMAID.getValue(),
            ImageMethodEnum.ICONIFY.getValue(),
            ImageMethodEnum.EMOJI_PACK.getValue()
    );

    @Resource
    private ArticleMemoryService articleMemoryService;

    @Resource
    private ArticleMapper articleMapper;

    @Override
    @AgentExecution(value = "image_strategy_router", description = "图片策略路由")
    public ImageStrategyDecisionVO route(ArticleState state, NodeExecutionMetadata metadata) {
        if (state == null || isBlank(state.getTaskId())) {
            ImageStrategyDecisionVO fallbackDecision = ImageStrategyDecisionVO.builder()
                    .needImages(Boolean.TRUE)
                    .preferredMethods(DEFAULT_NON_VIP_METHODS)
                    .source("default_policy")
                    .reason("task_missing_fallback")
                    .diagramPreferred(Boolean.FALSE)
                    .realisticPhotoPreferred(Boolean.TRUE)
                    .build();
            applyDecisionMetadata(metadata, fallbackDecision);
            return fallbackDecision;
        }

        Article article = articleMapper.selectOneByQuery(QueryWrapper.create().eq("taskId", state.getTaskId()));
        ArticleTaskMemoryVO memory = articleMemoryService.getTaskMemory(state.getTaskId());
        UserCreationPreferenceVO preference = article != null && article.getUserId() != null
                ? articleMemoryService.getUserPreference(article.getUserId())
                : null;
        ArticleMemoryContextVO memoryContext = articleMemoryService.buildCreationMemoryContext(
                state.getTaskId(), article == null ? null : article.getUserId()
        );

        List<String> availableMethods = sanitizeMethods(state.getEnabledImageMethods());
        String textSignal = buildSignalText(state);
        boolean diagramPreferred = containsAny(textSignal,
                "流程", "架构", "对比", "步骤", "图解", "时序", "关系", "原理", "结构", "框架", "拓扑",
                "flow", "architecture", "diagram", "sequence", "compare", "step");
        boolean realisticPreferred = containsAny(textSignal,
                "案例", "场景", "人物", "产品", "城市", "办公", "旅行", "风景",
                "scene", "product", "people", "office", "city", "travel");

        String source = "state_selection";
        String reason = "reuse_current_state_methods";
        List<String> result = new ArrayList<>(availableMethods);

        if (result.isEmpty()) {
            result = sanitizeMethods(preference == null ? null : preference.getPreferredImageMethods());
            if (!result.isEmpty()) {
                source = "user_preference";
                reason = "apply_user_preferred_methods";
            }
        }

        if (result.isEmpty() && memory != null && memory.getImageStrategy() != null) {
            result = sanitizeMethods(memory.getImageStrategy().getMethods());
            if (!result.isEmpty()) {
                source = "task_memory";
                reason = "reuse_task_memory_methods";
            }
        }

        if (result.isEmpty() && memoryContext != null) {
            result = sanitizeMethods(memoryContext.getPreferredImageMethods());
            if (!result.isEmpty()) {
                source = "long_term_memory";
                reason = "reuse_recalled_memory_methods";
            }
        }

        if (result.isEmpty()) {
            result = new ArrayList<>(DEFAULT_NON_VIP_METHODS);
            source = "default_policy";
            reason = "apply_default_image_policy";
        }

        result = reorderMethods(result, diagramPreferred, realisticPreferred);
        result = removeAvoidedMethods(result, memoryContext == null ? null : memoryContext.getAvoidImageMethods());
        if (diagramPreferred) {
            result = promote(result, ImageMethodEnum.MERMAID.getValue(), ImageMethodEnum.SVG_DIAGRAM.getValue(), ImageMethodEnum.ICONIFY.getValue());
            reason = appendReason(reason, "diagram_signal_detected");
        } else if (realisticPreferred) {
            result = promote(result, ImageMethodEnum.PEXELS.getValue(), ImageMethodEnum.NANO_BANANA.getValue());
            reason = appendReason(reason, "realistic_signal_detected");
        }
        if (memoryContext != null && memoryContext.getAvoidImageMethods() != null
                && !memoryContext.getAvoidImageMethods().isEmpty()) {
            reason = appendReason(reason, "avoid_failed_methods");
        }

        ImageStrategyDecisionVO decision = ImageStrategyDecisionVO.builder()
                .needImages(Boolean.TRUE)
                .preferredMethods(result)
                .source(source)
                .reason(reason)
                .diagramPreferred(diagramPreferred)
                .realisticPhotoPreferred(realisticPreferred)
                .build();
        applyDecisionMetadata(metadata, decision);
        return decision;
    }

    private String buildSignalText(ArticleState state) {
        StringBuilder builder = new StringBuilder();
        append(builder, state.getTopic());
        append(builder, state.getUserDescription());
        if (state.getTitle() != null) {
            append(builder, state.getTitle().getMainTitle());
            append(builder, state.getTitle().getSubTitle());
        }
        if (state.getOutline() != null && state.getOutline().getSections() != null) {
            state.getOutline().getSections().stream()
                    .filter(Objects::nonNull)
                    .forEach(section -> {
                        append(builder, section.getTitle());
                        if (section.getPoints() != null) {
                            section.getPoints().forEach(point -> append(builder, point));
                        }
                    });
        }
        append(builder, state.getContent());
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private void append(StringBuilder builder, String value) {
        if (!isBlank(value)) {
            builder.append(' ').append(value);
        }
    }

    private boolean containsAny(String text, String... words) {
        if (isBlank(text) || words == null) {
            return false;
        }
        for (String word : words) {
            if (!isBlank(word) && text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> reorderMethods(List<String> methods, boolean diagramPreferred, boolean realisticPreferred) {
        if (methods == null || methods.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>(new LinkedHashSet<>(methods));
        if (diagramPreferred) {
            return promote(result, ImageMethodEnum.MERMAID.getValue(), ImageMethodEnum.SVG_DIAGRAM.getValue(), ImageMethodEnum.ICONIFY.getValue());
        }
        if (realisticPreferred) {
            return promote(result, ImageMethodEnum.PEXELS.getValue(), ImageMethodEnum.NANO_BANANA.getValue());
        }
        return result;
    }

    private List<String> removeAvoidedMethods(List<String> methods, List<String> avoidMethods) {
        if (methods == null || methods.isEmpty() || avoidMethods == null || avoidMethods.isEmpty()) {
            return methods == null ? new ArrayList<>() : new ArrayList<>(methods);
        }
        Set<String> avoidSet = avoidMethods.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        List<String> filtered = methods.stream()
                .filter(method -> !avoidSet.contains(method))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return filtered.isEmpty() ? new ArrayList<>(methods) : filtered;
    }

    private List<String> promote(List<String> methods, String... preferred) {
        Set<String> ordered = new LinkedHashSet<>();
        for (String method : preferred) {
            if (methods.contains(method)) {
                ordered.add(method);
            }
        }
        ordered.addAll(methods);
        return new ArrayList<>(ordered);
    }

    private List<String> sanitizeMethods(List<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> sanitized = new LinkedHashSet<>();
        for (String method : methods) {
            if (isBlank(method)) {
                continue;
            }
            ImageMethodEnum methodEnum = ImageMethodEnum.getByValue(method);
            if (methodEnum != null && !methodEnum.isFallback()) {
                sanitized.add(methodEnum.getValue());
            }
        }
        return new ArrayList<>(sanitized);
    }

    private String appendReason(String base, String extra) {
        if (isBlank(base)) {
            return extra;
        }
        if (isBlank(extra)) {
            return base;
        }
        return base + "|" + extra;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void applyDecisionMetadata(NodeExecutionMetadata metadata, ImageStrategyDecisionVO decision) {
        if (metadata == null || decision == null) {
            return;
        }
        metadata.setDecisionSource(decision.getSource());
        metadata.setDecisionReason(decision.getReason());
        metadata.setDecisionSummary("needImages=" + decision.getNeedImages()
                + ", methods=" + decision.getPreferredMethods());
    }
}
