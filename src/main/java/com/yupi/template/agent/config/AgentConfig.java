package com.yupi.template.agent.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared agent runtime configuration.
 */
@Configuration
@Getter
public class AgentConfig {

    /**
     * Whether to use the StateGraph-based orchestrator or the legacy workflow.
     */
    @Value("${article.agent.orchestrator.enabled:true}")
    private boolean orchestratorEnabled;

    /**
     * Maximum orchestrator iterations.
     */
    @Value("${article.agent.max-iterations:10}")
    private int maxIterations;

    /**
     * Default model name used when a node profile does not specify one.
     */
    @Value("${article.agent.defaults.model:qwen-plus}")
    private String defaultModel;

    /**
     * Default sampling temperature used when a node profile does not specify one.
     */
    @Value("${article.agent.defaults.temperature:0.7}")
    private Double defaultTemperature;

    /**
     * Default max token limit used when a node profile does not specify one.
     */
    @Value("${article.agent.defaults.max-tokens:4000}")
    private Integer defaultMaxTokens;

    /**
     * Default topP used when a node profile does not specify one.
     */
    @Value("${article.agent.defaults.top-p:0.9}")
    private Double defaultTopP;

    /**
     * Default prompt version used when a node profile does not specify one.
     */
    @Value("${article.agent.defaults.prompt-version:v1}")
    private String defaultPromptVersion;

    @Bean
    public MemorySaver memorySaver() {
        return new MemorySaver();
    }
}
