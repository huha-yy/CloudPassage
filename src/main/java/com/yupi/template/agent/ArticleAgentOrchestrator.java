package com.yupi.template.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.yupi.template.agent.agents.ContentGeneratorAgent;
import com.yupi.template.agent.agents.ContentMergerAgent;
import com.yupi.template.agent.agents.ImageAnalyzerAgent;
import com.yupi.template.agent.agents.OutlineGeneratorAgent;
import com.yupi.template.agent.agents.TitleGeneratorAgent;
import com.yupi.template.agent.parallel.ParallelImageGenerator;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.enums.SseMessageTypeEnum;
import com.yupi.template.model.vo.ImageStrategyDecisionVO;
import com.yupi.template.service.ArticleNodeLogService;
import com.yupi.template.service.ArticleAgentService;
import com.yupi.template.service.ArticleMemoryService;
import com.yupi.template.service.ImageStrategyRouterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 文章多 Agent 编排器。
 */
@Service
@Slf4j
public class ArticleAgentOrchestrator {

    @Resource
    private TitleGeneratorAgent titleGeneratorAgent;

    @Resource
    private OutlineGeneratorAgent outlineGeneratorAgent;

    @Resource
    private ContentGeneratorAgent contentGeneratorAgent;

    @Resource
    private ImageAnalyzerAgent imageAnalyzerAgent;

    @Resource
    private ParallelImageGenerator parallelImageGenerator;

    @Resource
    private ContentMergerAgent contentMergerAgent;

    @Resource
    private ArticleNodeLogService articleNodeLogService;

    @Resource
    private ImageStrategyRouterService imageStrategyRouterService;

    @Resource
    private ArticleMemoryService articleMemoryService;

    @Resource
    private ArticleAgentService articleAgentService;

    private static final String KEY_TASK_ID = "taskId";
    private static final String KEY_TOPIC = "topic";
    private static final String KEY_STYLE = "style";
    private static final String KEY_USER_DESCRIPTION = "userDescription";
    private static final String KEY_MAIN_TITLE = "mainTitle";
    private static final String KEY_SUB_TITLE = "subTitle";
    private static final String KEY_TITLE_OPTIONS = "titleOptions";
    private static final String KEY_OUTLINE = "outline";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_CONTENT_WITH_PLACEHOLDERS = "contentWithPlaceholders";
    private static final String KEY_IMAGE_REQUIREMENTS = "imageRequirements";
    private static final String KEY_IMAGES = "images";
    private static final String KEY_FULL_CONTENT = "fullContent";
    private static final String KEY_ENABLED_IMAGE_METHODS = "enabledImageMethods";

    public void executePhase1_GenerateTitles(ArticleState state, Consumer<String> streamHandler) {
        log.info("Phase1 orchestrator started, taskId={}", state.getTaskId());
        try {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put(KEY_TASK_ID, state.getTaskId());
            inputs.put(KEY_TOPIC, state.getTopic());
            inputs.put(KEY_STYLE, state.getStyle());

            StateGraph graph = buildPhase1Graph();
            CompiledGraph compiledGraph = graph.compile();
            Optional<OverAllState> result = compiledGraph.invoke(inputs);

            if (result.isEmpty()) {
                throw new RuntimeException("标题方案生成失败：执行结果为空");
            }
            OverAllState finalState = result.get();
            @SuppressWarnings("unchecked")
            List<ArticleState.TitleOption> titleOptions = finalState.value(KEY_TITLE_OPTIONS)
                    .map(v -> (List<ArticleState.TitleOption>) v)
                    .orElse(null);
            if (titleOptions == null) {
                throw new RuntimeException("标题方案生成失败：结果为空");
            }
            state.setTitleOptions(titleOptions);
            streamHandler.accept(SseMessageTypeEnum.AGENT1_COMPLETE.getValue());
            log.info("Phase1 orchestrator finished, optionsCount={}", titleOptions.size());
        } catch (Exception e) {
            log.error("Phase1 orchestrator failed, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("标题方案生成失败: " + e.getMessage(), e);
        }
    }

    public void executePhase2_GenerateOutline(ArticleState state, Consumer<String> streamHandler) {
        log.info("Phase2 orchestrator started, taskId={}", state.getTaskId());
        try {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put(KEY_TASK_ID, state.getTaskId());
            inputs.put(KEY_MAIN_TITLE, state.getTitle().getMainTitle());
            inputs.put(KEY_SUB_TITLE, state.getTitle().getSubTitle());
            inputs.put(KEY_USER_DESCRIPTION, state.getUserDescription());
            inputs.put(KEY_STYLE, state.getStyle());

            StateGraph graph = buildPhase2Graph();
            CompiledGraph compiledGraph = graph.compile();
            Optional<OverAllState> result = compiledGraph.invoke(inputs);

            if (result.isEmpty()) {
                throw new RuntimeException("大纲生成失败：执行结果为空");
            }
            OverAllState finalState = result.get();
            ArticleState.OutlineResult outline = finalState.value(KEY_OUTLINE)
                    .filter(ArticleState.OutlineResult.class::isInstance)
                    .map(ArticleState.OutlineResult.class::cast)
                    .orElse(null);
            if (outline == null) {
                throw new RuntimeException("大纲生成失败：结果为空");
            }
            state.setOutline(outline);
            streamHandler.accept(SseMessageTypeEnum.AGENT2_COMPLETE.getValue());
            log.info("Phase2 orchestrator finished, sectionsCount={}", outline.getSections().size());
        } catch (Exception e) {
            log.error("Phase2 orchestrator failed, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("大纲生成失败: " + e.getMessage(), e);
        }
    }

    public void executePhase3_GenerateContent(ArticleState state, Consumer<String> streamHandler) {
        log.info("Phase3 orchestrator started, taskId={}", state.getTaskId());
        try {
            applyImageStrategyRouter(state);
            Map<String, Object> inputs = new HashMap<>();
            inputs.put(KEY_TASK_ID, state.getTaskId());
            inputs.put(KEY_MAIN_TITLE, state.getTitle().getMainTitle());
            inputs.put(KEY_SUB_TITLE, state.getTitle().getSubTitle());
            inputs.put(KEY_OUTLINE, state.getOutline());
            inputs.put(KEY_STYLE, state.getStyle());
            inputs.put(KEY_ENABLED_IMAGE_METHODS, state.getEnabledImageMethods());

            StateGraph graph = buildPhase3Graph();
            CompiledGraph compiledGraph = graph.compile();
            Optional<OverAllState> result = compiledGraph.invoke(inputs);

            if (result.isEmpty()) {
                throw new RuntimeException("正文与配图生成失败：执行结果为空");
            }
            OverAllState finalState = result.get();
            String contentWithPlaceholders = finalState.value(KEY_CONTENT_WITH_PLACEHOLDERS)
                    .map(Object::toString)
                    .orElse(null);
            String content = finalState.value(KEY_CONTENT)
                    .map(Object::toString)
                    .orElse(null);
            @SuppressWarnings("unchecked")
            List<ArticleState.ImageRequirement> imageRequirements = finalState.value(KEY_IMAGE_REQUIREMENTS)
                    .map(v -> (List<ArticleState.ImageRequirement>) v)
                    .orElse(null);
            @SuppressWarnings("unchecked")
            List<ArticleState.ImageResult> images = finalState.value(KEY_IMAGES)
                    .map(v -> (List<ArticleState.ImageResult>) v)
                    .orElse(null);
            @SuppressWarnings("unchecked")
            List<ArticleState.ImageFallbackRecord> imageFallbackRecords = finalState.value("imageFallbackRecords")
                    .map(v -> (List<ArticleState.ImageFallbackRecord>) v)
                    .orElse(null);
            String fullContent = finalState.value(KEY_FULL_CONTENT)
                    .map(Object::toString)
                    .orElse(null);

            if (contentWithPlaceholders != null) {
                state.setContent(contentWithPlaceholders);
            } else if (content != null) {
                state.setContent(content);
            }
            streamHandler.accept(SseMessageTypeEnum.AGENT3_COMPLETE.getValue());
            articleAgentService.agent3ReviewContent(state, buildContentReviewMetadata());
            streamHandler.accept(SseMessageTypeEnum.AGENT3_REVIEW_COMPLETE.getValue());

            if (imageRequirements != null) {
                state.setImageRequirements(imageRequirements);
                streamHandler.accept(SseMessageTypeEnum.AGENT4_COMPLETE.getValue());
            }
            if (images != null) {
                state.setImages(images);
                state.setImageFallbackRecords(imageFallbackRecords);
                streamHandler.accept(SseMessageTypeEnum.AGENT5_COMPLETE.getValue());
            }
            if (fullContent != null) {
                state.setFullContent(fullContent);
                streamHandler.accept(SseMessageTypeEnum.MERGE_COMPLETE.getValue());
            }

            log.info("Phase3 orchestrator finished, contentLength={}, imageCount={}",
                    contentWithPlaceholders != null ? contentWithPlaceholders.length() : (content == null ? 0 : content.length()),
                    images == null ? 0 : images.size());
        } catch (Exception e) {
            log.error("Phase3 orchestrator failed, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("正文与配图生成失败: " + e.getMessage(), e);
        }
    }

    private void applyImageStrategyRouter(ArticleState state) {
        ImageStrategyDecisionVO decision = imageStrategyRouterService.route(state, buildImageRouterMetadata());
        if (decision == null || decision.getPreferredMethods() == null || decision.getPreferredMethods().isEmpty()) {
            return;
        }
        state.setEnabledImageMethods(decision.getPreferredMethods());
        articleMemoryService.recordImageStrategyDecision(state.getTaskId(), state, decision);
        articleMemoryService.recordNodeSnapshot(state.getTaskId(), "CONTENT_GENERATING", "image_strategy_router", "SUCCESS", state);
        articleNodeLogService.info(state.getTaskId(), "CONTENT_GENERATING", "image_strategy_router",
                "图片策略已路由：source=" + decision.getSource() + ", methods=" + decision.getPreferredMethods(),
                buildImageRouterMetadata());
    }

    private StateGraph buildPhase1Graph() throws GraphStateException {
        KeyStrategyFactory keyStrategyFactory = createKeyStrategyFactory();
        return new StateGraph(keyStrategyFactory)
                .addNode("title_generator", node_async(titleGeneratorAgent))
                .addEdge(START, "title_generator")
                .addEdge("title_generator", END);
    }

    private StateGraph buildPhase2Graph() throws GraphStateException {
        KeyStrategyFactory keyStrategyFactory = createKeyStrategyFactory();
        return new StateGraph(keyStrategyFactory)
                .addNode("outline_generator", node_async(outlineGeneratorAgent))
                .addEdge(START, "outline_generator")
                .addEdge("outline_generator", END);
    }

    private StateGraph buildPhase3Graph() throws GraphStateException {
        KeyStrategyFactory keyStrategyFactory = createKeyStrategyFactory();
        return new StateGraph(keyStrategyFactory)
                .addNode("content_generator", node_async(contentGeneratorAgent))
                .addNode("image_analyzer", node_async(imageAnalyzerAgent))
                .addNode("parallel_image_generator", node_async(parallelImageGenerator))
                .addNode("content_merger", node_async(contentMergerAgent))
                .addEdge(START, "content_generator")
                .addEdge("content_generator", "image_analyzer")
                .addEdge("image_analyzer", "parallel_image_generator")
                .addEdge("parallel_image_generator", "content_merger")
                .addEdge("content_merger", END);
    }

    private KeyStrategyFactory createKeyStrategyFactory() {
        return () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(KEY_TASK_ID, new ReplaceStrategy());
            strategies.put(KEY_TOPIC, new ReplaceStrategy());
            strategies.put(KEY_STYLE, new ReplaceStrategy());
            strategies.put(KEY_USER_DESCRIPTION, new ReplaceStrategy());
            strategies.put(KEY_MAIN_TITLE, new ReplaceStrategy());
            strategies.put(KEY_SUB_TITLE, new ReplaceStrategy());
            strategies.put(KEY_TITLE_OPTIONS, new ReplaceStrategy());
            strategies.put(KEY_OUTLINE, new ReplaceStrategy());
            strategies.put(KEY_CONTENT, new ReplaceStrategy());
            strategies.put(KEY_CONTENT_WITH_PLACEHOLDERS, new ReplaceStrategy());
            strategies.put(KEY_IMAGE_REQUIREMENTS, new ReplaceStrategy());
            strategies.put(KEY_IMAGES, new ReplaceStrategy());
            strategies.put(KEY_FULL_CONTENT, new ReplaceStrategy());
            strategies.put(KEY_ENABLED_IMAGE_METHODS, new ReplaceStrategy());
            return strategies;
        };
    }

    private com.yupi.template.model.vo.NodeExecutionMetadata buildImageRouterMetadata() {
        return com.yupi.template.model.vo.NodeExecutionMetadata.builder()
                .promptKey("image_strategy_router")
                .promptVersion("rule-v1")
                .model("rule-engine")
                .temperature(0D)
                .maxTokens(0)
                .topP(0D)
                .build();
    }

    private com.yupi.template.model.vo.NodeExecutionMetadata buildContentReviewMetadata() {
        return com.yupi.template.model.vo.NodeExecutionMetadata.builder()
                .promptKey("agent3_content_review")
                .promptVersion("v1")
                .model("qwen-plus")
                .build();
    }
}
