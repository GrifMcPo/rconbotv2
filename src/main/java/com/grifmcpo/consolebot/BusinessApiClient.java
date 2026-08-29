package com.grifmcpo.consolebot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Утилита для вызова методов Bot API, используемых Business API:
 * - deleteBusinessMessages
 * - sendMessage (с business_connection_id)
 */
public class BusinessApiClient {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String botToken;

    public BusinessApiClient(String botToken) {
        this.botToken = botToken;
    }

    private String apiUrl(String method) {
        return "https://api.telegram.org/bot" + botToken + "/" + method;
    }

    public boolean deleteBusinessMessages(String businessConnectionId, List<Integer> messageIds) {
        try {
            Map<String, Object> payload = Map.of(
                    "business_connection_id", businessConnectionId,
                    "message_ids", messageIds
            );
            String body = JSON.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl("deleteBusinessMessages")))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            Map<?,?> result = JSON.readValue(resp.body(), Map.class);
            Object ok = result.get("ok");
            return ok instanceof Boolean && (Boolean) ok;
        } catch (Exception e) {
            System.err.println("deleteBusinessMessages error: " + e.getMessage());
            return false;
        }
    }

    public Map<String, Object> sendToBusinessChat(long chatId, String text, String businessConnectionId, Map<String, Object> replyMarkup) {
        try {
            Map<String, Object> base = Map.of(
                    "chat_id", chatId,
                    "text", text,
                    "business_connection_id", businessConnectionId,
                    "parse_mode", "HTML"
            );
            Map<String, Object> payload = base;
            if (replyMarkup != null) {
                payload = Map.of(
                        "chat_id", chatId,
                        "text", text,
                        "business_connection_id", businessConnectionId,
                        "parse_mode", "HTML",
                        "reply_markup", replyMarkup
                );
            }
            String body = JSON.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl("sendMessage")))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            Map<?,?> result = JSON.readValue(resp.body(), Map.class);
            if (Boolean.TRUE.equals(result.get("ok"))) {
                return (Map<String, Object>) result.get("result");
            } else {
                return Map.of("ok", false, "description", result.get("description"));
            }
        } catch (Exception e) {
            System.err.println("sendToBusinessChat error: " + e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
