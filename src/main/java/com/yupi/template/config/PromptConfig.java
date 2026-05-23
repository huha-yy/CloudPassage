package com.yupi.template.config;

import com.yupi.template.agent.config.AgentProfile;
import com.yupi.template.constant.PromptConstant;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Prompt configuration with defaults and per-agent profiles.
 */
@Configuration
@ConfigurationProperties(prefix = "prompt")
@Data
public class PromptConfig {

    /**
     * Default prompt version used when a template or profile does not specify one.
     */
    private String defaultVersion = "v1";

    /**
     * Prompt template overrides loaded from configuration.
     */
    private Map<String, PromptTemplateDefinition> templates = new HashMap<>();

    /**
     * Agent-level prompt/model profiles loaded from configuration.
     */
    private Map<String, AgentProfile> profiles = new HashMap<>();

    @PostConstruct
    public void init() {
        mergeTemplate("agent1_title", PromptConstant.AGENT1_TITLE_PROMPT, "title generator prompt");
        mergeTemplate("agent2_outline", PromptConstant.AGENT2_OUTLINE_PROMPT, "outline generator prompt");
        mergeTemplate("agent2_description_section", PromptConstant.AGENT2_DESCRIPTION_SECTION,
                "outline description section prompt");
        mergeTemplate("agent3_content", PromptConstant.AGENT3_CONTENT_PROMPT, "content generator prompt");
        mergeTemplate("agent4_image", PromptConstant.AGENT4_IMAGE_REQUIREMENTS_PROMPT, "image analyzer prompt");
        mergeTemplate("ai_modify_outline", PromptConstant.AI_MODIFY_OUTLINE_PROMPT, "outline modify prompt");
        mergeTemplate("style_tech", PromptConstant.STYLE_TECH_PROMPT, "tech style prompt");
        mergeTemplate("style_emotional", PromptConstant.STYLE_EMOTIONAL_PROMPT, "emotional style prompt");
        mergeTemplate("style_educational", PromptConstant.STYLE_EDUCATIONAL_PROMPT, "educational style prompt");
        mergeTemplate("style_humorous", PromptConstant.STYLE_HUMOROUS_PROMPT, "humorous style prompt");
    }

    /**
     * Returns the configured prompt definition for a key.
     */
    public PromptTemplateDefinition getPrompt(String key) {
        return templates.get(key);
    }

    private void mergeTemplate(String key, String defaultContent, String description) {
        PromptTemplateDefinition current = templates.get(key);
        if (current == null) {
            templates.put(key, PromptTemplateDefinition.builder()
                    .key(key)
                    .version(defaultVersion)
                    .content(defaultContent)
                    .description(description)
                    .build());
            return;
        }
        if (current.getKey() == null || current.getKey().isBlank()) {
            current.setKey(key);
        }
        if (current.getVersion() == null || current.getVersion().isBlank()) {
            current.setVersion(defaultVersion);
        }
        if (current.getContent() == null || current.getContent().isBlank()) {
            current.setContent(defaultContent);
        }
        if (current.getDescription() == null || current.getDescription().isBlank()) {
            current.setDescription(description);
        }
    }
}
