package com.aibots.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Native Ollama client ({@code /api/chat}, {@code /api/tags}).
 * Base URL is the Ollama host (default {@code http://127.0.0.1:11434});
 * a trailing {@code /v1} from copy-paste configs is stripped.
 */
public class OllamaProvider implements LLMProvider {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";

    private final String id;
    private final String baseUrl;
    private String model;
    private final int timeoutMs;
    private final int maxTokens;
    private final double temperature;
    private final Logger log;
    private final Gson gson = new Gson();
    private final ExecutorService executor;

    public OllamaProvider(
            String id,
            String baseUrl,
            String model,
            int timeoutSeconds,
            int maxTokens,
            double temperature,
            Logger log) {
        this.id = id == null || id.isBlank() ? "ollama" : id.trim();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = model;
        this.timeoutMs = Math.max(5, timeoutSeconds) * 1000;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.log = log;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "aibots-llm-" + this.id);
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return "Ollama(" + baseUrl + ")";
    }

    public void setModel(String model) {
        this.model = model;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public String generateResponse(String systemPrompt, String userMessage, LLMContext context) {
        try {
            ensureModel();
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("stream", false);
            body.addProperty("keep_alive", "10m");

            JsonArray messages = new JsonArray();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                JsonObject sys = new JsonObject();
                sys.addProperty("role", "system");
                sys.addProperty("content", systemPrompt);
                messages.add(sys);
            }
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userMessage == null ? "" : userMessage);
            messages.add(user);
            body.add("messages", messages);

            JsonObject options = new JsonObject();
            options.addProperty("temperature", temperature);
            options.addProperty("num_predict", maxTokens);
            body.add("options", options);

            String raw = postJson(baseUrl + "/api/chat", gson.toJson(body));
            return extractContent(raw);
        } catch (Exception e) {
            log.log(Level.WARNING, "[AIBots] LLM " + id + " chat failed: " + e.getMessage());
            return "I couldn't reach my brain (" + id + "): " + e.getMessage();
        }
    }

    @Override
    public CompletableFuture<String> generateResponseAsync(
            String systemPrompt, String userMessage, LLMContext context) {
        return CompletableFuture.supplyAsync(
                () -> generateResponse(systemPrompt, userMessage, context), executor);
    }

    @Override
    public boolean healthCheck() {
        try {
            String raw = get(baseUrl + "/api/tags", Math.min(timeoutMs, 5000));
            return raw != null && (raw.contains("models") || raw.contains("name"));
        } catch (Exception e) {
            log.warning("[AIBots] LLM " + id + " health check failed: " + e.getMessage());
            return false;
        }
    }

    private void ensureModel() throws IOException {
        if (model != null && !model.isBlank()) {
            return;
        }
        String raw = get(baseUrl + "/api/tags", timeoutMs);
        JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
        JsonArray models = obj.getAsJsonArray("models");
        if (models == null || models.isEmpty()) {
            throw new IOException("Ollama is running at " + baseUrl
                    + " but has no models. Run: ollama pull llama3.2");
        }
        JsonObject first = models.get(0).getAsJsonObject();
        if (first.has("name")) {
            model = first.get("name").getAsString();
        } else if (first.has("model")) {
            model = first.get("model").getAsString();
        } else {
            throw new IOException("Ollama /api/tags returned a model without a name");
        }
        log.info("[AIBots] Auto-selected " + id + " model: " + model);
    }

    private String extractContent(String raw) {
        try {
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            if (obj.has("error") && !obj.get("error").isJsonNull()) {
                return "I couldn't reach my brain (" + id + "): " + obj.get("error").getAsString();
            }
            if (obj.has("message") && obj.get("message").isJsonObject()) {
                JsonObject msg = obj.getAsJsonObject("message");
                String content = textField(msg, "content");
                if (!content.isBlank()) {
                    return content.trim();
                }
                // Reasoning models (qwen3, etc.) may put tokens in "thinking"
                String thinking = textField(msg, "thinking");
                if (!thinking.isBlank()) {
                    return thinking.trim();
                }
            }
            String response = textField(obj, "response");
            if (!response.isBlank()) {
                return response.trim();
            }
        } catch (Exception e) {
            log.log(Level.FINE, "Parse error: " + e.getMessage());
        }
        return "I heard you, but my reply was empty.";
    }

    private static String textField(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : "";
    }

    private String postJson(String url, String json) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(Math.min(timeoutMs, 8000));
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readFully(stream);
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + ": " + body);
        }
        return body;
    }

    private String get(String url, int timeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(Math.min(timeout, 5000));
        conn.setReadTimeout(timeout);
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readFully(stream);
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + ": " + body);
        }
        return body;
    }

    private static String readFully(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    static String normalizeBaseUrl(String raw) {
        String s = raw == null || raw.isBlank() ? DEFAULT_BASE_URL : raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/v1")) {
            s = s.substring(0, s.length() - 3);
        } else if (lower.endsWith("/api")) {
            s = s.substring(0, s.length() - 4);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
