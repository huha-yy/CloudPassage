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
 * 正文生成 Agent。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentGeneratorAgent implements NodeAction {

    private static final String PROFILE_NAME = "content-generator";

    private final DashScopeChatModel chatModel;
    private final AgentPromptSupport agentPromptSupport;

    public static final String INPUT_MAIN_TITLE = "mainTitle";
    public static final String INPUT_SUB_TITLE = "subTitle";
    public static final String INPUT_OUTLINE = "outline";
    public static final String INPUT_STYLE = "style";
    public static final String OUTPUT_CONTENT = "content";

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String mainTitle = state.value(INPUT_MAIN_TITLE)
                .map(Object::toString)
                .orElseThrow(() -> new IllegalArgumentException("缺少主标题参数"));

        String subTitle = state.value(INPUT_SUB_TITLE)
                .map(Object::toString)
                .orElse("");

        ArticleState.OutlineResult outline = state.value(INPUT_OUTLINE)
                .map(v -> {
                    if (v instanceof ArticleState.OutlineResult result) {
                        return result;
                    }
                    return GsonUtils.fromJson(GsonUtils.toJson(v), ArticleState.OutlineResult.class);
                })
                .orElseThrow(() -> new IllegalArgumentException("缺少大纲参数"));

        String style = state.value(INPUT_STYLE)
                .map(Object::toString)
                .orElse(null);

        AgentProfile profile = agentPromptSupport.resolveProfile(PROFILE_NAME, "agent3_content", true, false);
        String outlineText = GsonUtils.toJson(outline.getSections());
        String promptContent = agentPromptSupport.getPromptContent(profile.getPromptKey(), profile.getPromptVersion())
                .replace("{mainTitle}", mainTitle)
                .replace("{subTitle}", subTitle)
                .replace("{outline}", outlineText)
                + agentPromptSupport.getStylePrompt(style);

        log.info("ContentGeneratorAgent started, mainTitle={}, model={}, promptKey={}, promptVersion={}",
                mainTitle, profile.getModel(), profile.getPromptKey(), profile.getPromptVersion());

        Consumer<String> streamHandler = StreamHandlerContext.get();
        String content = callLlmWithStreaming(promptContent, profile, streamHandler);

        log.info("ContentGeneratorAgent finished, contentLength={}", content.length());

        return Map.of(OUTPUT_CONTENT, content);
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
                            streamHandler.accept(SseMessageTypeEnum.AGENT3_STREAMING.getStreamingPrefix() + chunk);
                        }
                    }
                })
                .doOnError(error -> log.error("ContentGeneratorAgent streaming call failed", error))
                .blockLast();

        return contentBuilder.toString();
    }
}
