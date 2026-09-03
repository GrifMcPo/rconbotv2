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

    public SupabaseManager(TelegramConsoleBot plugin) {
        this.plugin = plugin;
        this.supabaseUrl = plugin.getConfig().getString("supabase.url");
        this.supabaseKey = plugin.getConfig().getString("supabase.service_role_key");
        
        if (supabaseUrl == null || supabaseKey == null || supabaseUrl.isEmpty() || supabaseKey.isEmpty()) {
            plugin.getLogger().severe("❌ Supabase настройки не найдены в config.yml!");
        } else {
            plugin.getLogger().info("✅ SupabaseManager инициализирован!");
        }
    }

    // =========================================================
    // ==== ОБЩИЙ МЕТОД ЗАПРОСА =====
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
    public CompletableFuture<String> getOrCreatePlayer(String playerName, String ip) {
        return getPlayerUuidByName(playerName).thenComposeAsync(uuid -> {
            if (uuid != null) {
                return CompletableFuture.completedFuture(uuid);
            }
            
            // Создаем нового игрока
            String newUuid = UUID.randomUUID().toString();
            JsonObject json = new JsonObject();
            json.addProperty("uuid", newUuid);
            json.addProperty("player_name", playerName);
            if (ip != null && !ip.isEmpty() && !ip.equals("—")) {
                json.addProperty("ip", ip);
            }
            json.addProperty("session_start", System.currentTimeMillis());
            
            return sendRequest("players", "POST", json.toString())
                .thenApplyAsync(r -> r != null ? newUuid : null);
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

    public CompletableFuture<String> getPlayerNameByUuid(String uuid) {
        return sendRequest("players?uuid=eq." + uuid, "GET", null)
            .thenApplyAsync(response -> {
                if (response == null || response.isEmpty()) return null;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    if (arr.isEmpty()) return null;
                    return arr.get(0).getAsJsonObject().get("player_name").getAsString();
                } catch (Exception e) {
                    return null;
                }
            });
    }

    public CompletableFuture<Boolean> updatePlayerIp(String uuid, String ip) {
        JsonObject json = new JsonObject();
        json.addProperty("ip", ip);
        return sendRequest("players?uuid=eq." + uuid, "PATCH", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    // =========================================================
    // ==== НАКАЗАНИЯ (ВСЕ ТИПЫ) =====
    // =========================================================
    public CompletableFuture<Long> addPunishment(
            String playerUuid, String playerName,
            String type, String issuerUuid, String issuerName,
            String reason, String duration, long durationMs, long expiry,
            boolean hidden, String ip) {
        
        JsonObject json = new JsonObject();
        json.addProperty("player_uuid", playerUuid);
        json.addProperty("player_name", playerName);
        json.addProperty("type", type);
        json.addProperty("issuer_uuid", issuerUuid != null ? issuerUuid : "CONSOLE");
        json.addProperty("issuer_name", issuerName != null ? issuerName : "Console");
        json.addProperty("reason", reason != null ? reason : "Без причины");
        json.addProperty("duration", duration != null ? duration : "навсегда");
        json.addProperty("duration_ms", durationMs);
        json.addProperty("timestamp", System.currentTimeMillis());
        json.addProperty("expiry", expiry);
        json.addProperty("hidden", hidden);
        if (ip != null && !ip.isEmpty()) {
            json.addProperty("ip", ip);
        }
        json.addProperty("active", true);
        
        return sendRequest("punishments", "POST", json.toString())
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
    // ==== ПОЛУЧЕНИЕ НАКАЗАНИЙ =====
    // =========================================================
    
    // Активные наказания игрока
    public CompletableFuture<List<Map<String, Object>>> getActivePunishments(String playerUuid) {
        long now = System.currentTimeMillis();
        String endpoint = "punishments?player_uuid=eq." + playerUuid + "&active=eq.true&or=expiry.eq.-1,expiry.gt." + now + "&order=timestamp.desc";
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> parsePunishments(response));
    }

    // История наказаний игрока (hist)
    public CompletableFuture<List<Map<String, Object>>> getPunishmentHistory(String playerUuid, int limit) {
        String endpoint = "punishments?player_uuid=eq." + playerUuid + "&order=timestamp.desc&limit=" + limit;
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> parsePunishments(response));
    }

    // Наказания выданные игроком (shist)
    public CompletableFuture<List<Map<String, Object>>> getIssuerHistory(String issuerUuid, int limit) {
        String endpoint = "punishments?issuer_uuid=eq." + issuerUuid + "&order=timestamp.desc&limit=" + limit;
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> parsePunishments(response));
    }

    // Активные баны (banlist)
    public CompletableFuture<List<Map<String, Object>>> getActiveBans() {
        long now = System.currentTimeMillis();
        String endpoint = "punishments?type=eq.ban&active=eq.true&or=expiry.eq.-1,expiry.gt." + now + "&order=timestamp.desc";
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> parsePunishments(response));
    }

    // Активные муты (mutelist)
    public CompletableFuture<List<Map<String, Object>>> getActiveMutes() {
        long now = System.currentTimeMillis();
        String endpoint = "punishments?type=eq.mute&active=eq.true&or=expiry.eq.-1,expiry.gt." + now + "&order=timestamp.desc";
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> parsePunishments(response));
    }

    // Проверка бана
    public CompletableFuture<Map<String, Object>> getActiveBan(String playerUuid) {
        long now = System.currentTimeMillis();
        String endpoint = "punishments?player_uuid=eq." + playerUuid + "&type=eq.ban&active=eq.true&or=expiry.eq.-1,expiry.gt." + now + "&limit=1&order=timestamp.desc";
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> list = parsePunishments(response);
                return list.isEmpty() ? null : list.get(0);
            });
    }

    // Проверка мута
    public CompletableFuture<Map<String, Object>> getActiveMute(String playerUuid) {
        long now = System.currentTimeMillis();
        String endpoint = "punishments?player_uuid=eq." + playerUuid + "&type=eq.mute&active=eq.true&or=expiry.eq.-1,expiry.gt." + now + "&limit=1&order=timestamp.desc";
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> list = parsePunishments(response);
                return list.isEmpty() ? null : list.get(0);
            });
    }

    private List<Map<String, Object>> parsePunishments(String response) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (response == null || response.isEmpty()) return result;
        try {
            JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.get(i).getAsJsonObject();
                Map<String, Object> p = new HashMap<>();
                p.put("id", obj.get("id").getAsLong());
                p.put("player_uuid", obj.get("player_uuid").getAsString());
                p.put("player_name", obj.get("player_name").getAsString());
                p.put("type", obj.get("type").getAsString());
                p.put("issuer_uuid", obj.get("issuer_uuid").getAsString());
                p.put("issuer_name", obj.get("issuer_name").getAsString());
                p.put("reason", obj.get("reason").getAsString());
                p.put("duration", obj.get("duration").getAsString());
                if (obj.has("duration_ms") && !obj.get("duration_ms").isJsonNull()) {
                    p.put("duration_ms", obj.get("duration_ms").getAsLong());
                }
                p.put("timestamp", obj.get("timestamp").getAsLong());
                p.put("expiry", obj.get("expiry").getAsLong());
                p.put("hidden", obj.get("hidden").getAsBoolean());
                if (obj.has("ip") && !obj.get("ip").isJsonNull()) {
                    p.put("ip", obj.get("ip").getAsString());
                }
                p.put("active", obj.get("active").getAsBoolean());
                result.add(p);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка парсинга наказаний: " + e.getMessage());
        }
        return result;
    }

    // =========================================================
    // ==== БАНЫ БОТА (bot_bans) =====
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

    // =========================================================
    // ==== РЕПОРТЫ =====
    // =========================================================
    public CompletableFuture<Long> createReport(String reporterUuid, String reporterName, 
                                                 String targetUuid, String targetName, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("reporter_uuid", reporterUuid);
        json.addProperty("reporter_name", reporterName);
        json.addProperty("target_uuid", targetUuid);
        json.addProperty("target_name", targetName);
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

    public CompletableFuture<Boolean> closeReport(long id, String closedBy) {
        JsonObject json = new JsonObject();
        json.addProperty("closed", true);
        json.addProperty("closed_by", closedBy);
        json.addProperty("closed_at", System.currentTimeMillis());
        return sendRequest("reports?id=eq." + id, "PATCH", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<List<Map<String, Object>>> getReports(boolean onlyActive) {
        String endpoint = "reports" + (onlyActive ? "?closed=eq.false" : "") + "&order=timestamp.desc";
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
                        r.put("reporter_name", obj.get("reporter_name").getAsString());
                        r.put("target_uuid", obj.get("target_uuid").getAsString());
                        r.put("target_name", obj.get("target_name").getAsString());
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

    // =========================================================
    // ==== ТЕХНИЧЕСКИЕ РАБОТЫ =====
    // =========================================================
    public CompletableFuture<Boolean> updateTechWorks(boolean enabled, String reason, String admin, long endTime) {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("reason", reason);
        if (admin != null) json.addProperty("admin", admin);
        json.addProperty("end_time", endTime);
        return sendRequest("techworks?id=eq.1", "PATCH", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Map<String, Object>> getTechWorks() {
        return sendRequest("techworks?limit=1", "GET", null)
            .thenApplyAsync(response -> {
                if (response == null || response.isEmpty()) return null;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    if (arr.isEmpty()) return null;
                    JsonObject obj = arr.get(0).getAsJsonObject();
                    Map<String, Object> result = new HashMap<>();
                    result.put("enabled", obj.get("enabled").getAsBoolean());
                    result.put("reason", obj.get("reason").getAsString());
                    if (obj.has("admin") && !obj.get("admin").isJsonNull()) {
                        result.put("admin", obj.get("admin").getAsString());
                    }
                    result.put("end_time", obj.get("end_time").getAsLong());
                    return result;
                } catch (Exception e) {
                    return null;
                }
            });
    }

    // =========================================================
    // ==== ЧЕРНЫЙ СПИСОК =====
    // =========================================================
    public CompletableFuture<Boolean> addBlacklist(String playerUuid, String playerName, 
                                                    String issuerUuid, String issuerName, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("player_uuid", playerUuid);
        json.addProperty("player_name", playerName);
        json.addProperty("issuer_uuid", issuerUuid);
        json.addProperty("issuer_name", issuerName);
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

    public CompletableFuture<List<Map<String, Object>>> getBlacklist() {
        return sendRequest("blacklist", "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> b = new HashMap<>();
                        b.put("player_uuid", obj.get("player_uuid").getAsString());
                        b.put("player_name", obj.get("player_name").getAsString());
                        b.put("issuer_name", obj.get("issuer_name").getAsString());
                        b.put("reason", obj.get("reason").getAsString());
                        b.put("timestamp", obj.get("timestamp").getAsLong());
                        result.add(b);
                    }
                } catch (Exception e) {}
                return result;
            });
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
                if (response == null || response.isEmpty() || response.equals("[]")) {
                    return sendRequest("bots", "POST", json.toString())
                        .thenApply(r -> r != null).join();
                }
                return true;
            });
    }

    public CompletableFuture<Boolean> deleteBot(String name) {
        return sendRequest("bots?bot_name=eq." + name, "DELETE", null)
            .thenApplyAsync(r -> r != null);
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
                        result.add(bot);
                    }
                } catch (Exception e) {}
                return result;
            });
    }
}
