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
                String fullUrl = supabaseUrl + "/rest/v1/" + endpoint;
                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("apikey", supabaseKey);
                conn.setRequestProperty("Authorization", "Bearer " + supabaseKey);
                conn.setDoInput(true);

                if (method.equals("PATCH")) {
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
                } else {
                    conn.setRequestMethod(method);
                }
                
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
    // ==== НАКАЗАНИЯ =====
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
    // ==== ПОЛУЧЕНИЕ НАКАЗАНИЙ (ИСПРАВЛЕНО) =====
    // =========================================================
    
    public CompletableFuture<List<Map<String, Object>>> getActiveBans() {
        long now = System.currentTimeMillis();
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
        long now = System.currentTimeMillis();
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
        long now = System.currentTimeMillis();
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
        long now = System.currentTimeMillis();
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
}
