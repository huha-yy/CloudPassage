package com.yupi.template.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.google.gson.reflect.TypeToken;
import com.yupi.template.annotation.AgentExecution;
import com.yupi.template.agent.agents.AgentPromptSupport;
import com.yupi.template.agent.config.AgentProfile;
import com.yupi.template.agent.tools.ImageGenerationTool;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.model.enums.ImageMethodEnum;
import com.yupi.template.model.enums.SseMessageTypeEnum;
import com.yupi.template.model.vo.ArticleMemoryContextVO;
import com.yupi.template.model.vo.ImageFallbackDecisionVO;
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
import java.util.stream.Collectors;
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
    private static final String CONTENT_REVIEW_PROFILE = "content-reviewer";
    private static final String IMAGE_PROFILE = "image-analyzer";

    @Resource
    private DashScopeChatModel chatModel;

    @Resource
    private ArticleStructuredOutputService articleStructuredOutputService;

    @Resource
    private AgentPromptSupport agentPromptSupport;

    @Resource
    private ImageStrategyRouterService imageStrategyRouterService;

    @Resource
    private ArticleMemoryService articleMemoryService;

    @Resource
    private ArticleNodeLogService articleNodeLogService;

    @Resource
    private ArticleNodeReplayService articleNodeReplayService;

    @Resource
    private ImageGenerationTool imageGenerationTool;

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

            proxy.agent3ReviewContent(state,
                    buildMetadataForProfile(CONTENT_REVIEW_PROFILE, "agent3_content_review", false, true));
            streamHandler.accept(SseMessageTypeEnum.AGENT3_REVIEW_COMPLETE.getValue());

            proxy.imageStrategyRouter(state, buildImageRouterMetadata());
            log.info("Phase 3 analyze image requirements started, taskId={}", state.getTaskId());
            proxy.agent4AnalyzeImageRequirements(state,
                    buildMetadataForProfile(IMAGE_PROFILE, "agent4_image", false, true));
            streamHandler.accept(SseMessageTypeEnum.AGENT4_COMPLETE.getValue());

            log.info("Phase 3 generate images started, taskId={}", state.getTaskId());
            proxy.agent5GenerateImages(state, streamHandler, buildImageGenerationMetadata());
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

    @AgentExecution(value = "agent1_generate_titles", description = "鐢熸垚鏍囬鏂规")
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

    @AgentExecution(value = "agent2_generate_outline", description = "鐢熸垚鏂囩珷澶х翰")
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

    @AgentExecution(value = "agent3_generate_content", description = "鐢熸垚鏂囩珷姝ｆ枃")
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

    @AgentExecution(value = "agent3_review_content", description = "璇勫姝ｆ枃骞舵渶灏忎慨璁?)
    public void agent3ReviewContent(ArticleState state, NodeExecutionMetadata metadata) {
        AgentProfile profile = resolveProfile(CONTENT_REVIEW_PROFILE, "agent3_content_review", false, true);
        String outlineText = GsonUtils.toJson(state.getOutline().getSections());
        ArticleMemoryContextVO memoryContext = articleMemoryService.buildCreationMemoryContext(state.getTaskId(), null);
        String promptContent = agentPromptSupport.getPromptContent(profile.getPromptKey(), profile.getPromptVersion())
                .replace("{mainTitle}", state.getTitle().getMainTitle())
                .replace("{subTitle}", state.getTitle().getSubTitle())
                .replace("{outline}", outlineText)
                .replace("{content}", state.getContent())
                + buildReviewMemoryHints(memoryContext);
        applyReviewMemoryContextMetadata(metadata, memoryContext);

        ArticleState.ContentReviewResult reviewResult = articleStructuredOutputService.execute(
                promptContent,
                "contentReview",
                prompt -> callLlm(prompt, profile),
                ArticleState.ContentReviewResult.class,
                this::isValidContentReviewResult
        );
        if (reviewResult.getRevisedContent() != null && !reviewResult.getRevisedContent().isBlank()) {
            state.setContent(reviewResult.getRevisedContent());
        }
        state.setContentReview(reviewResult);
        applyContentReviewMetadata(metadata, reviewResult, memoryContext);
        log.info("Content reviewed, needsRevision={}, issues={}, model={}, promptKey={}, promptVersion={}",
                reviewResult.getNeedsRevision(),
                reviewResult.getIssues() == null ? 0 : reviewResult.getIssues().size(),
                profile.getModel(), profile.getPromptKey(), profile.getPromptVersion());
    }

    public void imageStrategyRouter(ArticleState state, NodeExecutionMetadata metadata) {
        ArticleMemoryContextVO memoryContext = articleMemoryService.buildCreationMemoryContext(state.getTaskId(), null);
        applyImageMemoryContextMetadata(metadata, memoryContext, "router");
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

    @AgentExecution(value = "agent4_analyze_image_requirements", description = "鍒嗘瀽閰嶅浘闇€姹?)
    public void agent4AnalyzeImageRequirements(ArticleState state, NodeExecutionMetadata metadata) {
        AgentProfile profile = resolveProfile(IMAGE_PROFILE, "agent4_image", false, true);
        ArticleMemoryContextVO memoryContext = articleMemoryService.buildCreationMemoryContext(state.getTaskId(), null);
        applyImageMemoryContextMetadata(metadata, memoryContext, "analyzer");
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
        applyImageAnalyzerSummary(metadata, validatedRequirements, state.getEnabledImageMethods());
        log.info("Image requirements analyzed, rawCount={}, validatedCount={}, model={}, promptKey={}, promptVersion={}",
                agent4Result.getImageRequirements().size(), validatedRequirements.size(),
                profile.getModel(), profile.getPromptKey(), profile.getPromptVersion());
    }

    @AgentExecution(value = "agent5_generate_images", description = "鐢熸垚閰嶅浘")
    public void agent5GenerateImages(ArticleState state, Consumer<String> streamHandler, NodeExecutionMetadata metadata) {
        ArticleMemoryContextVO memoryContext = articleMemoryService.buildCreationMemoryContext(state.getTaskId(), null);
        applyImageMemoryContextMetadata(metadata, memoryContext, "generation");
        List<ArticleState.ImageResult> imageResults = new ArrayList<>();
        List<ArticleState.ImageFallbackRecord> fallbackRecords = new ArrayList<>();
        for (ArticleState.ImageRequirement requirement : state.getImageRequirements()) {
            String imageSource = requirement.getImageSource();
            log.info("Generate image, position={}, source={}, keywords={}",
                    requirement.getPosition(), imageSource, requirement.getKeywords());

            ImageGenerationTool.ImageGenerationResult result = buildImageWithFallback(requirement, state.getEnabledImageMethods());
            ArticleState.ImageResult imageResult = buildImageResult(requirement, result);
            imageResults.add(imageResult);
            fallbackRecords.add(buildFallbackRecord(requirement, result));
            streamHandler.accept(SseMessageTypeEnum.IMAGE_COMPLETE.getStreamingPrefix() + GsonUtils.toJson(imageResult));
            log.info("Image generated, position={}, method={}, requestedMethod={}, fallbackApplied={}, url={}",
                    requirement.getPosition(), result.getMethod(), result.getRequestedMethod(),
                    result.getFallbackApplied(), result.getUrl());
        }
        state.setImages(imageResults);
        state.setImageFallbackRecords(fallbackRecords);
        applyImageGenerationSummary(metadata, imageResults, fallbackRecords);
        recordImageFallbackObservability(state, fallbackRecords, metadata);
        log.info("All images generated, count={}", imageResults.size());
    }

    @AgentExecution(value = "agent6_merge_content", description = "鍥炬枃鍚堟垚")
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
                                                      ImageGenerationTool.ImageGenerationResult result) {
        ArticleState.ImageResult imageResult = new ArticleState.ImageResult();
        imageResult.setPosition(requirement.getPosition());
        imageResult.setUrl(result.getUrl());
        imageResult.setMethod(result.getMethod());
        imageResult.setKeywords(requirement.getKeywords());
        imageResult.setSectionTitle(requirement.getSectionTitle());
        imageResult.setDescription(requirement.getType());
        imageResult.setPlaceholderId(requirement.getPlaceholderId());
        imageResult.setRequestedMethod(result.getRequestedMethod());
        imageResult.setFallbackApplied(result.getFallbackApplied());
        imageResult.setFallbackReason(result.getFallbackReason());
        imageResult.setAttemptedMethods(result.getAttemptedMethods());
        return imageResult;
    }

    private ImageGenerationTool.ImageGenerationResult buildImageWithFallback(ArticleState.ImageRequirement requirement,
                                                                             List<String> enabledMethods) {
        return imageGenerationTool.generateImageDirect(
                requirement.getImageSource(),
                requirement.getKeywords(),
                requirement.getPrompt(),
                requirement.getPosition(),
                requirement.getType(),
                requirement.getSectionTitle(),
                requirement.getPlaceholderId(),
                enabledMethods
        );
    }

    private ArticleState.ImageFallbackRecord buildFallbackRecord(ArticleState.ImageRequirement requirement,
                                                                 ImageGenerationTool.ImageGenerationResult result) {
        ArticleState.ImageFallbackRecord record = new ArticleState.ImageFallbackRecord();
        record.setPosition(requirement.getPosition());
        record.setRequestedMethod(result.getRequestedMethod());
        record.setFinalMethod(result.getMethod());
        record.setFallbackApplied(result.getFallbackApplied());
        record.setFallbackReason(result.getFallbackReason());
        record.setAttemptedMethods(result.getAttemptedMethods());
        record.setSectionTitle(requirement.getSectionTitle());
        record.setPlaceholderId(requirement.getPlaceholderId());
        return record;
    }

    private void recordImageFallbackObservability(ArticleState state,
                                                  List<ArticleState.ImageFallbackRecord> fallbackRecords,
                                                  NodeExecutionMetadata metadata) {
        if (state == null || state.getTaskId() == null || fallbackRecords == null || fallbackRecords.isEmpty()) {
            return;
        }
        List<ImageFallbackDecisionVO> decisions = fallbackRecords.stream()
                .map(record -> ImageFallbackDecisionVO.builder()
                        .requestedMethod(record.getRequestedMethod())
                        .finalMethod(record.getFinalMethod())
                        .fallbackApplied(record.getFallbackApplied())
                        .fallbackReason(record.getFallbackReason())
                        .attemptedMethods(record.getAttemptedMethods())
                        .build())
                .collect(Collectors.toList());
        articleMemoryService.recordImageFallbackDecision(state.getTaskId(), state, decisions);
        long fallbackCount = fallbackRecords.stream().filter(record -> Boolean.TRUE.equals(record.getFallbackApplied())).count();
        if (fallbackCount <= 0) {
            return;
        }
        String summary = fallbackRecords.stream()
                .filter(record -> Boolean.TRUE.equals(record.getFallbackApplied()))
                .limit(3)
                .map(record -> record.getRequestedMethod() + "->" + record.getFinalMethod())
                .collect(Collectors.joining(", "));
        if (metadata != null) {
            metadata.setFallbackSource("image_fallback_router");
            metadata.setFallbackReason("image_generation_failed");
            metadata.setFallbackSummary("count=" + fallbackCount + ", routes=" + summary);
        }
        articleNodeLogService.info(state.getTaskId(), "CONTENT_GENERATING", "agent5_generate_images",
                "鍥剧墖闄嶇骇宸茶Е鍙戯細" + summary, metadata);
    }

    private void applyImageMemoryContextMetadata(NodeExecutionMetadata metadata,
                                                 ArticleMemoryContextVO memoryContext,
                                                 String scenario) {
        if (metadata == null || memoryContext == null) {
            return;
        }
        metadata.setMemoryContextSummary(buildImageMemoryContextSummary(memoryContext, scenario));
        metadata.setMemoryContextSnapshot(buildImageMemoryContextSnapshot(memoryContext, scenario));
    }

    private String buildImageMemoryContextSummary(ArticleMemoryContextVO memoryContext, String scenario) {
        if (memoryContext == null) {
            return null;
        }
        int preferredCount = memoryContext.getPreferredImageMethods() == null ? 0 : memoryContext.getPreferredImageMethods().size();
        int avoidCount = memoryContext.getAvoidImageMethods() == null ? 0 : memoryContext.getAvoidImageMethods().size();
        int successCount = memoryContext.getRecalledSuccessCases() == null ? 0 : memoryContext.getRecalledSuccessCases().size();
        int failureCount = memoryContext.getRecalledFailureCases() == null ? 0 : memoryContext.getRecalledFailureCases().size();
        return "scenario=" + scenario
                + ", preferredMethods=" + preferredCount
                + ", avoidMethods=" + avoidCount
                + ", successCases=" + successCount
                + ", failureCases=" + failureCount;
    }

    private String buildImageMemoryContextSnapshot(ArticleMemoryContextVO memoryContext, String scenario) {
        if (memoryContext == null) {
            return null;
        }
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("scenario", scenario);
        snapshot.put("preferredImageMethods", memoryContext.getPreferredImageMethods());
        snapshot.put("avoidImageMethods", memoryContext.getAvoidImageMethods());
        snapshot.put("failureHints", memoryContext.getFailureHints());
        snapshot.put("successCases", summarizeMemoryCases(memoryContext.getRecalledSuccessCases()));
        snapshot.put("failureCases", summarizeMemoryCases(memoryContext.getRecalledFailureCases()));
        return GsonUtils.toJson(snapshot);
    }

    private void applyImageGenerationSummary(NodeExecutionMetadata metadata,
                                             List<ArticleState.ImageResult> imageResults,
                                             List<ArticleState.ImageFallbackRecord> fallbackRecords) {
        if (metadata == null) {
            return;
        }
        int imageCount = imageResults == null ? 0 : imageResults.size();
        long fallbackCount = fallbackRecords == null ? 0 : fallbackRecords.stream()
                .filter(Objects::nonNull)
                .filter(item -> Boolean.TRUE.equals(item.getFallbackApplied()))
                .count();
        String routeSummary = fallbackRecords == null ? null : fallbackRecords.stream()
                .filter(Objects::nonNull)
                .limit(3)
                .map(item -> firstNonBlank(item.getRequestedMethod(), "--") + "->" + firstNonBlank(item.getFinalMethod(), "--"))
                .collect(Collectors.joining(", "));
        metadata.setDecisionSummary("generated=" + imageCount
                + ", fallbackCount=" + fallbackCount
                + (routeSummary == null || routeSummary.isBlank() ? "" : ", routes=" + routeSummary));
    }

    private void applyImageAnalyzerSummary(NodeExecutionMetadata metadata,
                                           List<ArticleState.ImageRequirement> requirements,
                                           List<String> enabledMethods) {
        if (metadata == null) {
            return;
        }
        int requirementCount = requirements == null ? 0 : requirements.size();
        int enabledMethodCount = enabledMethods == null ? 0 : enabledMethods.size();
        String firstSource = requirements == null ? null : requirements.stream()
                .filter(Objects::nonNull)
                .map(ArticleState.ImageRequirement::getImageSource)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        metadata.setDecisionSummary("requirements=" + requirementCount
                + ", enabledMethods=" + enabledMethodCount
                + (firstSource == null ? "" : ", firstSource=" + firstSource));
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

    @AgentExecution(value = "ai_modify_outline", description = "AI淇敼澶х翰")
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

    private NodeExecutionMetadata buildImageGenerationMetadata() {
        return NodeExecutionMetadata.builder()
                .promptKey("image_fallback_router")
                .promptVersion("rule-v1")
                .model("tool-router")
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

    private boolean isValidContentReviewResult(ArticleState.ContentReviewResult result) {
        return result != null && isNotBlank(result.getRevisedContent());
    }

    private void applyContentReviewMetadata(NodeExecutionMetadata metadata,
                                            ArticleState.ContentReviewResult reviewResult,
                                            ArticleMemoryContextVO memoryContext) {
        if (metadata == null || reviewResult == null) {
            return;
        }
        boolean memoryUsed = memoryContext != null
                && ((memoryContext.getQualityHints() != null && !memoryContext.getQualityHints().isEmpty())
                || (memoryContext.getFailureHints() != null && !memoryContext.getFailureHints().isEmpty()));
        metadata.setDecisionSource(Boolean.TRUE.equals(reviewResult.getNeedsRevision())
                ? (memoryUsed ? "content_reviewer_with_memory" : "content_reviewer")
                : (memoryUsed ? "content_reviewer_keep_with_memory" : "content_reviewer_keep"));
        metadata.setDecisionReason(appendMemoryReason(joinReviewSignals(reviewResult), memoryContext));
        metadata.setDecisionSummary(buildReviewDecisionSummary(reviewResult, memoryContext));
    }

    private String joinReviewSignals(ArticleState.ContentReviewResult reviewResult) {
        if (reviewResult == null) {
            return null;
        }
        List<String> signals = new ArrayList<>();
        if (reviewResult.getIssues() != null) {
            signals.addAll(reviewResult.getIssues());
        }
        if (reviewResult.getQualitySignals() != null) {
            signals.addAll(reviewResult.getQualitySignals());
        }
        return signals.isEmpty() ? null : String.join("|", signals);
    }

    private String buildReviewMemoryHints(ArticleMemoryContextVO memoryContext) {
        if (memoryContext == null) {
            return "";
        }
        List<String> qualityHints = memoryContext.getQualityHints();
        List<String> failureHints = memoryContext.getFailureHints();
        List<ArticleMemoryContextVO.RecalledMemoryCaseVO> successCases = memoryContext.getRecalledSuccessCases();
        List<ArticleMemoryContextVO.RecalledMemoryCaseVO> failureCases = memoryContext.getRecalledFailureCases();
        if ((qualityHints == null || qualityHints.isEmpty())
                && (failureHints == null || failureHints.isEmpty())
                && (successCases == null || successCases.isEmpty())
                && (failureCases == null || failureCases.isEmpty())) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\n\n闀挎湡璁板繂鎻愮ず锛歕n");
        if (qualityHints != null && !qualityHints.isEmpty()) {
            builder.append("- 鍘嗗彶鎴愬姛璐ㄩ噺淇″彿锛?).append(String.join(", ", qualityHints)).append("\n");
        }
        if (failureHints != null && !failureHints.isEmpty()) {
            builder.append("- 鍘嗗彶澶辫触鎻愰啋锛?).append(String.join(", ", failureHints)).append("\n");
        }
        if (successCases != null && !successCases.isEmpty()) {
            builder.append("- 鐩镐技鎴愬姛妗堜緥锛?);
            builder.append(successCases.stream()
                    .limit(2)
                    .map(item -> firstNonBlank(item.getSummary(), item.getTopic()))
                    .collect(Collectors.joining("锛?)));
            builder.append("\n");
        }
        if (failureCases != null && !failureCases.isEmpty()) {
            builder.append("- 鐩镐技澶辫触妗堜緥锛?);
            builder.append(failureCases.stream()
                    .limit(2)
                    .map(item -> firstNonBlank(item.getFailedNode(), firstNonBlank(item.getSummary(), item.getTopic())))
                    .collect(Collectors.joining("锛?)));
            builder.append("\n");
        }
        builder.append("璇峰皢杩欎簺鎻愮ず浣滀负杞婚噺绾︽潫锛屽彧鍦ㄥ繀瑕佹椂鍋氭渶灏忎慨璁紝涓嶈鍥犱负鍘嗗彶鎻愮ず鑰岄噸鍐欐暣绡囨枃绔犮€?);
        return builder.toString();
    }

    private String appendMemoryReason(String baseReason, ArticleMemoryContextVO memoryContext) {
        if (memoryContext == null) {
            return baseReason;
        }
        List<String> memorySignals = new ArrayList<>();
        if (memoryContext.getQualityHints() != null && !memoryContext.getQualityHints().isEmpty()) {
            memorySignals.add("qualityHints=" + memoryContext.getQualityHints().size());
        }
        if (memoryContext.getFailureHints() != null && !memoryContext.getFailureHints().isEmpty()) {
            memorySignals.add("failureHints=" + memoryContext.getFailureHints().size());
        }
        if (memorySignals.isEmpty()) {
            return baseReason;
        }
        String memoryReason = String.join("|", memorySignals);
        if (baseReason == null || baseReason.isBlank()) {
            return memoryReason;
        }
        return baseReason + "|" + memoryReason;
    }

    private String buildReviewDecisionSummary(ArticleState.ContentReviewResult reviewResult,
                                              ArticleMemoryContextVO memoryContext) {
        String reviewSummary = reviewResult == null ? null : reviewResult.getSummary();
        if (memoryContext == null) {
            return reviewSummary;
        }
        int qualityCount = memoryContext.getQualityHints() == null ? 0 : memoryContext.getQualityHints().size();
        int failureCount = memoryContext.getFailureHints() == null ? 0 : memoryContext.getFailureHints().size();
        String memorySummary = "memoryHints=" + qualityCount + "/" + failureCount;
        if (reviewSummary == null || reviewSummary.isBlank()) {
            return memorySummary;
        }
        return reviewSummary + " | " + memorySummary;
    }

    private void applyReviewMemoryContextMetadata(NodeExecutionMetadata metadata,
                                                  ArticleMemoryContextVO memoryContext) {
        if (metadata == null || memoryContext == null) {
            return;
        }
        metadata.setMemoryContextSummary(buildMemoryContextSummary(memoryContext));
        metadata.setMemoryContextSnapshot(buildMemoryContextSnapshot(memoryContext));
    }

    private String buildMemoryContextSummary(ArticleMemoryContextVO memoryContext) {
        if (memoryContext == null) {
            return null;
        }
        int qualityCount = memoryContext.getQualityHints() == null ? 0 : memoryContext.getQualityHints().size();
        int failureCount = memoryContext.getFailureHints() == null ? 0 : memoryContext.getFailureHints().size();
        int successCount = memoryContext.getRecalledSuccessCases() == null ? 0 : memoryContext.getRecalledSuccessCases().size();
        int failedCount = memoryContext.getRecalledFailureCases() == null ? 0 : memoryContext.getRecalledFailureCases().size();
        return "qualityHints=" + qualityCount
                + ", failureHints=" + failureCount
                + ", successCases=" + successCount
                + ", failureCases=" + failedCount;
    }

    private String buildMemoryContextSnapshot(ArticleMemoryContextVO memoryContext) {
        if (memoryContext == null) {
            return null;
        }
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("preferredImageMethods", memoryContext.getPreferredImageMethods());
        snapshot.put("avoidImageMethods", memoryContext.getAvoidImageMethods());
        snapshot.put("qualityHints", memoryContext.getQualityHints());
        snapshot.put("failureHints", memoryContext.getFailureHints());
        snapshot.put("successCases", summarizeMemoryCases(memoryContext.getRecalledSuccessCases()));
        snapshot.put("failureCases", summarizeMemoryCases(memoryContext.getRecalledFailureCases()));
        return GsonUtils.toJson(snapshot);
    }

    private List<java.util.Map<String, Object>> summarizeMemoryCases(List<ArticleMemoryContextVO.RecalledMemoryCaseVO> cases) {
        if (cases == null || cases.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<java.util.Map<String, Object>> result = new ArrayList<>();
        for (ArticleMemoryContextVO.RecalledMemoryCaseVO item : cases.stream().limit(3).collect(Collectors.toList())) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("taskId", item.getTaskId());
            map.put("style", item.getStyle());
            map.put("summary", item.getSummary());
            map.put("failedNode", item.getFailedNode());
            map.put("imageMethods", item.getImageMethods());
            map.put("score", item.getScore());
            result.add(map);
        }
        return result;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
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

