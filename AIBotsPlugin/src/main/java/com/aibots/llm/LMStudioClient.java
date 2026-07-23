package com.aibots.llm;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Backward-compatible LM Studio client — wraps {@link OpenAiCompatibleProvider}.
 * Prefer {@link LLMRouter} for multi-provider routing.
 */
public class LMStudioClient implements LLMProvider {

    private final OpenAiCompatibleProvider delegate;

    public LMStudioClient(String baseUrl, String model, int timeoutSeconds, int maxTokens,
                          double temperature, Logger log) {
        this.delegate = new OpenAiCompatibleProvider(
                "lm-studio", baseUrl, model, null, timeoutSeconds, maxTokens, temperature, log);
    }

    public LMStudioClient(OpenAiCompatibleProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    public void setModel(String model) {
        delegate.setModel(model);
    }

    public String getModel() {
        return delegate.getModel();
    }

    public String getBaseUrl() {
        return delegate.getBaseUrl();
    }

    /** Legacy sync chat used by older call sites. */
    public String chat(String systemPrompt, String userMessage) {
        return generateResponse(systemPrompt, userMessage, LLMContext.builder()
                .taskType(LLMContext.TaskType.CHAT)
                .complexity(LLMContext.Complexity.SIMPLE)
                .build());
    }

    public CompletableFuture<String> chatAsync(String systemPrompt, String userMessage) {
        return generateResponseAsync(systemPrompt, userMessage, LLMContext.builder()
                .taskType(LLMContext.TaskType.CHAT)
                .complexity(LLMContext.Complexity.SIMPLE)
                .build());
    }

    @Override
    public String generateResponse(String systemPrompt, String userMessage, LLMContext context) {
        return delegate.generateResponse(systemPrompt, userMessage, context);
    }

    @Override
    public CompletableFuture<String> generateResponseAsync(
            String systemPrompt, String userMessage, LLMContext context) {
        return delegate.generateResponseAsync(systemPrompt, userMessage, context);
    }

    @Override
    public boolean healthCheck() {
        return delegate.healthCheck();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
