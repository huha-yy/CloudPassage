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

    private static final int MAX_ATTEMPTS = 3;

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
            String rawContent = null;
            try {
                rawContent = llmCall.apply(currentPrompt);
                String normalizedContent = normalizeJson(rawContent);
                T result = parseStructuredResult(parser, normalizedContent);
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
                    currentPrompt = buildRetryPrompt(prompt, resultName, rawContent, e.getMessage());
                }
            }
        }
        throw Objects.requireNonNullElseGet(lastException,
                () -> new RuntimeException(resultName + " parse failed"));
    }

    private <T> T parseStructuredResult(Function<String, T> parser, String normalizedContent) {
        RuntimeException firstException = null;
        for (String candidate : buildParseCandidates(normalizedContent)) {
            try {
                return parser.apply(candidate);
            } catch (RuntimeException e) {
                if (firstException == null) {
                    firstException = e;
                }
            }
        }
        throw firstException == null ? new RuntimeException("Structured output parsing failed") : firstException;
    }

    private String buildRetryPrompt(String originalPrompt, String resultName, String rawContent, String errorMessage) {
        return originalPrompt + "\n\nPrevious " + resultName
                + " output was not valid JSON.\n"
                + "Parse error: " + safeText(errorMessage) + "\n"
                + "Please repair the previous output into strict valid JSON only.\n"
                + "- Keep the original meaning and fields\n"
                + "- Escape all line breaks, tabs, quotes, and backslashes inside JSON strings correctly\n"
                + "- Do not add markdown fences, comments, or explanation text\n"
                + "- Return only one complete JSON object or array\n"
                + "Previous output:\n"
                + "<invalid_json>\n"
                + safeText(rawContent)
                + "\n</invalid_json>";
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
        String extracted = extractBalancedJson(trimmed);
        return extracted == null ? trimmed : extracted;
    }

    private String extractBalancedJson(String text) {
        int arrayStart = text.indexOf('[');
        int objectStart = text.indexOf('{');
        int start = chooseFirstJsonStart(arrayStart, objectStart);
        if (start < 0) {
            return null;
        }
        char opening = text.charAt(start);
        char closing = opening == '[' ? ']' : '}';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == opening) {
                depth++;
            } else if (current == closing) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1).trim();
                }
            }
        }
        return text.substring(start).trim();
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

    private Iterable<String> buildParseCandidates(String normalizedContent) {
        String repairedContent = escapeRawControlCharsInStrings(normalizedContent);
        if (Objects.equals(repairedContent, normalizedContent)) {
            return java.util.List.of(normalizedContent);
        }
        return java.util.List.of(normalizedContent, repairedContent);
    }

    private String escapeRawControlCharsInStrings(String content) {
        StringBuilder builder = new StringBuilder(content.length() + 32);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (escaped) {
                builder.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                builder.append(current);
                escaped = true;
                continue;
            }
            if (current == '"') {
                builder.append(current);
                inString = !inString;
                continue;
            }
            if (inString) {
                if (current == '\n') {
                    builder.append("\\n");
                    continue;
                }
                if (current == '\r') {
                    builder.append("\\r");
                    continue;
                }
                if (current == '\t') {
                    builder.append("\\t");
                    continue;
                }
            }
            builder.append(current);
        }
        return builder.toString();
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
