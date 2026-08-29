package com.grifmcpo.consolebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Хелпер для прямых API вызовов к Telegram Bot API
 * Используется для работы с Business Account специфичными методами
 */
public class TelegramApiHelper {

    private final String botToken;
    private final Logger logger;
    private static final String API_URL = "https://api.telegram.org/bot";

    public TelegramApiHelper(String botToken, Logger logger) {
        this.botToken = botToken;
        this.logger = logger;
    }

    /**
     * Удаляет сообщение из бизнес-чата
     * @param businessConnectionId ID подключения бизнес-аккаунта
     * @param chatId ID чата
     * @param messageId ID сообщения для удаления
     * @return true если успешно удалено
     */
    public boolean deleteBusinessMessage(String businessConnectionId, long chatId, int messageId) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("business_connection_id", businessConnectionId);
            
            JsonArray messageIds = new JsonArray();
            messageIds.add(messageId);
            payload.add("message_ids", messageIds);

            String response = makeApiRequest("deleteBusinessMessages", payload);
            
            if (response != null) {
                JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                boolean ok = jsonResponse.get("ok").getAsBoolean();
                
                if (ok) {
                    logger.info("✅ Сообщение удалено из бизнес-чата (ID: " + messageId + ")");
                } else {
                    logger.warning("❌ Ошибка удаления: " + jsonResponse.get("description"));
                }
                return ok;
            }
        } catch (Exception e) {
            logger.warning("Ошибка при удалении сообщения: " + e.getMessage());
        }
        return false;
    }

    /**
     * Отправляет сообщение в бизнес-чат от имени бизнес-аккаунта
     * @param businessConnectionId ID подключения бизнес-аккаунта
     * @param chatId ID чата
     * @param text Текст сообщения
     * @param parseMode Режим разбора (HTML, Markdown, MarkdownV2)
     * @return ID отправленного сообщения или -1 при ошибке
     */
    public int sendBusinessMessage(String businessConnectionId, long chatId, String text, String parseMode) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("business_connection_id", businessConnectionId);
            payload.addProperty("chat_id", chatId);
            payload.addProperty("text", text);
            payload.addProperty("parse_mode", parseMode);

            String response = makeApiRequest("sendMessage", payload);
            
            if (response != null) {
                JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                boolean ok = jsonResponse.get("ok").getAsBoolean();
                
                if (ok) {
                    int messageId = jsonResponse.getAsJsonObject("result").get("message_id").getAsInt();
                    logger.info("✅ Сообщение отправлено в бизнес-чат (Message ID: " + messageId + ")");
                    return messageId;
                } else {
                    logger.warning("❌ Ошибка отправки: " + jsonResponse.get("description"));
                }
            }
        } catch (Exception e) {
            logger.warning("Ошибка при отправке сообщения: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Получает информацию о бизнес-аккаунте
     * @param businessConnectionId ID подключения бизнес-аккаунта
     * @return JSON с информацией о бизнес-аккаунте или null
     */
    public JsonObject getBusinessConnection(String businessConnectionId) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("business_connection_id", businessConnectionId);

            String response = makeApiRequest("getBusinessConnection", payload);
            
            if (response != null) {
                JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                if (jsonResponse.get("ok").getAsBoolean()) {
                    return jsonResponse.getAsJsonObject("result");
                }
            }
        } catch (Exception e) {
            logger.warning("Ошибка получения информации о бизнес-аккаунте: " + e.getMessage());
        }
        return null;
    }

    /**
     * Выполняет HTTP POST запрос к Telegram Bot API
     * @param method Метод API
     * @param payload JSON payload
     * @return Ответ от API как строка или null при ошибке
     */
    private String makeApiRequest(String method, JsonObject payload) {
        try {
            URL url = new URL(API_URL + botToken + "/" + method);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            String jsonString = payload.toString();
            byte[] postData = jsonString.getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 201) {
                return readResponse(conn);
            } else {
                logger.warning("API Error: " + responseCode);
                return null;
            }

        } catch (IOException e) {
            logger.warning("API Request Error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Читает ответ от API
     */
    private String readResponse(HttpURLConnection conn) throws IOException {
        StringBuilder response = new StringBuilder();
        try (java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()))) {
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
        }
        return response.toString();
    }
}
