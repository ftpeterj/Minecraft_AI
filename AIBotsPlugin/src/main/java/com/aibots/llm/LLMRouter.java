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
 *   primary: ollama
 *   fallback-to: lm-studio
 *   escalate-complex: false
 *   escalate-to: openai
 *   providers:
 *     ollama:
 *       type: ollama
 *       base-url: http://127.0.0.1:11434
 *       model: ""
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
 * Legacy {@code ollama:*} / {@code lm-studio:*} keys are still honored when
 * {@code llm.providers} is absent.
 */
public final class LLMRouter implements LLMProvider {

    private static final String UNREACHABLE_PREFIX = "I couldn't reach my brain (";
    private static final long HEALTH_CACHE_MS = 15_000L;

    private final Map<String, LLMProvider> providers = new LinkedHashMap<>();
    private final String primaryId;
    private final boolean escalateComplex;
    private final String escalateToId;
    private final String fallbackToId;
    private final Logger log;
    private final LMStudioClient legacyFacade;
    private volatile long healthCachedAt;
    private volatile boolean primaryHealthyCached;

    public LLMRouter(FileConfiguration config, Logger log) {
        this.log = log;
        ConfigurationSection llm = config.getConfigurationSection("llm");
        if (llm != null && llm.isConfigurationSection("providers")) {
            this.primaryId = llm.getString("primary", "ollama");
            this.escalateComplex = llm.getBoolean("escalate-complex", false);
            this.escalateToId = llm.getString("escalate-to", "");
            this.fallbackToId = llm.getString("fallback-to", "");
            loadProviders(llm.getConfigurationSection("providers"), config);
        } else if (config.isConfigurationSection("ollama")) {
            this.primaryId = "ollama";
            this.escalateComplex = false;
            this.escalateToId = "";
            this.fallbackToId = config.isConfigurationSection("lm-studio") ? "lm-studio" : "";
            providers.put("ollama", buildOllamaFromRoot(config, "ollama"));
            if (config.isConfigurationSection("lm-studio")) {
                providers.put("lm-studio", buildLmStudioFromRoot(config));
            }
        } else {
            // Legacy lm-studio block only
            this.primaryId = "lm-studio";
            this.escalateComplex = false;
            this.escalateToId = "";
            this.fallbackToId = "";
            providers.put("lm-studio", buildLmStudioFromRoot(config));
        }

        // Always expose a LMStudioClient facade for legacy call sites
        LLMProvider primary = providers.get(primaryId);
        if (primary instanceof OpenAiCompatibleProvider oai) {
            this.legacyFacade = new LMStudioClient(oai);
        } else if (primary instanceof LMStudioClient lm) {
            this.legacyFacade = lm;
        } else if (providers.get("lm-studio") instanceof OpenAiCompatibleProvider oai) {
            this.legacyFacade = new LMStudioClient(oai);
        } else {
            this.legacyFacade = new LMStudioClient(buildLmStudioFromRoot(config));
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
            boolean ollamaType = type.equals("ollama") || key.equalsIgnoreCase("ollama");
            boolean openaiType = type.equals("openai-compatible") || type.equals("openai")
                    || type.equals("lm-studio");
            if (!ollamaType && !openaiType) {
                log.warning("[AIBots] Unknown LLM provider type '" + type + "' for " + key + " — skipping");
                continue;
            }

            int timeout = sec.getInt("timeout-seconds",
                    root.getInt(key + ".timeout-seconds",
                            root.getInt("ollama.timeout-seconds",
                                    root.getInt("lm-studio.timeout-seconds", ollamaType ? 120 : 60))));
            int maxTokens = sec.getInt("max-tokens",
                    root.getInt(key + ".max-tokens",
                            root.getInt("ollama.max-tokens",
                                    root.getInt("lm-studio.max-tokens", 400))));
            double temperature = sec.getDouble("temperature",
                    root.getDouble(key + ".temperature",
                            root.getDouble("ollama.temperature",
                                    root.getDouble("lm-studio.temperature", 0.7))));
            String model = sec.getString("model", "");
            String baseUrl = sec.getString("base-url",
                    ollamaType ? OllamaProvider.DEFAULT_BASE_URL : "http://127.0.0.1:1234/v1");

            if ((model == null || model.isBlank()) && root.isConfigurationSection(key)) {
                model = root.getString(key + ".model", model);
            }
            if (root.isConfigurationSection(key)) {
                String legacy = root.getString(key + ".base-url");
                if (legacy != null && !legacy.isBlank()
                        && (baseUrl == null || baseUrl.contains("127.0.0.1"))) {
                    baseUrl = legacy;
                }
            }

            LLMProvider p;
            if (ollamaType) {
                p = new OllamaProvider(key, baseUrl, model, timeout, maxTokens, temperature, log);
            } else {
                String apiKey = sec.getString("api-key", "");
                p = new OpenAiCompatibleProvider(
                        key, baseUrl, model, apiKey, timeout, maxTokens, temperature, log);
            }
            providers.put(key, p);
            log.info("[AIBots] Registered LLM provider: " + key + " (" + type + ") @ " + p.getBaseUrl());
        }
        if (providers.isEmpty()) {
            if (root.isConfigurationSection("ollama")) {
                providers.put("ollama", buildOllamaFromRoot(root, "ollama"));
            } else {
                providers.put("lm-studio", buildLmStudioFromRoot(root));
            }
        }
    }

    private OllamaProvider buildOllamaFromRoot(FileConfiguration root, String key) {
        return new OllamaProvider(
                key,
                root.getString(key + ".base-url", OllamaProvider.DEFAULT_BASE_URL),
                root.getString(key + ".model", ""),
                root.getInt(key + ".timeout-seconds", 120),
                root.getInt(key + ".max-tokens", 400),
                root.getDouble(key + ".temperature", 0.7),
                log
        );
    }

    private OpenAiCompatibleProvider buildLmStudioFromRoot(FileConfiguration root) {
        return new OpenAiCompatibleProvider(
                "lm-studio",
                root.getString("lm-studio.base-url", "http://127.0.0.1:1234/v1"),
                root.getString("lm-studio.model", ""),
                null,
                root.getInt("lm-studio.timeout-seconds", 60),
                root.getInt("lm-studio.max-tokens", 400),
                root.getDouble("lm-studio.temperature", 0.7),
                log
        );
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
        LLMProvider primary = primary();
        if (!primaryHealthy() && fallback() != null && fallback() != primary) {
            return fallback();
        }
        return primary;
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
        String result = p.generateResponse(systemPrompt, userMessage, context);
        LLMProvider fallback = fallback();
        if (isUnreachable(result) && fallback != null && fallback != p) {
            healthCachedAt = 0;
            primaryHealthyCached = false;
            log.warning("[AIBots] LLM " + p.id() + " failed; falling back to " + fallback.id());
            return fallback.generateResponse(systemPrompt, userMessage, context);
        }
        return result;
    }

    @Override
    public CompletableFuture<String> generateResponseAsync(
            String systemPrompt, String userMessage, LLMContext context) {
        LLMProvider p = resolve(context);
        return p.generateResponseAsync(systemPrompt, userMessage, context)
                .thenCompose(result -> {
                    LLMProvider fallback = fallback();
                    if (isUnreachable(result) && fallback != null && fallback != p) {
                        healthCachedAt = 0;
                        primaryHealthyCached = false;
                        log.warning("[AIBots] LLM " + p.id() + " failed; falling back to " + fallback.id());
                        return fallback.generateResponseAsync(systemPrompt, userMessage, context);
                    }
                    return CompletableFuture.completedFuture(result);
                });
    }

    @Override
    public boolean healthCheck() {
        if (primary().healthCheck()) {
            primaryHealthyCached = true;
            healthCachedAt = System.currentTimeMillis();
            return true;
        }
        primaryHealthyCached = false;
        healthCachedAt = System.currentTimeMillis();
        LLMProvider fallback = fallback();
        if (fallback != null && fallback.healthCheck()) {
            log.warning("[AIBots] LLM primary '" + primaryId + "' down; fallback '"
                    + fallbackToId + "' is reachable");
            return true;
        }
        return false;
    }

    private boolean primaryHealthy() {
        long now = System.currentTimeMillis();
        if (now - healthCachedAt < HEALTH_CACHE_MS) {
            return primaryHealthyCached;
        }
        primaryHealthyCached = primary().healthCheck();
        healthCachedAt = now;
        return primaryHealthyCached;
    }

    private LLMProvider fallback() {
        if (fallbackToId == null || fallbackToId.isBlank()) {
            return null;
        }
        return providers.get(fallbackToId);
    }

    private static boolean isUnreachable(String result) {
        return result != null && result.startsWith(UNREACHABLE_PREFIX);
    }

    @Override
    public String getModel() {
        return primary().getModel();
    }

    @Override
    public String getBaseUrl() {
        return primary().getBaseUrl();
    }

    public Map<String, LLMProvider> providers() {
        return Map.copyOf(providers);
    }

    public String primaryId() {
        return primaryId;
    }

    public String fallbackToId() {
        return fallbackToId;
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
