package com.yupi.template.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yupi.template.config.PexelsConfig;
import com.yupi.template.model.dto.image.ImageData;
import com.yupi.template.model.dto.image.ImageRequest;
import com.yupi.template.model.enums.ImageMethodEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.yupi.template.constant.ArticleConstant.PEXELS_API_URL;
import static com.yupi.template.constant.ArticleConstant.PEXELS_ORIENTATION_LANDSCAPE;
import static com.yupi.template.constant.ArticleConstant.PEXELS_PER_PAGE;
import static com.yupi.template.constant.ArticleConstant.PICSUM_URL_TEMPLATE;

/**
 * Pexels 图片检索服务。
 */
@Service
@Slf4j
public class PexelsService implements ImageSearchService {

    private static final int MAX_RETRIES = 3;

    @Resource
    private PexelsConfig pexelsConfig;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(20))
            .callTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public String searchImage(String keywords) {
        if (pexelsConfig.getApiKey() == null || pexelsConfig.getApiKey().isBlank()) {
            log.warn("Pexels API Key 未配置，跳过 Pexels 图片检索");
            return null;
        }

        String url = buildSearchUrl(keywords);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", pexelsConfig.getApiKey())
                .addHeader("User-Agent", "AI-Passage-Creator/1.0")
                .build();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() >= 500 && attempt < MAX_RETRIES) {
                        log.warn("Pexels API 第 {} 次调用返回 {}, 准备重试", attempt, response.code());
                        sleepBeforeRetry(attempt);
                        continue;
                    }
                    log.error("Pexels API 调用失败: {}", response.code());
                    return null;
                }

                if (response.body() == null) {
                    log.warn("Pexels API 返回空响应体");
                    return null;
                }

                String responseBody = response.body().string();
                return extractImageUrl(responseBody, keywords);
            } catch (IOException e) {
                if (attempt >= MAX_RETRIES) {
                    log.error("Pexels API 调用异常", e);
                    return null;
                }
                log.warn("Pexels API 第 {} 次调用异常，准备重试: {}", attempt, e.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
        return null;
    }

    @Override
    public ImageData getImageData(ImageRequest request) {
        String imageUrl = getImage(request);
        return ImageData.fromUrl(imageUrl);
    }

    @Override
    public ImageMethodEnum getMethod() {
        return ImageMethodEnum.PEXELS;
    }

    @Override
    public String getFallbackImage(int position) {
        return String.format(PICSUM_URL_TEMPLATE, position);
    }

    private String buildSearchUrl(String keywords) {
        String encodedKeywords = URLEncoder.encode(
                keywords == null ? "" : keywords,
                StandardCharsets.UTF_8
        );
        return String.format(
                "%s?query=%s&per_page=%d&orientation=%s",
                PEXELS_API_URL,
                encodedKeywords,
                PEXELS_PER_PAGE,
                PEXELS_ORIENTATION_LANDSCAPE
        );
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private String extractImageUrl(String responseBody, String keywords) {
        JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray photos = jsonObject.getAsJsonArray("photos");

        if (photos == null || photos.isEmpty()) {
            log.warn("Pexels 未检索到图片: {}", keywords);
            return null;
        }

        JsonObject photo = photos.get(0).getAsJsonObject();
        JsonObject src = photo.getAsJsonObject("src");
        if (src == null || !src.has("large")) {
            log.warn("Pexels 响应中缺少 large 图片地址: {}", keywords);
            return null;
        }
        return src.get("large").getAsString();
    }
}
