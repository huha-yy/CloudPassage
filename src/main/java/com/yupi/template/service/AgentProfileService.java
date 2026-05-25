package com.yupi.template.service;

import com.yupi.template.agent.config.AgentProfile;
import com.yupi.template.agent.config.AgentConfig;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.template.config.PromptConfig;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * Resolves agent-level prompt/model profiles and builds chat options.
 */
@Service
public class AgentProfileService {

    @Resource
    private PromptConfig promptConfig;

    @Resource
    private AgentConfig agentConfig;

    public AgentProfile resolveProfile(String profileName,
                                       String defaultPromptKey,
                                       boolean defaultStreaming,
                                       boolean defaultStructuredOutput) {
        AgentProfile configured = promptConfig.getProfiles().get(profileName);
        AgentProfile.AgentProfileBuilder builder = AgentProfile.builder()
                .name(profileName)
                .promptKey(defaultPromptKey)
                .promptVersion(agentConfig.getDefaultPromptVersion())
                .model(agentConfig.getDefaultModel())
                .temperature(agentConfig.getDefaultTemperature())
                .maxTokens(agentConfig.getDefaultMaxTokens())
                .topP(agentConfig.getDefaultTopP())
                .structuredOutput(defaultStructuredOutput)
                .streaming(defaultStreaming);
        if (configured == null) {
            return builder.build();
        }
        return builder
                .name(valueOrDefault(configured.getName(), profileName))
                .promptKey(valueOrDefault(configured.getPromptKey(), defaultPromptKey))
                .promptVersion(valueOrDefault(configured.getPromptVersion(), agentConfig.getDefaultPromptVersion()))
                .model(valueOrDefault(configured.getModel(), agentConfig.getDefaultModel()))
                .temperature(valueOrDefault(configured.getTemperature(), agentConfig.getDefaultTemperature()))
                .maxTokens(valueOrDefault(configured.getMaxTokens(), agentConfig.getDefaultMaxTokens()))
                .topP(valueOrDefault(configured.getTopP(), agentConfig.getDefaultTopP()))
                .structuredOutput(valueOrDefault(configured.getStructuredOutput(), defaultStructuredOutput))
                .streaming(valueOrDefault(configured.getStreaming(), defaultStreaming))
                .build();
    }

    public DashScopeChatOptions buildOptions(AgentProfile profile, Boolean streamOverride) {
        DashScopeChatOptions.DashScopeChatOptionsBuilder builder = DashScopeChatOptions.builder();
        if (profile.getModel() != null && !profile.getModel().isBlank()) {
            builder.model(profile.getModel());
        }
        if (profile.getTemperature() != null) {
            builder.temperature(profile.getTemperature());
        }
        if (profile.getMaxTokens() != null) {
            builder.maxToken(profile.getMaxTokens());
        }
        if (profile.getTopP() != null) {
            builder.topP(profile.getTopP());
        }
        Boolean stream = streamOverride != null ? streamOverride : profile.getStreaming();
        if (stream != null) {
            builder.stream(stream);
        }
        return builder.build();
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static <T> T valueOrDefault(T value, T defaultValue) {
        return value != null ? value : defaultValue;
    }
}
