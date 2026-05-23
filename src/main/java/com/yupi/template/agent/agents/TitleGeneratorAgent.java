package com.yupi.template.agent.agents;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.google.gson.reflect.TypeToken;
import com.yupi.template.agent.config.AgentProfile;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 标题生成 Agent。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TitleGeneratorAgent implements NodeAction {

    private static final String PROFILE_NAME = "title-generator";

    private final DashScopeChatModel chatModel;
    private final AgentPromptSupport agentPromptSupport;

    public static final String INPUT_TOPIC = "topic";
    public static final String INPUT_STYLE = "style";
    public static final String OUTPUT_TITLE_OPTIONS = "titleOptions";

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String topic = state.value(INPUT_TOPIC)
                .map(Object::toString)
                .orElseThrow(() -> new IllegalArgumentException("缺少选题参数"));

        String style = state.value(INPUT_STYLE)
                .map(Object::toString)
                .orElse(null);

        AgentProfile profile = agentPromptSupport.resolveProfile(PROFILE_NAME, "agent1_title", false, true);
        String promptContent = agentPromptSupport.getPromptContent(profile.getPromptKey(), profile.getPromptVersion())
                .replace("{topic}", topic)
                + agentPromptSupport.getStylePrompt(style);
        Prompt prompt = agentPromptSupport.buildPrompt(promptContent, profile, false);

        log.info("TitleGeneratorAgent started, topic={}, style={}, model={}, promptKey={}, promptVersion={}",
                topic, style, profile.getModel(), profile.getPromptKey(), profile.getPromptVersion());

        ChatResponse response = chatModel.call(prompt);
        String content = response.getResult().getOutput().getText();

        List<ArticleState.TitleOption> titleOptions = GsonUtils.fromJson(
                content,
                new TypeToken<List<ArticleState.TitleOption>>() {
                }
        );

        log.info("TitleGeneratorAgent finished, generatedOptionsCount={}",
                titleOptions == null ? 0 : titleOptions.size());

        return Map.of(OUTPUT_TITLE_OPTIONS, titleOptions);
    }
}
