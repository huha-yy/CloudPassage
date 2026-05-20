package com.yupi.template.service;

import com.google.gson.reflect.TypeToken;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared helper for structured LLM output parsing and retry.
 *
 * @author <a href="XXXX">呼哈设计</a>
 */
public interface ArticleStructuredOutputService {

    <T> T execute(String prompt, String resultName, Function<String, String> llmCall,
                  Class<T> clazz, Predicate<T> validator);

    <T> T execute(String prompt, String resultName, Function<String, String> llmCall,
                  TypeToken<T> typeToken, Predicate<T> validator);
}
