package com.grifmcpo.consolebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
                String fullUrl = supabaseUrl + "/rest/v1/" + endpoint;
                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("apikey", supabaseKey);
                conn.setRequestProperty("Authorization", "Bearer " + supabaseKey);
                conn.setDoInput(true);

                conn.setRequestMethod(method);

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
                    plugin.getLogger().warning("❌ Supabase ошибка: " + responseCode + " - " + error);
                    return null;
                }
            } catch (Exception e) {
                plugin.getLogger().severe("❌ Supabase request error: " + e.getMessage());
                return null;
            }
        });
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            return value;
        }
    }

    // =========================================================
    // ==== ИГРОКИ =====
    // =========================================================
    public CompletableFuture<String> getOrCreatePlayer(String playerName, String ip) {
        return getPlayerUuidByName(playerName).thenComposeAsync(uuid -> {
            if (uuid != null) {
                return CompletableFuture.completedFuture(uuid);
            }

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
        return sendRequest("players?player_name=eq." + encode(name), "GET", null)
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

    public CompletableFuture<Boolean> setPlayerTelegramId(String uuid, String telegramId) {
        JsonObject json = new JsonObject();
        json.addProperty("telegram_id", telegramId);
        return sendRequest("players?uuid=eq." + uuid, "PATCH", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Boolean> setPlayerBlocked(String uuid, boolean blocked) {
        JsonObject json = new JsonObject();
        json.addProperty("blocked", blocked);
        return sendRequest("players?uuid=eq." + uuid, "PATCH", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Boolean> isPlayerBlocked(String uuid) {
        return sendRequest("players?uuid=eq." + uuid + "&select=blocked", "GET", null)
            .thenApplyAsync(response -> {
                if (response == null || response.isEmpty()) return false;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    if (arr.isEmpty()) return false;
                    return arr.get(0).getAsJsonObject().get("blocked").getAsBoolean();
                } catch (Exception e) {
                    return false;
                }
            });
    }

    // =========================================================
    // ==== НАКАЗАНИЯ =====
    // =========================================================
    public CompletableFuture<Long> addPunishment(
            String playerUuid, String playerName,
            String type, String issuerUuid, String issuerName,
            String reason, String duration, long durationMs, long expiry,
            boolean hidden, String ip) {

        JsonObject json = new JsonObject();
        if (playerUuid == null || playerUuid.isEmpty()) {
            plugin.getLogger().severe("❌ addPunishment: player_uuid is null for " + playerName + ", type=" + type);
            return CompletableFuture.completedFuture(-1L);
        }
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

    public CompletableFuture<List<Map<String, Object>>> getActiveBans() {
        String endpoint = "punishments?type=eq.ban&active=eq.true&order=timestamp.desc";
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = parsePunishments(response);
                result.removeIf(p -> {
                    long expiry = (Long) p.get("expiry");
                    return expiry != -1 && expiry <= System.currentTimeMillis();
                });
                return result;
            });
    }

    public CompletableFuture<List<Map<String, Object>>> getActiveMutes() {
        String endpoint = "punishments?type=eq.mute&active=eq.true&order=timestamp.desc";
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = parsePunishments(response);
                result.removeIf(p -> {
                    long expiry = (Long) p.get("expiry");
                    return expiry != -1 && expiry <= System.currentTimeMillis();
                });
                return result;
            });
    }

    public CompletableFuture<Map<String, Object>> getActiveBan(String playerUuid) {
        String endpoint = "punishments?player_uuid=eq." + playerUuid +
                          "&type=eq.ban&active=eq.true&limit=1&order=timestamp.desc";
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> list = parsePunishments(response);
                if (list.isEmpty()) return null;
                Map<String, Object> ban = list.get(0);
                long expiry = (Long) ban.get("expiry");
                if (expiry != -1 && expiry <= System.currentTimeMillis()) {
                    return null;
                }
                return ban;
            });
    }

    public CompletableFuture<Map<String, Object>> getActiveMute(String playerUuid) {
        String endpoint = "punishments?player_uuid=eq." + playerUuid +
                          "&type=eq.mute&active=eq.true&limit=1&order=timestamp.desc";
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> list = parsePunishments(response);
                if (list.isEmpty()) return null;
                Map<String, Object> mute = list.get(0);
                long expiry = (Long) mute.get("expiry");
                if (expiry != -1 && expiry <= System.currentTimeMillis()) {
                    return null;
                }
                return mute;
            });
    }

    // =========================================================
    // ==== ИСТОРИЯ =====
    // =========================================================
    public CompletableFuture<List<Map<String, Object>>> getPunishmentHistory(String playerUuid, int limit) {
        String endpoint = "punishments?player_uuid=eq." + playerUuid + "&order=timestamp.desc&limit=" + limit;
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> parsePunishments(response));
    }

    public CompletableFuture<List<Map<String, Object>>> getIssuerHistory(String issuerUuid, int limit) {
        String endpoint = "punishments?issuer_uuid=eq." + issuerUuid + "&order=timestamp.desc&limit=" + limit;
        return sendRequest(endpoint, "GET", null)
            .thenApplyAsync(response -> parsePunishments(response));
    }

    // =========================================================
    // ==== ПАРСИНГ =====
    // =========================================================
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
            plugin.getLogger().severe("❌ Ошибка парсинга: " + e.getMessage());
        }
        return result;
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

    // =========================================================
    // ==== БАНЫ БОТА =====
    // =========================================================
    public CompletableFuture<Boolean> addBotBan(String telegramId, String playerUuid, String reason, String issuer, long timestamp, long expiry) {
        JsonObject json = new JsonObject();
        json.addProperty("telegram_id", telegramId);
        if (playerUuid != null) json.addProperty("player_uuid", playerUuid);
        if (reason != null) json.addProperty("reason", reason);
        if (issuer != null) json.addProperty("issuer", issuer);
        json.addProperty("timestamp", timestamp);
        json.addProperty("expiry", expiry);
        return sendRequest("bot_bans", "POST", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Boolean> removeBotBan(String telegramId) {
        return sendRequest("bot_bans?telegram_id=eq." + encode(telegramId), "DELETE", null)
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Map<String, Object>> getBotBan(String telegramId) {
        return sendRequest("bot_bans?telegram_id=eq." + encode(telegramId) + "&limit=1", "GET", null)
            .thenApplyAsync(response -> {
                if (response == null || response.isEmpty()) return null;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    if (arr.isEmpty()) return null;
                    JsonObject obj = arr.get(0).getAsJsonObject();
                    Map<String, Object> ban = new HashMap<>();
                    ban.put("telegram_id", obj.get("telegram_id").getAsString());
                    if (obj.has("reason") && !obj.get("reason").isJsonNull()) ban.put("reason", obj.get("reason").getAsString());
                    if (obj.has("issuer") && !obj.get("issuer").isJsonNull()) ban.put("issuer", obj.get("issuer").getAsString());
                    ban.put("timestamp", obj.get("timestamp").getAsLong());
                    ban.put("expiry", obj.get("expiry").getAsLong());
                    return ban;
                } catch (Exception e) {
                    return null;
                }
            });
    }

    public CompletableFuture<List<Map<String, Object>>> getAllBotBans() {
        return sendRequest("bot_bans?order=timestamp.desc", "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> ban = new HashMap<>();
                        ban.put("telegram_id", obj.get("telegram_id").getAsString());
                        if (obj.has("reason") && !obj.get("reason").isJsonNull()) ban.put("reason", obj.get("reason").getAsString());
                        if (obj.has("issuer") && !obj.get("issuer").isJsonNull()) ban.put("issuer", obj.get("issuer").getAsString());
                        ban.put("timestamp", obj.get("timestamp").getAsLong());
                        ban.put("expiry", obj.get("expiry").getAsLong());
                        result.add(ban);
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("❌ Ошибка парсинга bot_bans: " + e.getMessage());
                }
                return result;
            });
    }

    // =========================================================
    // ==== РЕПОРТЫ =====
    // =========================================================
    public CompletableFuture<Long> addReport(String reporterUuid, String reporterName, String targetUuid, String targetName, String reason, long timestamp) {
        JsonObject json = new JsonObject();
        json.addProperty("reporter_uuid", reporterUuid);
        json.addProperty("reporter_name", reporterName);
        json.addProperty("target_uuid", targetUuid);
        json.addProperty("target_name", targetName);
        json.addProperty("reason", reason);
        json.addProperty("timestamp", timestamp);
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

    public CompletableFuture<Integer> closeAllReports(String closedBy) {
        JsonObject json = new JsonObject();
        json.addProperty("closed", true);
        json.addProperty("closed_by", closedBy);
        json.addProperty("closed_at", System.currentTimeMillis());
        return sendRequest("reports?closed=eq.false", "PATCH", json.toString())
            .thenApplyAsync(r -> r != null ? 1 : 0);
    }

    public CompletableFuture<List<Map<String, Object>>> getActiveReports() {
        return sendRequest("reports?closed=eq.false&order=timestamp.desc", "GET", null)
            .thenApplyAsync(response -> parseReports(response, false));
    }

    public CompletableFuture<List<Map<String, Object>>> getAllReports() {
        return sendRequest("reports?order=timestamp.desc", "GET", null)
            .thenApplyAsync(response -> parseReports(response, true));
    }

    private List<Map<String, Object>> parseReports(String response, boolean includeClosed) {
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
                if (obj.has("closed_at") && !obj.get("closed_at").isJsonNull()) {
                    r.put("closed_at", obj.get("closed_at").getAsLong());
                }
                result.add(r);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Ошибка парсинга reports: " + e.getMessage());
        }
        return result;
    }

    // =========================================================
    // ==== ЧЕРНЫЙ СПИСОК =====
    // =========================================================
    public CompletableFuture<Boolean> addBlacklist(String playerUuid, String playerName, String issuerUuid, String issuerName, String reason, long timestamp) {
        JsonObject json = new JsonObject();
        json.addProperty("player_uuid", playerUuid);
        json.addProperty("player_name", playerName);
        json.addProperty("issuer_uuid", issuerUuid);
        json.addProperty("issuer_name", issuerName);
        if (reason != null) json.addProperty("reason", reason);
        json.addProperty("timestamp", timestamp);
        return sendRequest("blacklist", "POST", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Boolean> removeBlacklist(String playerUuid) {
        return sendRequest("blacklist?player_uuid=eq." + playerUuid, "DELETE", null)
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<List<Map<String, Object>>> getBlacklist() {
        return sendRequest("blacklist?order=timestamp.desc", "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> e = new HashMap<>();
                        e.put("player_uuid", obj.get("player_uuid").getAsString());
                        e.put("player_name", obj.get("player_name").getAsString());
                        e.put("issuer_uuid", obj.get("issuer_uuid").getAsString());
                        e.put("issuer_name", obj.get("issuer_name").getAsString());
                        if (obj.has("reason") && !obj.get("reason").isJsonNull()) {
                            e.put("reason", obj.get("reason").getAsString());
                        }
                        e.put("timestamp", obj.get("timestamp").getAsLong());
                        result.add(e);
                    }
                } catch (Exception ex) {
                    plugin.getLogger().severe("❌ Ошибка парсинга blacklist: " + ex.getMessage());
                }
                return result;
            });
    }

    // =========================================================
    // ==== ТЕХНИЧЕСКИЕ РАБОТЫ =====
    // =========================================================
    public CompletableFuture<Boolean> setTechWorks(boolean enabled, String reason, String admin, long startTime, long endTime, boolean autoStartEnabled, long autoStartTime, String autoStartReason) {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("reason", reason != null ? reason : "Технические работы на сервере!");
        if (admin != null) json.addProperty("admin", admin);
        if (startTime > 0) json.addProperty("start_time", startTime);
        json.addProperty("end_time", endTime);
        json.addProperty("auto_start_enabled", autoStartEnabled);
        json.addProperty("auto_start_time", autoStartTime);
        if (autoStartReason != null) json.addProperty("auto_start_reason", autoStartReason);
        return sendRequest("techworks?id=eq.1", "PATCH", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Boolean> initTechWorks() {
        JsonObject json = new JsonObject();
        json.addProperty("id", 1);
        json.addProperty("enabled", false);
        json.addProperty("reason", "Технические работы на сервере!");
        json.addProperty("end_time", -1);
        json.addProperty("auto_start_enabled", false);
        json.addProperty("auto_start_time", -1);
        return sendRequest("techworks", "POST", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Map<String, Object>> getTechWorks() {
        return sendRequest("techworks?id=eq.1&limit=1", "GET", null)
            .thenApplyAsync(response -> {
                if (response == null || response.isEmpty()) return null;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    if (arr.isEmpty()) return null;
                    JsonObject obj = arr.get(0).getAsJsonObject();
                    Map<String, Object> tw = new HashMap<>();
                    tw.put("enabled", obj.get("enabled").getAsBoolean());
                    tw.put("reason", obj.get("reason").getAsString());
                    if (obj.has("admin") && !obj.get("admin").isJsonNull()) tw.put("admin", obj.get("admin").getAsString());
                    if (obj.has("start_time") && !obj.get("start_time").isJsonNull()) tw.put("start_time", obj.get("start_time").getAsLong());
                    tw.put("end_time", obj.get("end_time").getAsLong());
                    tw.put("auto_start_enabled", obj.get("auto_start_enabled").getAsBoolean());
                    tw.put("auto_start_time", obj.get("auto_start_time").getAsLong());
                    if (obj.has("auto_start_reason") && !obj.get("auto_start_reason").isJsonNull()) tw.put("auto_start_reason", obj.get("auto_start_reason").getAsString());
                    return tw;
                } catch (Exception e) {
                    return null;
                }
            });
    }

    // =========================================================
    // ==== БОТЫ (NPC) =====
    // =========================================================
    public CompletableFuture<Boolean> addBot(String botName, String uuid, boolean active, String world, double x, double y, double z, float yaw, float pitch) {
        JsonObject json = new JsonObject();
        json.addProperty("bot_name", botName);
        json.addProperty("uuid", uuid);
        json.addProperty("active", active);
        json.addProperty("world", world != null ? world : "world");
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);
        json.addProperty("yaw", yaw);
        json.addProperty("pitch", pitch);
        return sendRequest("bots", "POST", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Boolean> updateBot(String botName, boolean active, String world, double x, double y, double z, float yaw, float pitch) {
        JsonObject json = new JsonObject();
        json.addProperty("active", active);
        if (world != null) json.addProperty("world", world);
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);
        json.addProperty("yaw", yaw);
        json.addProperty("pitch", pitch);
        return sendRequest("bots?bot_name=eq." + encode(botName), "PATCH", json.toString())
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<Boolean> deleteBot(String botName) {
        return sendRequest("bots?bot_name=eq." + encode(botName), "DELETE", null)
            .thenApplyAsync(r -> r != null);
    }

    public CompletableFuture<List<Map<String, Object>>> getAllBots() {
        return sendRequest("bots?order=bot_name.asc", "GET", null)
            .thenApplyAsync(response -> {
                List<Map<String, Object>> result = new ArrayList<>();
                if (response == null || response.isEmpty()) return result;
                try {
                    JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        Map<String, Object> b = new HashMap<>();
                        b.put("bot_name", obj.get("bot_name").getAsString());
                        b.put("uuid", obj.get("uuid").getAsString());
                        b.put("active", obj.get("active").getAsBoolean());
                        b.put("world", obj.get("world").getAsString());
                        b.put("x", obj.get("x").getAsDouble());
                        b.put("y", obj.get("y").getAsDouble());
                        b.put("z", obj.get("z").getAsDouble());
                        b.put("yaw", obj.get("yaw").getAsFloat());
                        b.put("pitch", obj.get("pitch").getAsFloat());
                        result.add(b);
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("❌ Ошибка парсинга bots: " + e.getMessage());
                }
                return result;
            });
    }
}
