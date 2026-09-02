package com.grifmcpo.consolebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SupabaseManager {

    private final TelegramConsoleBot plugin;
    private final String supabaseUrl;
    private final String supabaseKey;
    private final String supabaseAnonKey;

    public SupabaseManager(TelegramConsoleBot plugin) {
        this.plugin = plugin;
        this.supabaseUrl = plugin.getConfig().getString("supabase.url");
        this.supabaseKey = plugin.getConfig().getString("supabase.service_role_key");
        this.supabaseAnonKey = plugin.getConfig().getString("supabase.anon_key");
        
        if (supabaseUrl == null || supabaseKey == null || supabaseUrl.isEmpty() || supabaseKey.isEmpty()) {
            plugin.getLogger().severe("❌ Supabase настройки не найдены в config.yml!");
        } else {
            plugin.getLogger().info("✅ SupabaseManager инициализирован!");
        }
    }

    // =========================================================
    // ==== ОБЩИЙ МЕТОД ДЛЯ ЗАПРОСОВ =====
    // =========================================================
    private CompletableFuture<String> sendRequest(String endpoint, String method, String body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(supabaseUrl + "/rest/v1/" + endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("apikey", supabaseKey);
                conn.setRequestProperty("Authorization", "Bearer " + supabaseKey);
                conn.setDoInput(true);
                
                if (body != null && !body.isEmpty()) {
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                
                int responseCode = conn.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } else {
                    String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                    plugin.getLogger().warning("Supabase error: " + responseCode + " - " + error);
                    return null;
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Supabase request error: " + e.getMessage());
                return null;
            }
        });
    }

    // =========================================================
    // ==== ИГРОКИ =====
    // =========================================================
    public CompletableFuture<Boolean> savePlayer(String uuid, String name, String telegramId, String ip) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid);
        json.addProperty("player_name", name);
        if (telegramId != null) json.addProperty("telegram_id", telegramId);
        if (ip != null) json.addProperty("ip", ip);
        json.addProperty("session_start", System.currentTimeMillis());
        
        return sendRequest("players?uuid=eq." + uuid, "PATCH", json.toString())
            .thenApplyAsync(response -> {
                if (response == null) {
                    // Если нет записи, создаем
                    return sendRequest("players", "POST", json.toString())
                        .thenApply(r -> r != null).join();
                }
                return true;
            });
    }

    public CompletableFuture<Map<String, Object>> getPlayer(String uuid) {
        return sendRequest("players?uuid=eq." + uuid, "GET", null)
            .thenApplyAsync(response -> {
                if (response == null || response.isEmpty()) return null;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    if (arr.isEmpty()) return null;
                    JsonObject obj = arr.get(0).getAsJsonObject();
                    Map<String, Object> player = new HashMap<>();
                    player.put("uuid", obj.get("uuid").getAsString());
                    player.put("player_name", obj.get("player_name").getAsString());
                    if (obj.has("telegram_id") && !obj.get("telegram_id").isJsonNull()) {
                        player.put("telegram_id", obj.get("telegram_id").getAsString());
                    }
                    if (obj.has("ip") && !obj.get("ip").isJsonNull()) {
                        player.put("ip", obj.get("ip").getAsString());
                    }
                    player.put("session_start", obj.get("session_start").getAsLong());
                    player.put("blocked", obj.get("blocked").getAsBoolean());
                    return player;
                } catch (Exception e) {
                    plugin.getLogger().severe("Ошибка парсинга игрока: " + e.getMessage());
                    return null;
                }
            });
    }

    public CompletableFuture<String> getPlayerUuidByName(String name) {
        return sendRequest("players?player_name=eq." + name, "GET", null)
            .thenApplyAsync(response -> {
                if (response == null || response.isEmpty()) return null;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    if (arr.isEmpty()) return null;
                    return arr.get(0).getAsJsonObject().get("uuid").getAsString();
                } catch (Exception e) {
                    return null;
                }
            });
    }

    // =========================================================
    // ==== НАКАЗАНИЯ =====
    // =========================================================
    public CompletableFuture<Boolean> addPunishment(String playerUuid, String type, String issuerUuid,
                                                     String reason, String duration, long expiry, boolean hidden, boolean ipBan) {
        JsonObject json = new JsonObject();
        json.addProperty("player_uuid", playerUuid);
        json.addProperty("type", type);
        json.addProperty("issuer", issuerUuid);
        json.addProperty("reason", reason);
        json.addProperty("duration", duration);
        json.addProperty("timestamp", System.currentTimeMillis());
        json.addProperty("expiry", expiry);
        json.addProperty("hidden", hidden);
        json.addProperty("ip_ban", ipBan);
        json.addProperty("active", true);
        
        return sendRequest("punishments", "POST", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<List<Map<String, Object>>> getActivePunishments(String playerUuid) {
        String endpoint = "punishments?player_uuid=eq." + playerUuid + "&active=eq.true&expiry=gt." + System.currentTimeMillis();
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> p = new HashMap<>();
                        p.put("id", obj.get("id").getAsLong());
                        p.put("type", obj.get("type").getAsString());
                        p.put("issuer", obj.get("issuer").getAsString());
                        p.put("reason", obj.get("reason").getAsString());
                        p.put("duration", obj.get("duration").getAsString());
                        p.put("timestamp", obj.get("timestamp").getAsLong());
                        p.put("expiry", obj.get("expiry").getAsLong());
                        p.put("hidden", obj.get("hidden").getAsBoolean());
                        p.put("ip_ban", obj.get("ip_ban").getAsBoolean());
                        result.add(p);
                    }
                } catch (Exception e) {}
                return result;
            });
    }

    public CompletableFuture<Boolean> deactivatePunishment(long id) {
        JsonObject json = new JsonObject();
        json.addProperty("active", false);
        return sendRequest("punishments?id=eq." + id, "PATCH", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Boolean> deactivatePunishmentsByType(String playerUuid, String type) {
        JsonObject json = new JsonObject();
        json.addProperty("active", false);
        return sendRequest("punishments?player_uuid=eq." + playerUuid + "&type=eq." + type + "&active=eq.true", "PATCH", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    // =========================================================
    // ==== ИСТОРИЯ (HIST / SHIST) =====
    // =========================================================
    public CompletableFuture<List<Map<String, Object>>> getPunishmentHistory(String playerUuid, int limit) {
        String endpoint = "punishments?player_uuid=eq." + playerUuid + "&order=timestamp.desc&limit=" + limit;
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> p = new HashMap<>();
                        p.put("type", obj.get("type").getAsString());
                        p.put("issuer", obj.get("issuer").getAsString());
                        p.put("reason", obj.get("reason").getAsString());
                        p.put("duration", obj.get("duration").getAsString());
                        p.put("timestamp", obj.get("timestamp").getAsLong());
                        p.put("hidden", obj.get("hidden").getAsBoolean());
                        p.put("ip_ban", obj.get("ip_ban").getAsBoolean());
                        result.add(p);
                    }
                } catch (Exception e) {}
                return result;
            });
    }

    public CompletableFuture<List<Map<String, Object>>> getIssuerHistory(String issuerUuid, int limit) {
        String endpoint = "punishments?issuer=eq." + issuerUuid + "&order=timestamp.desc&limit=" + limit;
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> p = new HashMap<>();
                        p.put("player_uuid", obj.get("player_uuid").getAsString());
                        p.put("type", obj.get("type").getAsString());
                        p.put("reason", obj.get("reason").getAsString());
                        p.put("duration", obj.get("duration").getAsString());
                        p.put("timestamp", obj.get("timestamp").getAsLong());
                        result.add(p);
                    }
                } catch (Exception e) {}
                return result;
            });
    }

    // =========================================================
    // ==== БАНЛИСТ / МУТЕЛИСТ =====
    // =========================================================
    public CompletableFuture<List<Map<String, Object>>> getActiveBans() {
        String endpoint = "punishments?type=eq.ban&active=eq.true&expiry=gt." + System.currentTimeMillis();
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> p = new HashMap<>();
                        p.put("player_uuid", obj.get("player_uuid").getAsString());
                        p.put("reason", obj.get("reason").getAsString());
                        p.put("duration", obj.get("duration").getAsString());
                        p.put("expiry", obj.get("expiry").getAsLong());
                        p.put("issuer", obj.get("issuer").getAsString());
                        result.add(p);
                    }
                } catch (Exception e) {}
                return result;
            });
    }

    public CompletableFuture<List<Map<String, Object>>> getActiveMutes() {
        String endpoint = "punishments?type=eq.mute&active=eq.true&expiry=gt." + System.currentTimeMillis();
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> p = new HashMap<>();
                        p.put("player_uuid", obj.get("player_uuid").getAsString());
                        p.put("reason", obj.get("reason").getAsString());
                        p.put("duration", obj.get("duration").getAsString());
                        p.put("expiry", obj.get("expiry").getAsLong());
                        p.put("issuer", obj.get("issuer").getAsString());
                        result.add(p);
                    }
                } catch (Exception e) {}
                return result;
            });
    }

    // =========================================================
    // ==== БАНЫ БОТА =====
    // =========================================================
    public CompletableFuture<Boolean> banBotUser(String telegramId, String reason, String issuer, long expiry) {
        JsonObject json = new JsonObject();
        json.addProperty("telegram_id", telegramId);
        json.addProperty("reason", reason);
        json.addProperty("issuer", issuer);
        json.addProperty("timestamp", System.currentTimeMillis());
        json.addProperty("expiry", expiry);
        
        return sendRequest("bot_bans", "POST", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Boolean> unbanBotUser(String telegramId) {
        return sendRequest("bot_bans?telegram_id=eq." + telegramId, "DELETE", null)
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Map<String, Object>> getBotBan(String telegramId) {
        return sendRequest("bot_bans?telegram_id=eq." + telegramId, "GET", null)
            .thenApplyAsync(response -> {
                if (response == null || response.isEmpty()) return null;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    if (arr.isEmpty()) return null;
                    JsonObject obj = arr.get(0).getAsJsonObject();
                    Map<String, Object> ban = new HashMap<>();
                    ban.put("reason", obj.get("reason").getAsString());
                    ban.put("issuer", obj.get("issuer").getAsString());
                    ban.put("timestamp", obj.get("timestamp").getAsLong());
                    ban.put("expiry", obj.get("expiry").getAsLong());
                    return ban;
                } catch (Exception e) {
                    return null;
                }
            });
    }

    // =========================================================
    // ==== РЕПОРТЫ =====
    // =========================================================
    public CompletableFuture<Long> createReport(String reporterUuid, String targetUuid, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("reporter_uuid", reporterUuid);
        json.addProperty("target_uuid", targetUuid);
        json.addProperty("reason", reason);
        json.addProperty("timestamp", System.currentTimeMillis());
        json.addProperty("closed", false);
        
        return sendRequest("reports", "POST", json.toString())
            .thenApplyAsync(response -> {
                if (response == null) return -1L;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    if (arr.isEmpty()) return -1L;
                    return arr.get(0).getAsJsonObject().get("id").getAsLong();
                } catch (Exception e) {
                    return -1L;
                }
            });
    }

    public CompletableFuture<List<Map<String, Object>>> getReports(boolean onlyActive) {
        String endpoint = "reports" + (onlyActive ? "?closed=eq.false" : "");
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> r = new HashMap<>();
                        r.put("id", obj.get("id").getAsLong());
                        r.put("reporter_uuid", obj.get("reporter_uuid").getAsString());
                        r.put("target_uuid", obj.get("target_uuid").getAsString());
                        r.put("reason", obj.get("reason").getAsString());
                        r.put("timestamp", obj.get("timestamp").getAsLong());
                        r.put("closed", obj.get("closed").getAsBoolean());
                        if (obj.has("closed_by") && !obj.get("closed_by").isJsonNull()) {
                            r.put("closed_by", obj.get("closed_by").getAsString());
                        }
                        result.add(r);
                    }
                } catch (Exception e) {}
                return result;
            });
    }

    public CompletableFuture<Boolean> closeReport(long id, String closedBy) {
        JsonObject json = new JsonObject();
        json.addProperty("closed", true);
        json.addProperty("closed_by", closedBy);
        json.addProperty("closed_at", System.currentTimeMillis());
        return sendRequest("reports?id=eq." + id, "PATCH", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    // =========================================================
    // ==== ЧЕРНЫЙ СПИСОК =====
    // =========================================================
    public CompletableFuture<Boolean> addBlacklist(String playerUuid, String issuer, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("player_uuid", playerUuid);
        json.addProperty("issuer", issuer);
        json.addProperty("reason", reason);
        json.addProperty("timestamp", System.currentTimeMillis());
        return sendRequest("blacklist", "POST", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Boolean> removeBlacklist(String playerUuid) {
        return sendRequest("blacklist?player_uuid=eq." + playerUuid, "DELETE", null)
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Boolean> isBlacklisted(String playerUuid) {
        return sendRequest("blacklist?player_uuid=eq." + playerUuid, "GET", null)
            .thenApplyAsync(response -> response != null && !response.isEmpty() && !response.equals("[]"));
    }

    // =========================================================
    // ==== ТЕХНИЧЕСКИЕ РАБОТЫ =====
    // =========================================================
    public CompletableFuture<Map<String, Object>> getTechWorksStatus() {
        return sendRequest("techworks?limit=1", "GET", null)
            .thenApplyAsync(response -> {
                if (response == null || response.isEmpty()) return null;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    if (arr.isEmpty()) return null;
                    JsonObject obj = arr.get(0).getAsJsonObject();
                    Map<String, Object> status = new HashMap<>();
                    status.put("enabled", obj.get("enabled").getAsBoolean());
                    status.put("reason", obj.get("reason").getAsString());
                    if (obj.has("admin") && !obj.get("admin").isJsonNull()) {
                        status.put("admin", obj.get("admin").getAsString());
                    }
                    status.put("end_time", obj.get("end_time").getAsLong());
                    status.put("auto_start_enabled", obj.get("auto_start_enabled").getAsBoolean());
                    status.put("auto_start_time", obj.get("auto_start_time").getAsLong());
                    if (obj.has("auto_start_reason") && !obj.get("auto_start_reason").isJsonNull()) {
                        status.put("auto_start_reason", obj.get("auto_start_reason").getAsString());
                    }
                    return status;
                } catch (Exception e) {
                    return null;
                }
            });
    }

    public CompletableFuture<Boolean> updateTechWorks(Map<String, Object> data) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getValue() instanceof Boolean) {
                json.addProperty(entry.getKey(), (Boolean) entry.getValue());
            } else if (entry.getValue() instanceof Long) {
                json.addProperty(entry.getKey(), (Long) entry.getValue());
            } else if (entry.getValue() instanceof String) {
                json.addProperty(entry.getKey(), (String) entry.getValue());
            }
        }
        return sendRequest("techworks?id=eq.1", "PATCH", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    // =========================================================
    // ==== БОТЫ (NPC) =====
    // =========================================================
    public CompletableFuture<Boolean> saveBot(String name, String uuid, boolean active, 
                                               String world, double x, double y, double z, float yaw, float pitch) {
        JsonObject json = new JsonObject();
        json.addProperty("bot_name", name);
        json.addProperty("uuid", uuid);
        json.addProperty("active", active);
        json.addProperty("world", world);
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);
        json.addProperty("yaw", yaw);
        json.addProperty("pitch", pitch);
        
        return sendRequest("bots?bot_name=eq." + name, "PATCH", json.toString())
            .thenApplyAsync(response -> {
                if (response == null) {
                    return sendRequest("bots", "POST", json.toString())
                        .thenApply(r -> r != null).join();
                }
                return true;
            });
    }

    public CompletableFuture<List<Map<String, Object>>> getBots() {
        return sendRequest("bots", "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> bot = new HashMap<>();
                        bot.put("bot_name", obj.get("bot_name").getAsString());
                        bot.put("uuid", obj.get("uuid").getAsString());
                        bot.put("active", obj.get("active").getAsBoolean());
                        bot.put("world", obj.get("world").getAsString());
                        bot.put("x", obj.get("x").getAsDouble());
                        bot.put("y", obj.get("y").getAsDouble());
                        bot.put("z", obj.get("z").getAsDouble());
                        bot.put("yaw", obj.get("yaw").getAsFloat());
                        bot.put("pitch", obj.get("pitch").getAsFloat());
                        result.add(bot);
                    }
                } catch (Exception e) {}
                return result;
            });
    }

    public CompletableFuture<Boolean> deleteBot(String name) {
        return sendRequest("bots?bot_name=eq." + name, "DELETE", null)
            .thenApplyAsync(r -> r != null);
    }

    // =========================================================
    // ==== ЛОГИ КОМАНД =====
    // =========================================================
    public CompletableFuture<Void> logCommand(String playerUuid, String command) {
        JsonObject json = new JsonObject();
        json.addProperty("player_uuid", playerUuid);
        json.addProperty("command", command);
        json.addProperty("timestamp", System.currentTimeMillis());
        return sendRequest("command_logs", "POST", json.toString())
            .thenAcceptAsync(r -> {});
    }

    public CompletableFuture<List<Map<String, Object>>> getCommandLogs(String playerUuid, int limit, int days) {
        long cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
        String endpoint = "command_logs?player_uuid=eq." + playerUuid + "&timestamp=gt." + cutoff + 
                         "&order=timestamp.desc&limit=" + limit;
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> log = new HashMap<>();
                        log.put("command", obj.get("command").getAsString());
                        log.put("timestamp", obj.get("timestamp").getAsLong());
                        result.add(log);
                    }
                } catch (Exception e) {}
                return result;
            });
    }
}
