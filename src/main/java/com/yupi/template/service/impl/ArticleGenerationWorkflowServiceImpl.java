package com.yupi.template.service.impl;

import com.yupi.template.agent.ArticleAgentOrchestrator;
import com.yupi.template.agent.config.AgentConfig;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.dto.article.ArticleWorkflowEvent;
import com.yupi.template.model.enums.ArticlePhaseEnum;
import com.yupi.template.model.enums.SseMessageTypeEnum;
import com.yupi.template.service.ArticleAgentService;
import com.yupi.template.service.ArticleGenerationWorkflowService;
import com.yupi.template.service.ArticleNodeLogService;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * Unified workflow adapter that hides the current execution engine from callers.
 */
@Service
@Slf4j
public class ArticleGenerationWorkflowServiceImpl implements ArticleGenerationWorkflowService {

    @Resource
    private ArticleAgentService articleAgentService;

    @Resource
    private ArticleAgentOrchestrator articleAgentOrchestrator;

    @Resource
    private AgentConfig agentConfig;

    @Resource
    private ArticleNodeLogService articleNodeLogService;

    @Override
    public void generateTitles(ArticleState state, Consumer<ArticleWorkflowEvent> eventConsumer) {
        log.info("Dispatch title generation, taskId={}, mode={}", state.getTaskId(), getWorkflowMode());
        if (agentConfig.isOrchestratorEnabled()) {
            articleAgentOrchestrator.executePhase1_GenerateTitles(state, message -> emit(message, eventConsumer));
            return;
        }
        articleAgentService.executePhase1_GenerateTitles(state, message -> emit(message, eventConsumer));
    }

    @Override
    public void generateOutline(ArticleState state, Consumer<ArticleWorkflowEvent> eventConsumer) {
        log.info("Dispatch outline generation, taskId={}, mode={}", state.getTaskId(), getWorkflowMode());
        if (agentConfig.isOrchestratorEnabled()) {
            articleAgentOrchestrator.executePhase2_GenerateOutline(state, message -> emit(message, eventConsumer));
            return;
        }
        articleAgentService.executePhase2_GenerateOutline(state, message -> emit(message, eventConsumer));
    }

    @Override
    public void generateContent(ArticleState state, Consumer<ArticleWorkflowEvent> eventConsumer) {
        log.info("Dispatch content generation, taskId={}, mode={}", state.getTaskId(), getWorkflowMode());
        if (agentConfig.isOrchestratorEnabled()) {
            articleAgentOrchestrator.executePhase3_GenerateContent(state, message -> emit(message, eventConsumer));
            return;
        }
        articleAgentService.executePhase3_GenerateContent(state, message -> emit(message, eventConsumer));
    }

    @Override
    public String getWorkflowMode() {
        return agentConfig.isOrchestratorEnabled() ? "orchestrator" : "legacy";
    }

    @Override
    public void emitNodeStart(String taskId, String phase, String node) {
        articleNodeLogService.start(taskId, phase, node, node + " started");
    }

    @Override
    public void emitNodeSuccess(String taskId, String phase, String node) {
        articleNodeLogService.success(taskId, phase, node, node + " finished");
    }

    @Override
    public void emitNodeFailure(String taskId, String phase, String node, Exception exception) {
        String message = exception == null ? node + " failed" : exception.getMessage();
        articleNodeLogService.fail(taskId, phase, node, message);
    }

    private void emit(String rawMessage, Consumer<ArticleWorkflowEvent> eventConsumer) {
        String agent2Prefix = SseMessageTypeEnum.AGENT2_STREAMING.getStreamingPrefix();
        String agent3Prefix = SseMessageTypeEnum.AGENT3_STREAMING.getStreamingPrefix();
        String imageCompletePrefix = SseMessageTypeEnum.IMAGE_COMPLETE.getStreamingPrefix();

        if (rawMessage.startsWith(agent2Prefix)) {
            eventConsumer.accept(ArticleWorkflowEvent.streaming(
                    SseMessageTypeEnum.AGENT2_STREAMING,
                    rawMessage.substring(agent2Prefix.length())
            ));
            return;
        }
        if (rawMessage.startsWith(agent3Prefix)) {
            eventConsumer.accept(ArticleWorkflowEvent.streaming(
                    SseMessageTypeEnum.AGENT3_STREAMING,
                    rawMessage.substring(agent3Prefix.length())
            ));
            return;
        }
        if (rawMessage.startsWith(imageCompletePrefix)) {
            ArticleState.ImageResult image = GsonUtils.fromJson(
                    rawMessage.substring(imageCompletePrefix.length()),
                    ArticleState.ImageResult.class
            );
            eventConsumer.accept(ArticleWorkflowEvent.image(image));
            return;
        }

        SseMessageTypeEnum type = SseMessageTypeEnum.fromValue(rawMessage);
        if (type == null) {
            log.warn("Ignore unsupported workflow signal, rawMessage={}", rawMessage);
            return;
        }
        eventConsumer.accept(ArticleWorkflowEvent.simple(type));
    }
}
