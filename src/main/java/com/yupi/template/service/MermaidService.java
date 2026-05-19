package com.yupi.template.service;

import com.yupi.template.config.MermaidConfig;
import com.yupi.template.model.dto.image.ImageData;
import com.yupi.template.model.dto.image.ImageRequest;
import com.yupi.template.model.enums.ImageMethodEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.yupi.template.constant.ArticleConstant.PICSUM_URL_TEMPLATE;

/**
 * Mermaid 图表服务，使用 mermaid.ink 渲染为可下载的图片 URL。
 */
@Service
@Slf4j
public class MermaidService implements ImageSearchService {

    private static final String MERMAID_INK_BASE_URL = "https://mermaid.ink";

    @Resource
    private MermaidConfig mermaidConfig;

    @Override
    public String searchImage(String keywords) {
        return buildMermaidInkUrl(keywords);
    }

    @Override
    public ImageData getImageData(ImageRequest request) {
        String mermaidCode = request.getEffectiveParam(true);
        String imageUrl = buildMermaidInkUrl(mermaidCode);
        return ImageData.fromUrl(imageUrl);
    }

    private String buildMermaidInkUrl(String mermaidCode) {
        if (mermaidCode == null || mermaidCode.trim().isEmpty()) {
            log.warn("Mermaid 代码为空");
            return null;
        }

        byte[] mermaidBytes = mermaidCode.getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(mermaidBytes);
        String format = resolveOutputFormat();
        String imageUrl = String.format("%s/%s/%s", MERMAID_INK_BASE_URL, format, encoded);
        log.info("Mermaid 图表已转换为远程渲染地址: {}", imageUrl);
        return imageUrl;
    }

    @Override
    public ImageMethodEnum getMethod() {
        return ImageMethodEnum.MERMAID;
    }

    @Override
    public String getFallbackImage(int position) {
        return String.format(PICSUM_URL_TEMPLATE, position);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private String resolveOutputFormat() {
        String format = mermaidConfig.getOutputFormat();
        return "png".equalsIgnoreCase(format) ? "img" : "svg";
    }
}
