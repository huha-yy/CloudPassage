package com.yupi.template.service.impl;

import com.google.gson.reflect.TypeToken;
import com.yupi.template.service.ArticleStructuredOutputService;
import com.yupi.template.utils.GsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Default structured output parser with light retry.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
@Service
@Slf4j
public class ArticleStructuredOutputServiceImpl implements ArticleStructuredOutputService {

    private static final int MAX_ATTEMPTS = 2;

    @Override
    public <T> T execute(String prompt, String resultName, Function<String, String> llmCall,
                         Class<T> clazz, Predicate<T> validator) {
        return executeInternal(prompt, resultName, llmCall, content -> GsonUtils.fromJson(content, clazz), validator);
    }

    @Override
    public <T> T execute(String prompt, String resultName, Function<String, String> llmCall,
                         TypeToken<T> typeToken, Predicate<T> validator) {
        return executeInternal(prompt, resultName, llmCall, content -> GsonUtils.fromJson(content, typeToken), validator);
    }

    private <T> T executeInternal(String prompt, String resultName, Function<String, String> llmCall,
                                  Function<String, T> parser, Predicate<T> validator) {
        String currentPrompt = prompt;
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String rawContent = llmCall.apply(currentPrompt);
                String normalizedContent = normalizeJson(rawContent);
                T result = parser.apply(normalizedContent);
                if (result == null) {
                    throw new RuntimeException(resultName + " is empty");
                }
                if (validator != null && !validator.test(result)) {
                    throw new RuntimeException(resultName + " validation failed");
                }
                return result;
            } catch (RuntimeException e) {
                lastException = new RuntimeException(resultName + " parse failed", e);
                log.warn("{} parse attempt {} failed: {}", resultName, attempt, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    currentPrompt = buildRetryPrompt(prompt, resultName);
                }
            }
        }
        throw Objects.requireNonNullElseGet(lastException,
                () -> new RuntimeException(resultName + " parse failed"));
    }

    private String buildRetryPrompt(String originalPrompt, String resultName) {
        return originalPrompt + "\n\nPrevious " + resultName
                + " output was not valid JSON. Return strict JSON only with no markdown, comments, or extra text.";
    }

    private String normalizeJson(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        int arrayStart = trimmed.indexOf('[');
        int objectStart = trimmed.indexOf('{');
        int start = chooseFirstJsonStart(arrayStart, objectStart);
        if (start < 0) {
            return trimmed;
        }
        char startChar = trimmed.charAt(start);
        int end = startChar == '[' ? trimmed.lastIndexOf(']') : trimmed.lastIndexOf('}');
        if (end > start) {
            return trimmed.substring(start, end + 1).trim();
        }
        return trimmed.substring(start).trim();
    }

    private int chooseFirstJsonStart(int arrayStart, int objectStart) {
        if (arrayStart >= 0 && objectStart >= 0) {
            return Math.min(arrayStart, objectStart);
        }
        if (arrayStart >= 0) {
            return arrayStart;
        }
        return objectStart;
    }
}
