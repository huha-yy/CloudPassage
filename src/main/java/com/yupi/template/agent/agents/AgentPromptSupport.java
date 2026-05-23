package com.yupi.template.agent.agents;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.yupi.template.agent.config.AgentProfile;
import com.yupi.template.config.PromptTemplateDefinition;
import com.yupi.template.model.vo.NodeExecutionMetadata;
import com.yupi.template.service.AgentProfileService;
import com.yupi.template.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Shared helpers for configurable Agent nodes.
 */
@Component
@RequiredArgsConstructor
public class AgentPromptSupport {

    private final PromptTemplateService promptTemplateService;
    private final AgentProfileService agentProfileService;

    public AgentProfile resolveProfile(String profileName,
                                       String defaultPromptKey,
                                       boolean defaultStreaming,
                                       boolean defaultStructuredOutput) {
        return agentProfileService.resolveProfile(profileName, defaultPromptKey, defaultStreaming, defaultStructuredOutput);
    }

    public String getPromptContent(String promptKey, String promptVersion) {
        return promptTemplateService.getPromptContent(promptKey, promptVersion);
    }

    public String getStylePrompt(String styleValue) {
        return promptTemplateService.getStylePrompt(styleValue);
    }

    public Prompt buildPrompt(String content, AgentProfile profile, Boolean streamOverride) {
        return new Prompt(content, agentProfileService.buildOptions(profile, streamOverride));
    }

    public NodeExecutionMetadata toMetadata(AgentProfile profile) {
        if (profile == null) {
            return null;
        }
        return NodeExecutionMetadata.builder()
                .promptKey(profile.getPromptKey())
                .promptVersion(profile.getPromptVersion())
                .model(profile.getModel())
                .temperature(profile.getTemperature())
                .maxTokens(profile.getMaxTokens())
                .topP(profile.getTopP())
                .build();
    }

    public PromptTemplateDefinition getTemplate(String promptKey, String promptVersion) {
        return promptTemplateService.getTemplate(promptKey, promptVersion);
    }
}
