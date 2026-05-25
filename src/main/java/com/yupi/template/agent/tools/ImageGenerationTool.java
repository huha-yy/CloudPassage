package com.yupi.template.agent.tools;

import com.yupi.template.model.dto.image.ImageRequest;
import com.yupi.template.model.enums.ImageMethodEnum;
import com.yupi.template.model.vo.ImageFallbackDecisionVO;
import com.yupi.template.service.ImageFallbackRouterService;
import com.yupi.template.service.CosService;
import com.yupi.template.service.ImageServiceStrategy;
import com.yupi.template.utils.GsonUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片生成工具
 * 封装 ImageServiceStrategy，供 Agent 调用
 *
 * @author AI Passage Creator
 */
@Component
@Slf4j
public class ImageGenerationTool {

    @Resource
    private ImageServiceStrategy imageServiceStrategy;

    @Resource
    private CosService cosService;

    @Resource
    private ImageFallbackRouterService imageFallbackRouterService;

    /**
     * 根据需求生成或搜索图片
     *
     * @param imageSource 图片来源类型（PEXELS/NANO_BANANA/MERMAID/ICONIFY/EMOJI_PACK/SVG_DIAGRAM）
     * @param keywords    搜索关键词（用于图库检索）
     * @param prompt      AI 生图提示词或 Mermaid/SVG 代码
     * @param position    图片位置序号
     * @param type        图片类型（cover/section）
     * @param sectionTitle 章节标题
     * @return 图片生成结果的 JSON 字符串
     */
    @Tool(description = "根据需求生成或搜索图片。支持多种图片来源：PEXELS（真实照片）、NANO_BANANA（AI生图）、MERMAID（流程图）、ICONIFY（图标）、EMOJI_PACK（表情包）、SVG_DIAGRAM（概念示意图）")
    public String generateImage(
            @ToolParam(description = "图片来源类型：PEXELS/NANO_BANANA/MERMAID/ICONIFY/EMOJI_PACK/SVG_DIAGRAM") String imageSource,
            @ToolParam(description = "搜索关键词（用于 PEXELS/ICONIFY/EMOJI_PACK）") String keywords,
            @ToolParam(description = "AI 生图提示词或图表代码（用于 NANO_BANANA/MERMAID/SVG_DIAGRAM）") String prompt,
            @ToolParam(description = "图片位置序号，封面为1，其他为章节顺序") Integer position,
            @ToolParam(description = "图片类型：cover（封面）或 section（章节配图）") String type,
            @ToolParam(description = "对应的章节标题，封面图留空") String sectionTitle) {
        
        log.info("ImageGenerationTool 开始执行: imageSource={}, position={}, type={}", 
                imageSource, position, type);
        
        try {
            // 构建图片请求
            ImageRequest imageRequest = ImageRequest.builder()
                    .keywords(keywords)
                    .prompt(prompt)
                    .position(position)
                    .type(type)
                    .build();
            
            // 使用统一上传到 COS 的方法
            ImageServiceStrategy.ImageResult result = imageServiceStrategy.getImageAndUpload(imageSource, imageRequest);
            String cosUrl = result.getUrl();
            ImageMethodEnum method = result.getMethod();
            
            // 构建返回结果
            ImageGenerationResult generationResult = new ImageGenerationResult();
            generationResult.setPosition(position);
            generationResult.setUrl(cosUrl);
            generationResult.setMethod(method.getValue());
            generationResult.setKeywords(keywords);
            generationResult.setSectionTitle(sectionTitle);
            generationResult.setDescription(type);
            generationResult.setSuccess(true);
            
            log.info("ImageGenerationTool 执行成功: position={}, method={}, cosUrl={}", 
                    position, method.getValue(), cosUrl);
            
            return GsonUtils.toJson(generationResult);
            
        } catch (Exception e) {
            log.error("ImageGenerationTool 执行失败: imageSource={}, position={}", imageSource, position, e);
            
            // 返回失败结果
            ImageGenerationResult failResult = new ImageGenerationResult();
            failResult.setPosition(position);
            failResult.setSuccess(false);
            failResult.setError(e.getMessage());
            failResult.setSectionTitle(sectionTitle);
            
            return GsonUtils.toJson(failResult);
        }
    }

    /**
     * 直接生成图片（不通过 Agent，供内部调用）
     *
     * @param imageSource   图片来源
     * @param keywords      关键词
     * @param prompt        提示词
     * @param position      位置
     * @param type          类型
     * @param sectionTitle  章节标题
     * @param placeholderId 占位符ID
     * @return 图片生成结果
     */
    public ImageGenerationResult generateImageDirect(String imageSource, String keywords, String prompt,
                                                     Integer position, String type, String sectionTitle,
                                                     String placeholderId) {
        return generateImageDirect(imageSource, keywords, prompt, position, type, sectionTitle, placeholderId, null);
    }

    public ImageGenerationResult generateImageDirect(String imageSource, String keywords, String prompt,
                                                     Integer position, String type, String sectionTitle,
                                                     String placeholderId, List<String> enabledMethods) {
        ImageRequest imageRequest = ImageRequest.builder()
                .keywords(keywords)
                .prompt(prompt)
                .position(position)
                .type(type)
                .build();
        List<String> attemptedMethods = new ArrayList<>();
        String requestedMethod = imageSource;

        try {
            ImageFallbackDecisionVO fallbackDecision = imageFallbackRouterService.route(
                    buildRequirement(imageSource, keywords, prompt, position, type, sectionTitle, placeholderId),
                    enabledMethods
            );
            List<String> routeMethods = fallbackDecision == null || fallbackDecision.getAttemptedMethods() == null
                    || fallbackDecision.getAttemptedMethods().isEmpty()
                    ? List.of(imageSource)
                    : fallbackDecision.getAttemptedMethods();

            Exception lastException = null;
            for (String candidateMethod : routeMethods) {
                attemptedMethods.add(candidateMethod);
                try {
                    ImageServiceStrategy.ImageResult result = imageServiceStrategy.tryGetImageAndUpload(candidateMethod, imageRequest);
                    if (result != null && result.isSuccess()) {
                        return buildSuccessResult(position, keywords, type, sectionTitle, placeholderId, requestedMethod,
                                attemptedMethods, fallbackDecision, result);
                    }
                } catch (Exception e) {
                    lastException = e;
                    log.warn("图片候选方法执行失败: requestedMethod={}, candidateMethod={}, position={}, error={}",
                            requestedMethod, candidateMethod, position, e.getMessage());
                }
            }

            ImageGenerationResult failResult = new ImageGenerationResult();
            failResult.setPosition(position);
            failResult.setSuccess(false);
            failResult.setError(lastException == null ? "image_generation_failed" : lastException.getMessage());
            failResult.setSectionTitle(sectionTitle);
            failResult.setPlaceholderId(placeholderId);
            failResult.setRequestedMethod(requestedMethod);
            failResult.setAttemptedMethods(attemptedMethods);
            failResult.setFallbackApplied(attemptedMethods.size() > 1);
            failResult.setFallbackReason(fallbackDecision == null ? "fallback_route_exhausted" : fallbackDecision.getFallbackReason());
            return applyTerminalFallback(imageRequest, failResult);
        } catch (Exception e) {
            log.error("图片生成失败: imageSource={}, position={}", imageSource, position, e);

            ImageGenerationResult failResult = new ImageGenerationResult();
            failResult.setPosition(position);
            failResult.setSuccess(false);
            failResult.setError(e.getMessage());
            failResult.setSectionTitle(sectionTitle);
            failResult.setPlaceholderId(placeholderId);
            failResult.setRequestedMethod(requestedMethod);
            failResult.setAttemptedMethods(attemptedMethods);
            failResult.setFallbackApplied(attemptedMethods.size() > 1);
            failResult.setFallbackReason("image_generation_exception");
            return applyTerminalFallback(imageRequest, failResult);
        }
    }

    private ImageGenerationResult buildSuccessResult(Integer position, String keywords, String type, String sectionTitle,
                                                     String placeholderId, String requestedMethod,
                                                     List<String> attemptedMethods,
                                                     ImageFallbackDecisionVO fallbackDecision,
                                                     ImageServiceStrategy.ImageResult result) {
        String cosUrl = result.getUrl();
        ImageMethodEnum method = result.getMethod();

        ImageGenerationResult generationResult = new ImageGenerationResult();
        generationResult.setPosition(position);
        generationResult.setUrl(cosUrl);
        generationResult.setMethod(method.getValue());
        generationResult.setKeywords(keywords);
        generationResult.setSectionTitle(sectionTitle);
        generationResult.setDescription(type);
        generationResult.setPlaceholderId(placeholderId);
        generationResult.setSuccess(true);
        generationResult.setRequestedMethod(requestedMethod);
        generationResult.setAttemptedMethods(attemptedMethods);
        generationResult.setFallbackApplied(attemptedMethods.size() > 1);
        generationResult.setFallbackReason(fallbackDecision == null ? null : fallbackDecision.getFallbackReason());
        return generationResult;
    }

    private ImageGenerationResult applyTerminalFallback(ImageRequest imageRequest, ImageGenerationResult failedResult) {
        ImageServiceStrategy.ImageResult finalFallback = imageServiceStrategy.getImageAndUpload(
                ImageMethodEnum.getFallbackMethod().getValue(),
                imageRequest
        );
        if (finalFallback != null && finalFallback.isSuccess()) {
            failedResult.setUrl(finalFallback.getUrl());
            failedResult.setMethod(finalFallback.getMethod().getValue());
            failedResult.setSuccess(true);
            if (failedResult.getAttemptedMethods() == null) {
                failedResult.setAttemptedMethods(new ArrayList<>());
            }
            if (!failedResult.getAttemptedMethods().contains(finalFallback.getMethod().getValue())) {
                failedResult.getAttemptedMethods().add(finalFallback.getMethod().getValue());
            }
            failedResult.setFallbackApplied(true);
        }
        return failedResult;
    }

    private com.yupi.template.model.dto.article.ArticleState.ImageRequirement buildRequirement(String imageSource,
                                                                                               String keywords,
                                                                                               String prompt,
                                                                                               Integer position,
                                                                                               String type,
                                                                                               String sectionTitle,
                                                                                               String placeholderId) {
        com.yupi.template.model.dto.article.ArticleState.ImageRequirement requirement =
                new com.yupi.template.model.dto.article.ArticleState.ImageRequirement();
        requirement.setImageSource(imageSource);
        requirement.setKeywords(keywords);
        requirement.setPrompt(prompt);
        requirement.setPosition(position);
        requirement.setType(type);
        requirement.setSectionTitle(sectionTitle);
        requirement.setPlaceholderId(placeholderId);
        return requirement;
    }

    /**
     * 图片生成结果
     */
    @Data
    public static class ImageGenerationResult implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private Integer position;
        private String url;
        private String method;
        private String keywords;
        private String sectionTitle;
        private String description;
        private String placeholderId;
        private boolean success;
        private String error;
        private String requestedMethod;
        private Boolean fallbackApplied;
        private String fallbackReason;
        private List<String> attemptedMethods;
    }
}
