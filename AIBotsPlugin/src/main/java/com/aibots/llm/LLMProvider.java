package com.aibots.llm;

import java.util.concurrent.CompletableFuture;

/**
 * Multi-LLM provider abstraction (Phase 1).
 * Implementations: local LM Studio, OpenAI-compatible cloud APIs, etc.
 */
public interface LLMProvider extends AutoCloseable {

    /** Stable id used in config routing (e.g. "lm-studio", "openai", "grok"). */
    String id();

    /** Human-readable label for logs. */
    default String displayName() {
        return id();
    }

    /**
     * Generate a completion from system + user prompts.
     *
     * @param systemPrompt role / system instructions (may be null)
     * @param userMessage  player or bot message
     * @param context      routing metadata (role, complexity)
     * @return model reply text (never null; errors become readable messages)
     */
    String generateResponse(String systemPrompt, String userMessage, LLMContext context);

    /**
     * Async variant — runs off the main server thread.
     */
    default CompletableFuture<String> generateResponseAsync(
            String systemPrompt, String userMessage, LLMContext context) {
        return CompletableFuture.completedFuture(
                generateResponse(systemPrompt, userMessage, context));
    }

    /** True if the endpoint answers a lightweight health probe. */
    boolean healthCheck();

    @Override
    void close();
}
