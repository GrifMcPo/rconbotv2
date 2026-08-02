package com.grifmcpo.consolebot;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final JavaPlugin plugin;
    private File authFile;
    private FileConfiguration authConfig;
    private final Map<String, AuthData> authData = new ConcurrentHashMap<>();
    private final Map<String, String> pendingCodes = new ConcurrentHashMap<>(); // playerName -> code
    private final Map<String, Long> pendingCodeExpiry = new ConcurrentHashMap<>(); // playerName -> expiry

    public AuthManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadAuthData();
    }

    private void loadAuthData() {
        authFile = new File(plugin.getDataFolder(), "auth.yml");
        if (!authFile.exists()) {
            try {
                authFile.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().severe("Не удалось создать auth.yml");
            }
        }
        authConfig = YamlConfiguration.loadConfiguration(authFile);

        for (String playerName : authConfig.getKeys(false)) {
            AuthData data = new AuthData();
            data.telegramId = authConfig.getString(playerName + ".telegramId");
            data.ip = authConfig.getString(playerName + ".ip");
            data.sessionStart = authConfig.getLong(playerName + ".sessionStart", 0);
            data.hwid = authConfig.getString(playerName + ".hwid", "—");
            data.bannedIps = authConfig.getStringList(playerName + ".bannedIps");
            if (data.bannedIps == null) data.bannedIps = new ArrayList<>();
            data.banExpiry = authConfig.getLong(playerName + ".banExpiry", 0);
            authData.put(playerName, data);
        }
        plugin.getLogger().info("Загружено привязанных аккаунтов: " + authData.size());
    }

    public void saveAuthData() {
        for (Map.Entry<String, AuthData> entry : authData.entrySet()) {
            String playerName = entry.getKey();
            AuthData data = entry.getValue();
            authConfig.set(playerName + ".telegramId", data.telegramId);
            authConfig.set(playerName + ".ip", data.ip);
            authConfig.set(playerName + ".sessionStart", data.sessionStart);
            authConfig.set(playerName + ".hwid", data.hwid);
            authConfig.set(playerName + ".bannedIps", data.bannedIps);
            authConfig.set(playerName + ".banExpiry", data.banExpiry);
        }
        try {
            authConfig.save(authFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка сохранения auth.yml: " + e.getMessage());
        }
    }

    // ============================================
    // ==== ГЕНЕРАЦИЯ КОДА =====
    // ============================================
    public String generateCode(String playerName) {
        String code = String.format("%05d", new Random().nextInt(100000));
        pendingCodes.put(playerName, code);
        pendingCodeExpiry.put(playerName, System.currentTimeMillis() + 5 * 60 * 1000); // 5 минут
        plugin.getLogger().info("Код для " + playerName + ": " + code);
        return code;
    }

    public boolean verifyCode(String playerName, String code) {
        String savedCode = pendingCodes.get(playerName);
        Long expiry = pendingCodeExpiry.get(playerName);
        
        if (savedCode == null || expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            pendingCodes.remove(playerName);
            pendingCodeExpiry.remove(playerName);
            return false;
        }
        if (!savedCode.equals(code)) return false;
        
        pendingCodes.remove(playerName);
        pendingCodeExpiry.remove(playerName);
        return true;
    }

    // ============================================
    // ==== ПРИВЯЗКА АККАУНТА =====
    // ============================================
    public boolean linkAccount(String playerName, String telegramId, String ip) {
        // Проверка: не привязан ли уже этот Telegram ID к другому аккаунту
        for (Map.Entry<String, AuthData> entry : authData.entrySet()) {
            if (entry.getValue().telegramId != null && entry.getValue().telegramId.equals(telegramId)) {
                return false; // Telegram ID уже используется
            }
        }

        // Проверка: не привязан ли уже этот аккаунт
        if (authData.containsKey(playerName) && authData.get(playerName).telegramId != null) {
            return false; // Аккаунт уже привязан
        }

        AuthData data = authData.getOrDefault(playerName, new AuthData());
        data.telegramId = telegramId;
        data.ip = ip;
        data.sessionStart = System.currentTimeMillis();
        data.hwid = "—";
        data.bannedIps = new ArrayList<>();
        data.banExpiry = 0;
        authData.put(playerName, data);
        saveAuthData();
        return true;
    }

    public boolean unlinkAccount(String playerName) {
        if (!authData.containsKey(playerName)) return false;
        authData.get(playerName).telegramId = null;
        saveAuthData();
        return true;
    }

    // ============================================
    // ==== ПРОВЕРКА СТАТУСА =====
    // ============================================
    public boolean isLinked(String playerName) {
        AuthData data = authData.get(playerName);
        return data != null && data.telegramId != null && !data.telegramId.isEmpty();
    }

    public String getTelegramId(String playerName) {
        AuthData data = authData.get(playerName);
        return data != null ? data.telegramId : null;
    }

    public String getPlayerNameByTelegram(String telegramId) {
        for (Map.Entry<String, AuthData> entry : authData.entrySet()) {
            if (entry.getValue().telegramId != null && entry.getValue().telegramId.equals(telegramId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public AuthData getAuthData(String playerName) {
        return authData.get(playerName);
    }

    public Set<String> getLinkedPlayers() {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, AuthData> entry : authData.entrySet()) {
            if (entry.getValue().telegramId != null && !entry.getValue().telegramId.isEmpty()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    // ============================================
    // ==== СЕССИЯ =====
    // ============================================
    private static final long SESSION_DURATION = 5 * 60 * 60 * 1000; // 5 часов

    public boolean isSessionValid(String playerName) {
        AuthData data = authData.get(playerName);
        if (data == null || data.telegramId == null) return true; // Не привязан — пропускаем
        if (data.sessionStart == 0) return false;
        return (System.currentTimeMillis() - data.sessionStart) < SESSION_DURATION;
    }

    public void refreshSession(String playerName) {
        AuthData data = authData.get(playerName);
        if (data != null) {
            data.sessionStart = System.currentTimeMillis();
            saveAuthData();
        }
    }

    public long getSessionTimeLeft(String playerName) {
        AuthData data = authData.get(playerName);
        if (data == null || data.sessionStart == 0) return 0;
        long elapsed = System.currentTimeMillis() - data.sessionStart;
        long left = SESSION_DURATION - elapsed;
        return left > 0 ? left : 0;
    }

    // ============================================
    // ==== IP УПРАВЛЕНИЕ =====
    // ============================================
    public String getLinkedIp(String playerName) {
        AuthData data = authData.get(playerName);
        return data != null ? data.ip : null;
    }

    public void updateIp(String playerName, String ip) {
        AuthData data = authData.get(playerName);
        if (data != null) {
            data.ip = ip;
            saveAuthData();
        }
    }

    public boolean isNewIp(String playerName, String ip) {
        String savedIp = getLinkedIp(playerName);
        return savedIp != null && !savedIp.equals(ip);
    }

    // ============================================
    // ==== БАН IP НА 10 ЧАСОВ =====
    // ============================================
    private static final long BAN_DURATION = 10 * 60 * 60 * 1000; // 10 часов

    public boolean banIp(String playerName, String ip) {
        AuthData data = authData.get(playerName);
        if (data == null) return false;
        
        if (!data.bannedIps.contains(ip)) {
            data.bannedIps.add(ip);
            data.banExpiry = System.currentTimeMillis() + BAN_DURATION;
            saveAuthData();
        }
        return true;
    }

    public boolean isIpBanned(String playerName, String ip) {
        AuthData data = authData.get(playerName);
        if (data == null) return false;
        
        // Проверяем, не истек ли бан
        if (data.banExpiry > 0 && System.currentTimeMillis() > data.banExpiry) {
            data.bannedIps.clear();
            data.banExpiry = 0;
            saveAuthData();
            return false;
        }
        
        return data.bannedIps.contains(ip);
    }

    public void unbanIp(String playerName, String ip) {
        AuthData data = authData.get(playerName);
        if (data != null) {
            data.bannedIps.remove(ip);
            data.banExpiry = 0;
            saveAuthData();
        }
    }

    public long getBanTimeLeft(String playerName) {
        AuthData data = authData.get(playerName);
        if (data == null || data.banExpiry == 0) return 0;
        long left = data.banExpiry - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    // ============================================
    // ==== ВСПОМОГАТЕЛЬНЫЙ МЕТОД ДЛЯ ПОЛУЧЕНИЯ ВРЕМЕНИ =====
    // ============================================
    public String formatTimeLeft(long millis) {
        if (millis <= 0) return "0 секунд";
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds %= 60;
        minutes %= 60;
        
        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append(" ч ");
        if (minutes > 0) sb.append(minutes).append(" мин ");
        if (seconds > 0 && hours == 0) sb.append(seconds).append(" сек");
        return sb.toString().trim();
    }

    // ============================================
    // ==== КЛАСС ДАННЫХ =====
    // ============================================
    public static class AuthData {
        public String telegramId;
        public String ip;
        public long sessionStart;
        public String hwid;
        public List<String> bannedIps;
        public long banExpiry;
    }
}
