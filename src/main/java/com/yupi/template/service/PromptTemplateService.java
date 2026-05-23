package com.yupi.template.service;

import com.yupi.template.config.PromptConfig;
import com.yupi.template.config.PromptTemplateDefinition;
import com.yupi.template.model.enums.ArticleStyleEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * Provides prompt templates with configuration overrides and safe fallbacks.
 */
@Service
public class PromptTemplateService {

    @Resource
    private PromptConfig promptConfig;

    public PromptTemplateDefinition getTemplate(String key) {
        return getTemplate(key, null);
    }

    public PromptTemplateDefinition getTemplate(String key, String preferredVersion) {
        PromptTemplateDefinition template = promptConfig.getPrompt(key);
        if (template == null) {
            return PromptTemplateDefinition.builder()
                    .key(key)
                    .version(resolveVersion(preferredVersion))
                    .content("")
                    .description(key)
                    .build();
        }
        return PromptTemplateDefinition.builder()
                .key(key)
                .version(resolveVersion(preferredVersion != null && !preferredVersion.isBlank()
                        ? preferredVersion
                        : template.getVersion()))
                .content(template.getContent() == null ? "" : template.getContent())
                .description(template.getDescription())
                .build();
    }

    public String getPromptContent(String key) {
        return getTemplate(key).getContent();
    }

    public String getPromptContent(String key, String preferredVersion) {
        return getTemplate(key, preferredVersion).getContent();
    }

    public String getStylePrompt(String styleValue) {
        if (styleValue == null || styleValue.isBlank()) {
            return "";
        }
        ArticleStyleEnum styleEnum = ArticleStyleEnum.getEnumByValue(styleValue);
        if (styleEnum == null) {
            return "";
        }
        String key = switch (styleEnum) {
            case TECH -> "style_tech";
            case EMOTIONAL -> "style_emotional";
            case EDUCATIONAL -> "style_educational";
            case HUMOROUS -> "style_humorous";
        };
        return getPromptContent(key);
    }

    private String resolveVersion(String preferredVersion) {
        if (preferredVersion != null && !preferredVersion.isBlank()) {
            return preferredVersion;
        }
        return promptConfig.getDefaultVersion();
    }
}
