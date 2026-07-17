package com.aibots;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class LMStudioClient {
    private final String baseUrl;
    private final String model;

    public LMStudioClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public String sendMessage(String prompt) {
        try {
            URL url = new URL(baseUrl + "/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String jsonBody = String.format("""
                {
                  "model": "%s",
                  "messages": [{"role": "user", "content": "%s"}],
                  "temperature": 0.8,
                  "max_tokens": 800
                }
                """, model, prompt.replace("\"", "\\\"").replace("\n", "\\n"));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes("UTF-8"));
            }

            Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8");
            String fullResponse = scanner.useDelimiter("\\A").next();
            scanner.close();

            if (fullResponse.contains("\"content\":")) {
                int start = fullResponse.indexOf("\"content\": \"") + 12;
                int end = fullResponse.indexOf("\"", start);
                if (end > start) {
                    String content = fullResponse.substring(start, end);
                    content = content.replace("\\n", "\n").replace("\\\"", "\"");
                    return content.trim();
                }
            }
            return "I heard you! What would you like me to do?";

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}