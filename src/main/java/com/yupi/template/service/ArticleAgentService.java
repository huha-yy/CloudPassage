package com.yupi.template.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.google.gson.reflect.TypeToken;
import com.yupi.template.annotation.AgentExecution;
import com.yupi.template.agent.agents.AgentPromptSupport;
import com.yupi.template.agent.config.AgentProfile;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.dto.image.ImageRequest;
import com.yupi.template.model.enums.ImageMethodEnum;
import com.yupi.template.model.enums.SseMessageTypeEnum;
import com.yupi.template.model.vo.ImageStrategyDecisionVO;
import com.yupi.template.model.vo.NodeExecutionMetadata;
import com.yupi.template.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Legacy article agent workflow service.
 */
@Service
@Slf4j
public class ArticleAgentService {

    private static final String TITLE_PROFILE = "title-generator";
    private static final String OUTLINE_PROFILE = "outline-generator";
    private static final String CONTENT_PROFILE = "content-generator";
    private static final String IMAGE_PROFILE = "image-analyzer";

    @Resource
    private DashScopeChatModel chatModel;

    @Resource
    private ImageServiceStrategy imageServiceStrategy;

    @Resource
    private ArticleStructuredOutputService articleStructuredOutputService;

    @Resource
    private AgentPromptSupport agentPromptSupport;

    @Resource
    private ImageStrategyRouterService imageStrategyRouterService;

    @Resource
    private ArticleMemoryService articleMemoryService;

    public void executePhase1_GenerateTitles(ArticleState state, Consumer<String> streamHandler) {
        try {
            log.info("Phase 1 generate titles started, taskId={}", state.getTaskId());
            getProxy().agent1GenerateTitleOptions(state, buildMetadataForProfile(TITLE_PROFILE, "agent1_title", false, true));
            streamHandler.accept(SseMessageTypeEnum.AGENT1_COMPLETE.getValue());
            log.info("Phase 1 generate titles finished, taskId={}, optionsCount={}",
                    state.getTaskId(), state.getTitleOptions().size());
        } catch (Exception e) {
            log.error("Phase 1 generate titles failed, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("Title generation failed: " + e.getMessage(), e);
        }
    }

    public void executePhase2_GenerateOutline(ArticleState state, Consumer<String> streamHandler) {
        try {
            log.info("Phase 2 generate outline started, taskId={}", state.getTaskId());
            getProxy().agent2GenerateOutline(state, streamHandler,
                    buildMetadataForProfile(OUTLINE_PROFILE, "agent2_outline", true, true));
            streamHandler.accept(SseMessageTypeEnum.AGENT2_COMPLETE.getValue());
            log.info("Phase 2 generate outline finished, taskId={}", state.getTaskId());
        } catch (Exception e) {
            log.error("Phase 2 generate outline failed, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("Outline generation failed: " + e.getMessage(), e);
        }
    }

    public void executePhase3_GenerateContent(ArticleState state, Consumer<String> streamHandler) {
        try {
            ArticleAgentService proxy = getProxy();
            log.info("Phase 3 generate content started, taskId={}", state.getTaskId());
            proxy.agent3GenerateContent(state, streamHandler,
                    buildMetadataForProfile(CONTENT_PROFILE, "agent3_content", true, false));
            streamHandler.accept(SseMessageTypeEnum.AGENT3_COMPLETE.getValue());

            proxy.imageStrategyRouter(state, buildImageRouterMetadata());
            log.info("Phase 3 analyze image requirements started, taskId={}", state.getTaskId());
            proxy.agent4AnalyzeImageRequirements(state,
                    buildMetadataForProfile(IMAGE_PROFILE, "agent4_image", false, true));
            streamHandler.accept(SseMessageTypeEnum.AGENT4_COMPLETE.getValue());

            log.info("Phase 3 generate images started, taskId={}", state.getTaskId());
            proxy.agent5GenerateImages(state, streamHandler);
            streamHandler.accept(SseMessageTypeEnum.AGENT5_COMPLETE.getValue());

            log.info("Phase 3 merge content started, taskId={}", state.getTaskId());
            proxy.mergeImagesIntoContent(state);
            streamHandler.accept(SseMessageTypeEnum.MERGE_COMPLETE.getValue());
            log.info("Phase 3 finished, taskId={}", state.getTaskId());
        } catch (Exception e) {
            log.error("Phase 3 failed, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("Content generation failed: " + e.getMessage(), e);
        }
    }

    @AgentExecution(value = "agent1_generate_titles", description = "生成标题方案")
    public void agent1GenerateTitleOptions(ArticleState state, NodeExecutionMetadata metadata) {
        AgentProfile profile = resolveProfile(TITLE_PROFILE, "agent1_title", false, true);
        String promptContent = agentPromptSupport.getPromptContent(profile.getPromptKey(), profile.getPromptVersion())
                .replace("{topic}", state.getTopic())
                + agentPromptSupport.getStylePrompt(state.getStyle());

        List<ArticleState.TitleOption> titleOptions = articleStructuredOutputService.execute(
                promptContent,
                "titleOptions",
                prompt -> callLlm(prompt, profile),
                new TypeToken<List<ArticleState.TitleOption>>() {
                },
                this::isValidTitleOptions
        );
        state.setTitleOptions(titleOptions);
        log.info("Title options generated, count={}, model={}, promptKey={}, promptVersion={}",
                titleOptions.size(), profile.getModel(), profile.getPromptKey(), profile.getPromptVersion());
    }

    @AgentExecution(value = "agent2_generate_outline", description = "生成文章大纲")
    public void agent2GenerateOutline(ArticleState state, Consumer<String> streamHandler, NodeExecutionMetadata metadata) {
        AgentProfile profile = resolveProfile(OUTLINE_PROFILE, "agent2_outline", true, true);
        String descriptionSection = "";
        if (state.getUserDescription() != null && !state.getUserDescription().trim().isEmpty()) {
            descriptionSection = agentPromptSupport.getPromptContent("agent2_description_section", profile.getPromptVersion())
                    .replace("{userDescription}", state.getUserDescription());
        }

        String promptContent = agentPromptSupport.getPromptContent(profile.getPromptKey(), profile.getPromptVersion())
                .replace("{mainTitle}", state.getTitle().getMainTitle())
                .replace("{subTitle}", state.getTitle().getSubTitle())
                .replace("{descriptionSection}", descriptionSection)
                + agentPromptSupport.getStylePrompt(state.getStyle());

        String content = callLlmWithStreaming(promptContent, profile, streamHandler, SseMessageTypeEnum.AGENT2_STREAMING);
        ArticleState.OutlineResult outlineResult = articleStructuredOutputService.execute(
                promptContent,
                "outlineResult",
                ignored -> content,
                ArticleState.OutlineResult.class,
                this::isValidOutlineResult
        );
        state.setOutline(outlineResult);
        state.setOutlineRaw(content);
        log.info("Outline generated, sections={}, model={}, promptKey={}, promptVersion={}",
                outlineResult.getSections().size(), profile.getModel(), profile.getPromptKey(), profile.getPromptVersion());
    }

    @AgentExecution(value = "agent3_generate_content", description = "生成文章正文")
    public void agent3GenerateContent(ArticleState state, Consumer<String> streamHandler, NodeExecutionMetadata metadata) {
        AgentProfile profile = resolveProfile(CONTENT_PROFILE, "agent3_content", true, false);
        String outlineText = GsonUtils.toJson(state.getOutline().getSections());
        String promptContent = agentPromptSupport.getPromptContent(profile.getPromptKey(), profile.getPromptVersion())
                .replace("{mainTitle}", state.getTitle().getMainTitle())
                .replace("{subTitle}", state.getTitle().getSubTitle())
                .replace("{outline}", outlineText)
                + agentPromptSupport.getStylePrompt(state.getStyle());

        String content = callLlmWithStreaming(promptContent, profile, streamHandler, SseMessageTypeEnum.AGENT3_STREAMING);
        state.setContent(content);
        log.info("Content generated, length={}, model={}, promptKey={}, promptVersion={}",
                content.length(), profile.getModel(), profile.getPromptKey(), profile.getPromptVersion());
    }

    public void imageStrategyRouter(ArticleState state, NodeExecutionMetadata metadata) {
        ImageStrategyDecisionVO decision = imageStrategyRouterService.route(state, metadata);
        if (decision == null || decision.getPreferredMethods() == null || decision.getPreferredMethods().isEmpty()) {
            return;
        }
        state.setEnabledImageMethods(decision.getPreferredMethods());
        articleMemoryService.recordImageStrategyDecision(state.getTaskId(), state, decision);
        articleMemoryService.recordNodeSnapshot(state.getTaskId(), "CONTENT_GENERATING", "image_strategy_router", "SUCCESS", state);
        log.info("Image strategy routed, taskId={}, source={}, methods={}",
                state.getTaskId(), decision.getSource(), decision.getPreferredMethods());
    }

    @AgentExecution(value = "agent4_analyze_image_requirements", description = "分析配图需求")
    public void agent4AnalyzeImageRequirements(ArticleState state, NodeExecutionMetadata metadata) {
        AgentProfile profile = resolveProfile(IMAGE_PROFILE, "agent4_image", false, true);
        String availableMethods = buildAvailableMethodsDescription(state.getEnabledImageMethods());
        String methodUsageGuide = buildMethodUsageGuide(state.getEnabledImageMethods());

        String promptContent = agentPromptSupport.getPromptContent(profile.getPromptKey(), profile.getPromptVersion())
                .replace("{mainTitle}", state.getTitle().getMainTitle())
                .replace("{content}", state.getContent())
                .replace("{availableMethods}", availableMethods)
                .replace("{methodUsageGuide}", methodUsageGuide);

        ArticleState.Agent4Result agent4Result = articleStructuredOutputService.execute(
                promptContent,
                "imageRequirements",
                prompt -> callLlm(prompt, profile),
                ArticleState.Agent4Result.class,
                this::isValidAgent4Result
        );

        state.setContent(agent4Result.getContentWithPlaceholders());
        List<ArticleState.ImageRequirement> validatedRequirements = validateAndFilterImageRequirements(
                agent4Result.getImageRequirements(),
                state.getEnabledImageMethods()
        );
        state.setImageRequirements(validatedRequirements);
        log.info("Image requirements analyzed, rawCount={}, validatedCount={}, model={}, promptKey={}, promptVersion={}",
                agent4Result.getImageRequirements().size(), validatedRequirements.size(),
                profile.getModel(), profile.getPromptKey(), profile.getPromptVersion());
    }

    @AgentExecution(value = "agent5_generate_images", description = "生成配图")
    public void agent5GenerateImages(ArticleState state, Consumer<String> streamHandler) {
        List<ArticleState.ImageResult> imageResults = new ArrayList<>();
        for (ArticleState.ImageRequirement requirement : state.getImageRequirements()) {
            String imageSource = requirement.getImageSource();
            log.info("Generate image, position={}, source={}, keywords={}",
                    requirement.getPosition(), imageSource, requirement.getKeywords());

            ImageRequest imageRequest = ImageRequest.builder()
                    .keywords(requirement.getKeywords())
                    .prompt(requirement.getPrompt())
                    .position(requirement.getPosition())
                    .type(requirement.getType())
                    .build();

            ImageServiceStrategy.ImageResult result = imageServiceStrategy.getImageAndUpload(imageSource, imageRequest);
            ArticleState.ImageResult imageResult = buildImageResult(requirement, result.getUrl(), result.getMethod());
            imageResults.add(imageResult);
            streamHandler.accept(SseMessageTypeEnum.IMAGE_COMPLETE.getStreamingPrefix() + GsonUtils.toJson(imageResult));
            log.info("Image generated, position={}, method={}, url={}",
                    requirement.getPosition(), result.getMethod().getValue(), result.getUrl());
        }
        state.setImages(imageResults);
        log.info("All images generated, count={}", imageResults.size());
    }

    @AgentExecution(value = "agent6_merge_content", description = "图文合成")
    public void mergeImagesIntoContent(ArticleState state) {
        String content = state.getContent();
        List<ArticleState.ImageResult> images = state.getImages();
        if (images == null || images.isEmpty()) {
            state.setFullContent(content);
            return;
        }

        String fullContent = content;
        for (ArticleState.ImageResult image : images) {
            String placeholder = image.getPlaceholderId();
            if (placeholder != null && !placeholder.isEmpty()) {
                String imageMarkdown = "![" + image.getDescription() + "](" + image.getUrl() + ")";
                fullContent = fullContent.replace(placeholder, imageMarkdown);
            }
        }
        state.setFullContent(fullContent);
        log.info("Merge content finished, length={}", fullContent.length());
    }

    private String callLlm(String promptContent, AgentProfile profile) {
        Prompt prompt = agentPromptSupport.buildPrompt(promptContent, profile, false);
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }

    private String callLlmWithStreaming(String promptContent,
                                        AgentProfile profile,
                                        Consumer<String> streamHandler,
                                        SseMessageTypeEnum messageType) {
        StringBuilder contentBuilder = new StringBuilder();
        Prompt prompt = agentPromptSupport.buildPrompt(promptContent, profile, true);
        Flux<ChatResponse> streamResponse = chatModel.stream(prompt);
        streamResponse
                .doOnNext(response -> {
                    String chunk = response.getResult().getOutput().getText();
                    if (chunk != null && !chunk.isEmpty()) {
                        contentBuilder.append(chunk);
                        streamHandler.accept(messageType.getStreamingPrefix() + chunk);
                    }
                })
                .doOnError(error -> log.error("Streaming LLM call failed, messageType={}", messageType, error))
                .blockLast();
        return contentBuilder.toString();
    }

    private ArticleState.ImageResult buildImageResult(ArticleState.ImageRequirement requirement,
                                                      String imageUrl,
                                                      ImageMethodEnum method) {
        ArticleState.ImageResult imageResult = new ArticleState.ImageResult();
        imageResult.setPosition(requirement.getPosition());
        imageResult.setUrl(imageUrl);
        imageResult.setMethod(method.getValue());
        imageResult.setKeywords(requirement.getKeywords());
        imageResult.setSectionTitle(requirement.getSectionTitle());
        imageResult.setDescription(requirement.getType());
        imageResult.setPlaceholderId(requirement.getPlaceholderId());
        return imageResult;
    }

    private String buildAvailableMethodsDescription(List<String> enabledMethods) {
        if (enabledMethods == null || enabledMethods.isEmpty()) {
            return getAllMethodsDescription();
        }
        StringBuilder sb = new StringBuilder();
        for (String method : enabledMethods) {
            ImageMethodEnum methodEnum = ImageMethodEnum.getByValue(method);
            if (methodEnum != null && !methodEnum.isFallback()) {
                sb.append("- ")
                        .append(methodEnum.getValue())
                        .append(": ")
                        .append(getMethodUsageDescription(methodEnum))
                        .append("\n");
            }
        }
        return sb.toString();
    }

    private String getAllMethodsDescription() {
        return """
                - PEXELS: realistic photos for scenes, people, products, and places
                - NANO_BANANA: AI-generated illustrations or stylized visuals
                - MERMAID: flowcharts, architecture diagrams, and sequence diagrams
                - ICONIFY: icons and small decorative symbols
                - EMOJI_PACK: meme-like or lightweight expressive images
                - SVG_DIAGRAM: conceptual SVG diagrams and relationship maps
                """;
    }

    private String getMethodUsageDescription(ImageMethodEnum method) {
        return switch (method) {
            case PEXELS -> "realistic stock photos";
            case NANO_BANANA -> "AI-generated illustration or concept art";
            case MERMAID -> "structured diagrams using Mermaid code";
            case ICONIFY -> "searchable icon assets";
            case EMOJI_PACK -> "emoji or meme style pictures";
            case SVG_DIAGRAM -> "conceptual SVG diagrams";
            default -> method.getDescription();
        };
    }

    private String buildMethodUsageGuide(List<String> enabledMethods) {
        List<String> methodsToInclude = (enabledMethods == null || enabledMethods.isEmpty())
                ? List.of("PEXELS", "NANO_BANANA", "MERMAID", "ICONIFY", "EMOJI_PACK", "SVG_DIAGRAM")
                : enabledMethods;

        StringBuilder sb = new StringBuilder();
        for (String method : methodsToInclude) {
            String guide = getMethodDetailedGuide(method);
            if (guide != null && !guide.isEmpty()) {
                sb.append(guide).append("\n");
            }
        }
        return sb.toString();
    }

    private String getMethodDetailedGuide(String method) {
        return switch (method) {
            case "PEXELS" -> "- PEXELS: provide concise English search keywords; leave prompt empty.";
            case "NANO_BANANA" -> "- NANO_BANANA: provide a detailed English image prompt; keywords can be empty.";
            case "MERMAID" -> "- MERMAID: write full Mermaid code in prompt; keywords can be empty.";
            case "ICONIFY" -> "- ICONIFY: provide concise icon keywords such as check, arrow, star, heart.";
            case "EMOJI_PACK" -> "- EMOJI_PACK: provide meme or emoji search keywords.";
            case "SVG_DIAGRAM" -> "- SVG_DIAGRAM: describe the conceptual diagram in prompt, focusing on structure and relationships.";
            default -> null;
        };
    }

    private List<ArticleState.ImageRequirement> validateAndFilterImageRequirements(
            List<ArticleState.ImageRequirement> requirements,
            List<String> enabledMethods) {
        if (requirements == null || requirements.isEmpty()) {
            return new ArrayList<>();
        }
        if (enabledMethods == null || enabledMethods.isEmpty()) {
            return requirements;
        }

        List<ArticleState.ImageRequirement> validatedRequirements = new ArrayList<>();
        for (ArticleState.ImageRequirement requirement : requirements) {
            String imageSource = requirement.getImageSource();
            if (enabledMethods.contains(imageSource)) {
                validatedRequirements.add(requirement);
            } else if (!enabledMethods.isEmpty()) {
                String fallbackSource = enabledMethods.get(0);
                requirement.setImageSource(fallbackSource);
                validatedRequirements.add(requirement);
                log.info("Image requirement source replaced, position={}, fallback={}",
                        requirement.getPosition(), fallbackSource);
            }
        }
        return validatedRequirements;
    }

    @AgentExecution(value = "ai_modify_outline", description = "AI修改大纲")
    public List<ArticleState.OutlineSection> aiModifyOutline(String mainTitle, String subTitle,
                                                             List<ArticleState.OutlineSection> currentOutline,
                                                             String modifySuggestion,
                                                             NodeExecutionMetadata metadata) {
        AgentProfile profile = resolveProfile(OUTLINE_PROFILE, "ai_modify_outline", false, true);
        String currentOutlineJson = GsonUtils.toJson(currentOutline);
        String promptContent = agentPromptSupport.getPromptContent(profile.getPromptKey(), profile.getPromptVersion())
                .replace("{mainTitle}", mainTitle)
                .replace("{subTitle}", subTitle)
                .replace("{currentOutline}", currentOutlineJson)
                .replace("{modifySuggestion}", modifySuggestion);

        ArticleState.OutlineResult outlineResult = articleStructuredOutputService.execute(
                promptContent,
                "modifiedOutline",
                prompt -> callLlm(prompt, profile),
                ArticleState.OutlineResult.class,
                this::isValidOutlineResult
        );
        log.info("AI outline modified, sectionsCount={}, model={}, promptKey={}, promptVersion={}",
                outlineResult.getSections().size(), profile.getModel(), profile.getPromptKey(), profile.getPromptVersion());
        return outlineResult.getSections();
    }

    public List<ArticleState.OutlineSection> aiModifyOutline(String mainTitle, String subTitle,
                                                             List<ArticleState.OutlineSection> currentOutline,
                                                             String modifySuggestion) {
        return getProxy().aiModifyOutline(mainTitle, subTitle, currentOutline, modifySuggestion,
                buildMetadataForProfile(OUTLINE_PROFILE, "ai_modify_outline", false, true));
    }

    private AgentProfile resolveProfile(String profileName,
                                        String defaultPromptKey,
                                        boolean defaultStreaming,
                                        boolean defaultStructuredOutput) {
        return agentPromptSupport.resolveProfile(profileName, defaultPromptKey, defaultStreaming, defaultStructuredOutput);
    }

    private NodeExecutionMetadata buildMetadataForProfile(String profileName,
                                                          String defaultPromptKey,
                                                          boolean defaultStreaming,
                                                          boolean defaultStructuredOutput) {
        return agentPromptSupport.toMetadata(resolveProfile(profileName, defaultPromptKey, defaultStreaming, defaultStructuredOutput));
    }

    private NodeExecutionMetadata buildImageRouterMetadata() {
        return NodeExecutionMetadata.builder()
                .promptKey("image_strategy_router")
                .promptVersion("rule-v1")
                .model("rule-engine")
                .temperature(0D)
                .maxTokens(0)
                .topP(0D)
                .build();
    }

    private boolean isValidTitleOptions(List<ArticleState.TitleOption> titleOptions) {
        if (titleOptions == null || titleOptions.size() < 3 || titleOptions.size() > 5) {
            return false;
        }
        return titleOptions.stream().allMatch(option -> option != null
                && isNotBlank(option.getMainTitle())
                && isNotBlank(option.getSubTitle()));
    }

    private boolean isValidOutlineResult(ArticleState.OutlineResult outlineResult) {
        if (outlineResult == null || outlineResult.getSections() == null || outlineResult.getSections().isEmpty()) {
            return false;
        }
        return outlineResult.getSections().stream().allMatch(section -> section != null
                && section.getSection() != null
                && isNotBlank(section.getTitle())
                && section.getPoints() != null
                && !section.getPoints().isEmpty());
    }

    private boolean isValidAgent4Result(ArticleState.Agent4Result result) {
        return result != null
                && isNotBlank(result.getContentWithPlaceholders())
                && result.getImageRequirements() != null;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private ArticleAgentService getProxy() {
        try {
            return (ArticleAgentService) AopContext.currentProxy();
        } catch (IllegalStateException e) {
            log.warn("Falling back to direct invocation because AOP proxy is unavailable: {}", e.getMessage());
            return this;
        }
    }
}
