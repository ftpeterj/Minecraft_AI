package com.aibots.llm;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Multi-LLM router: primary local model + optional cloud escalation.
 * <p>
 * Config:
 * <pre>
 * llm:
 *   primary: lm-studio
 *   escalate-complex: false
 *   escalate-to: openai
 *   providers:
 *     lm-studio:
 *       type: openai-compatible
 *       base-url: http://127.0.0.1:1234/v1
 *       model: ""
 *     openai:
 *       type: openai-compatible
 *       base-url: https://api.openai.com/v1
 *       api-key: ""
 *       model: gpt-4o-mini
 * </pre>
 * Legacy {@code lm-studio:*} keys are still honored when {@code llm.providers} is absent.
 */
public final class LLMRouter implements LLMProvider {

    private final Map<String, LLMProvider> providers = new LinkedHashMap<>();
    private final String primaryId;
    private final boolean escalateComplex;
    private final String escalateToId;
    private final Logger log;
    private final LMStudioClient legacyFacade;

    public LLMRouter(FileConfiguration config, Logger log) {
        this.log = log;
        ConfigurationSection llm = config.getConfigurationSection("llm");
        if (llm != null && llm.isConfigurationSection("providers")) {
            this.primaryId = llm.getString("primary", "lm-studio");
            this.escalateComplex = llm.getBoolean("escalate-complex", false);
            this.escalateToId = llm.getString("escalate-to", "");
            loadProviders(llm.getConfigurationSection("providers"), config);
        } else {
            // Legacy lm-studio block only
            this.primaryId = "lm-studio";
            this.escalateComplex = false;
            this.escalateToId = "";
            String baseUrl = config.getString("lm-studio.base-url", "http://127.0.0.1:1234/v1");
            String model = config.getString("lm-studio.model", "");
            int timeout = config.getInt("lm-studio.timeout-seconds", 60);
            int maxTokens = config.getInt("lm-studio.max-tokens", 400);
            double temperature = config.getDouble("lm-studio.temperature", 0.7);
            OpenAiCompatibleProvider p = new OpenAiCompatibleProvider(
                    "lm-studio", baseUrl, model, null, timeout, maxTokens, temperature, log);
            providers.put(p.id(), p);
        }

        // Always expose a LMStudioClient facade for legacy call sites
        LLMProvider primary = providers.get(primaryId);
        if (primary instanceof OpenAiCompatibleProvider oai) {
            this.legacyFacade = new LMStudioClient(oai);
        } else if (primary instanceof LMStudioClient lm) {
            this.legacyFacade = lm;
        } else {
            // Fallback facade sharing primary generate
            this.legacyFacade = new LMStudioClient(
                    config.getString("lm-studio.base-url",
                            config.getString("llm.providers.lm-studio.base-url", "http://127.0.0.1:1234/v1")),
                    config.getString("lm-studio.model",
                            config.getString("llm.providers.lm-studio.model", "")),
                    config.getInt("lm-studio.timeout-seconds", 60),
                    config.getInt("lm-studio.max-tokens", 400),
                    config.getDouble("lm-studio.temperature", 0.7),
                    log
            );
            providers.putIfAbsent(legacyFacade.id(), legacyFacade);
        }
    }

    private void loadProviders(ConfigurationSection providersSec, FileConfiguration root) {
        if (providersSec == null) {
            return;
        }
        for (String key : providersSec.getKeys(false)) {
            ConfigurationSection sec = providersSec.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            String type = sec.getString("type", "openai-compatible").toLowerCase(Locale.ROOT);
            if (!type.equals("openai-compatible") && !type.equals("openai") && !type.equals("lm-studio")) {
                log.warning("[AIBots] Unknown LLM provider type '" + type + "' for " + key + " — skipping");
                continue;
            }
            String baseUrl = sec.getString("base-url", "http://127.0.0.1:1234/v1");
            String model = sec.getString("model", "");
            String apiKey = sec.getString("api-key", "");
            // Allow env-style empty with legacy lm-studio fallback for primary
            if ("lm-studio".equals(key) && (model == null || model.isBlank())) {
                model = root.getString("lm-studio.model", model);
            }
            if ("lm-studio".equals(key) && (baseUrl == null || baseUrl.contains("127.0.0.1"))) {
                String legacy = root.getString("lm-studio.base-url");
                if (legacy != null && !legacy.isBlank()) {
                    baseUrl = legacy;
                }
            }
            int timeout = sec.getInt("timeout-seconds", root.getInt("lm-studio.timeout-seconds", 60));
            int maxTokens = sec.getInt("max-tokens", root.getInt("lm-studio.max-tokens", 400));
            double temperature = sec.getDouble("temperature", root.getDouble("lm-studio.temperature", 0.7));
            OpenAiCompatibleProvider p = new OpenAiCompatibleProvider(
                    key, baseUrl, model, apiKey, timeout, maxTokens, temperature, log);
            providers.put(key, p);
            log.info("[AIBots] Registered LLM provider: " + key + " @ " + baseUrl);
        }
        if (providers.isEmpty()) {
            // Safety net
            OpenAiCompatibleProvider p = new OpenAiCompatibleProvider(
                    "lm-studio",
                    root.getString("lm-studio.base-url", "http://127.0.0.1:1234/v1"),
                    root.getString("lm-studio.model", ""),
                    null,
                    root.getInt("lm-studio.timeout-seconds", 60),
                    root.getInt("lm-studio.max-tokens", 400),
                    root.getDouble("lm-studio.temperature", 0.7),
                    log
            );
            providers.put(p.id(), p);
        }
    }

    /** Legacy client facade (chat / chatAsync / getModel). */
    public LMStudioClient asLegacyClient() {
        return legacyFacade;
    }

    public LLMProvider primary() {
        LLMProvider p = providers.get(primaryId);
        if (p != null) {
            return p;
        }
        return providers.values().iterator().next();
    }

    public LLMProvider resolve(LLMContext context) {
        if (context != null && context.preferredProviderId() != null
                && providers.containsKey(context.preferredProviderId())) {
            return providers.get(context.preferredProviderId());
        }
        if (escalateComplex && context != null
                && context.complexity() == LLMContext.Complexity.COMPLEX
                && escalateToId != null && !escalateToId.isBlank()
                && providers.containsKey(escalateToId)) {
            return providers.get(escalateToId);
        }
        // Role-based optional routing from config later — default primary
        return primary();
    }

    @Override
    public String id() {
        return "router";
    }

    @Override
    public String displayName() {
        return "LLMRouter(primary=" + primaryId + ")";
    }

    @Override
    public String generateResponse(String systemPrompt, String userMessage, LLMContext context) {
        LLMProvider p = resolve(context);
        return p.generateResponse(systemPrompt, userMessage, context);
    }

    @Override
    public CompletableFuture<String> generateResponseAsync(
            String systemPrompt, String userMessage, LLMContext context) {
        LLMProvider p = resolve(context);
        return p.generateResponseAsync(systemPrompt, userMessage, context);
    }

    @Override
    public boolean healthCheck() {
        return primary().healthCheck();
    }

    public Map<String, LLMProvider> providers() {
        return Map.copyOf(providers);
    }

    public String primaryId() {
        return primaryId;
    }

    @Override
    public void close() {
        for (LLMProvider p : providers.values()) {
            try {
                p.close();
            } catch (Exception ignored) {
            }
        }
    }
}
