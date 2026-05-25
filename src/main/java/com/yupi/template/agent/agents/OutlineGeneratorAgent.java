package com.yupi.template.agent.agents;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.yupi.template.agent.config.AgentProfile;
import com.yupi.template.agent.context.StreamHandlerContext;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.enums.SseMessageTypeEnum;
import com.yupi.template.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 大纲生成 Agent。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutlineGeneratorAgent implements NodeAction {

    private static final String PROFILE_NAME = "outline-generator";

    private final DashScopeChatModel chatModel;
    private final AgentPromptSupport agentPromptSupport;

    public static final String INPUT_MAIN_TITLE = "mainTitle";
    public static final String INPUT_SUB_TITLE = "subTitle";
    public static final String INPUT_USER_DESCRIPTION = "userDescription";
    public static final String INPUT_STYLE = "style";
    public static final String OUTPUT_OUTLINE = "outline";

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String mainTitle = state.value(INPUT_MAIN_TITLE)
                .map(Object::toString)
                .orElseThrow(() -> new IllegalArgumentException("缺少主标题参数"));

        String subTitle = state.value(INPUT_SUB_TITLE)
                .map(Object::toString)
                .orElse("");

        String userDescription = state.value(INPUT_USER_DESCRIPTION)
                .map(Object::toString)
                .orElse(null);

        String style = state.value(INPUT_STYLE)
                .map(Object::toString)
                .orElse(null);

        AgentProfile profile = agentPromptSupport.resolveProfile(PROFILE_NAME, "agent2_outline", true, true);
        String descriptionSection = "";
        if (userDescription != null && !userDescription.trim().isEmpty()) {
            descriptionSection = agentPromptSupport.getPromptContent("agent2_description_section", profile.getPromptVersion())
                    .replace("{userDescription}", userDescription);
        }

        String promptContent = agentPromptSupport.getPromptContent(profile.getPromptKey(), profile.getPromptVersion())
                .replace("{mainTitle}", mainTitle)
                .replace("{subTitle}", subTitle)
                .replace("{descriptionSection}", descriptionSection)
                + agentPromptSupport.getStylePrompt(style);

        log.info("OutlineGeneratorAgent started, mainTitle={}, model={}, promptKey={}, promptVersion={}",
                mainTitle, profile.getModel(), profile.getPromptKey(), profile.getPromptVersion());

        Consumer<String> streamHandler = StreamHandlerContext.get();
        String content = callLlmWithStreaming(promptContent, profile, streamHandler);

        ArticleState.OutlineResult outlineResult = GsonUtils.fromJson(content, ArticleState.OutlineResult.class);

        log.info("OutlineGeneratorAgent finished, sections={}",
                outlineResult == null || outlineResult.getSections() == null ? 0 : outlineResult.getSections().size());

        return Map.of(OUTPUT_OUTLINE, outlineResult);
    }

    private String callLlmWithStreaming(String promptContent, AgentProfile profile, Consumer<String> streamHandler) {
        StringBuilder contentBuilder = new StringBuilder();
        Prompt prompt = agentPromptSupport.buildPrompt(promptContent, profile, true);
        Flux<ChatResponse> streamResponse = chatModel.stream(prompt);

        streamResponse
                .doOnNext(response -> {
                    String chunk = response.getResult().getOutput().getText();
                    if (chunk != null && !chunk.isEmpty()) {
                        contentBuilder.append(chunk);
                        if (streamHandler != null) {
                            streamHandler.accept(SseMessageTypeEnum.AGENT2_STREAMING.getStreamingPrefix() + chunk);
                        }
                    }
                })
                .doOnError(error -> log.error("OutlineGeneratorAgent streaming call failed", error))
                .blockLast();

        return contentBuilder.toString();
    }
}
