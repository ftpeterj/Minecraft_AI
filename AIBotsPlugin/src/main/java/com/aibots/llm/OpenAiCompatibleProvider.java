package com.aibots.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OpenAI-compatible chat completions client (LM Studio, OpenAI, Grok, local proxies).
 */
public class OpenAiCompatibleProvider implements LLMProvider {

    private final String id;
    private final String baseUrl;
    private String model;
    private final String apiKey;
    private final int timeoutMs;
    private final int maxTokens;
    private final double temperature;
    private final Logger log;
    private final Gson gson = new Gson();
    private final ExecutorService executor;

    public OpenAiCompatibleProvider(
            String id,
            String baseUrl,
            String model,
            String apiKey,
            int timeoutSeconds,
            int maxTokens,
            double temperature,
            Logger log) {
        this.id = id == null || id.isBlank() ? "openai" : id.trim();
        this.baseUrl = trimTrailingSlash(baseUrl == null ? "http://127.0.0.1:1234/v1" : baseUrl);
        this.model = model;
        this.apiKey = apiKey;
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

    public void setModel(String model) {
        this.model = model;
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public String generateResponse(String systemPrompt, String userMessage, LLMContext context) {
        try {
            ensureModel();
            JsonObject body = new JsonObject();
            body.addProperty("model", model == null ? "" : model);
            body.addProperty("temperature", temperature);
            body.addProperty("max_tokens", maxTokens);

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

            String raw = postJson(baseUrl + "/chat/completions", gson.toJson(body));
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
            String raw = get(baseUrl + "/models");
            return raw != null && (raw.contains("data") || raw.contains("object"));
        } catch (Exception e) {
            log.warning("[AIBots] LLM " + id + " health check failed: " + e.getMessage());
            return false;
        }
    }

    private void ensureModel() throws IOException {
        if (model != null && !model.isBlank()) {
            return;
        }
        String raw = get(baseUrl + "/models");
        JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
        if (obj.has("data") && obj.getAsJsonArray("data").size() > 0) {
            model = obj.getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsString();
            log.info("[AIBots] Auto-selected " + id + " model: " + model);
        } else {
            throw new IOException("No models available at " + baseUrl);
        }
    }

    private String extractContent(String raw) {
        try {
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray choices = obj.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return "…";
            }
            JsonObject choice0 = choices.get(0).getAsJsonObject();
            if (choice0.has("message") && choice0.get("message").isJsonObject()) {
                JsonObject msg = choice0.getAsJsonObject("message");
                if (msg.has("content") && !msg.get("content").isJsonNull()) {
                    return msg.get("content").getAsString().trim();
                }
            }
            if (choice0.has("text")) {
                return choice0.get("text").getAsString().trim();
            }
        } catch (Exception e) {
            log.log(Level.FINE, "Parse error: " + e.getMessage());
        }
        return "I heard you, but my reply was empty.";
    }

    private String postJson(String url, String json) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
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

    private String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
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

    private static String trimTrailingSlash(String s) {
        if (s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
