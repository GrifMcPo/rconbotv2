package com.grifmcpo.consolebot;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final JavaPlugin plugin;
    private File authFile;
    private FileConfiguration authConfig;
    private final Map<String, AuthData> authData = new ConcurrentHashMap<>();
    private final Map<String, String> pendingCodes = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingCodeExpiry = new ConcurrentHashMap<>();
    private final Map<String, List<String>> bannedIps = new ConcurrentHashMap<>();
    private final Map<String, Long> banExpiry = new ConcurrentHashMap<>();

    private static final long SESSION_DURATION = 5 * 60 * 60 * 1000;
    private static final long BAN_DURATION = 10 * 60 * 60 * 1000;

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

        authData.clear();
        for (String playerName : authConfig.getKeys(false)) {
            AuthData data = new AuthData();
            data.telegramId = authConfig.getString(playerName + ".telegramId");
            data.ip = authConfig.getString(playerName + ".ip");
            data.sessionStart = authConfig.getLong(playerName + ".sessionStart", 0);
            data.blocked = authConfig.getBoolean(playerName + ".blocked", false);
            
            List<String> ips = authConfig.getStringList(playerName + ".bannedIps");
            if (ips != null && !ips.isEmpty()) {
                bannedIps.put(playerName, ips);
                banExpiry.put(playerName, authConfig.getLong(playerName + ".banExpiry", 0));
            }
            
            if (data.telegramId != null) {
                authData.put(playerName, data);
            }
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
            authConfig.set(playerName + ".blocked", data.blocked);
        }
        
        for (Map.Entry<String, List<String>> entry : bannedIps.entrySet()) {
            authConfig.set(entry.getKey() + ".bannedIps", entry.getValue());
            authConfig.set(entry.getKey() + ".banExpiry", banExpiry.get(entry.getKey()));
        }
        
        try {
            authConfig.save(authFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка сохранения auth.yml: " + e.getMessage());
        }
    }

    public String generateCode(String playerName) {
        String code = String.format("%05d", new Random().nextInt(100000));
        pendingCodes.put(playerName, code);
        pendingCodeExpiry.put(playerName, System.currentTimeMillis() + 5 * 60 * 1000);
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

    public boolean linkAccount(String playerName, String telegramId, String ip) {
        for (Map.Entry<String, AuthData> entry : authData.entrySet()) {
            if (entry.getValue().telegramId != null && entry.getValue().telegramId.equals(telegramId)) {
                return false;
            }
        }

        if (authData.containsKey(playerName) && authData.get(playerName).telegramId != null) {
            return false;
        }

        AuthData data = authData.getOrDefault(playerName, new AuthData());
        data.telegramId = telegramId;
        data.ip = ip;
        data.sessionStart = System.currentTimeMillis();
        data.blocked = false;
        authData.put(playerName, data);
        saveAuthData();
        return true;
    }

    public boolean unlinkAccount(String playerName) {
        if (!authData.containsKey(playerName)) return false;
        AuthData data = authData.get(playerName);
        data.telegramId = null;
        data.blocked = false;
        saveAuthData();
        return true;
    }

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

    public boolean toggleBlock(String playerName) {
        AuthData data = authData.get(playerName);
        if (data == null || data.telegramId == null) return false;
        data.blocked = !data.blocked;
        saveAuthData();
        return data.blocked;
    }

    public boolean isBlocked(String playerName) {
        AuthData data = authData.get(playerName);
        return data != null && data.blocked;
    }

    public boolean isSessionValid(String playerName) {
        AuthData data = authData.get(playerName);
        if (data == null || data.telegramId == null) return true;
        if (data.sessionStart == 0) return false;
        if (data.blocked) return false;
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

    public boolean banIp(String playerName, String ip) {
        AuthData data = authData.get(playerName);
        if (data == null) return false;
        
        List<String> ips = bannedIps.getOrDefault(playerName, new ArrayList<>());
        if (!ips.contains(ip)) {
            ips.add(ip);
            bannedIps.put(playerName, ips);
            banExpiry.put(playerName, System.currentTimeMillis() + BAN_DURATION);
            saveAuthData();
        }
        return true;
    }

    public boolean isIpBanned(String playerName, String ip) {
        List<String> ips = bannedIps.get(playerName);
        if (ips == null) return false;
        
        Long expiry = banExpiry.get(playerName);
        if (expiry != null && System.currentTimeMillis() > expiry) {
            bannedIps.remove(playerName);
            banExpiry.remove(playerName);
            saveAuthData();
            return false;
        }
        
        return ips.contains(ip);
    }

    public void unbanIp(String playerName, String ip) {
        List<String> ips = bannedIps.get(playerName);
        if (ips != null) {
            ips.remove(ip);
            if (ips.isEmpty()) {
                bannedIps.remove(playerName);
                banExpiry.remove(playerName);
            }
            saveAuthData();
        }
    }

    public long getBanTimeLeft(String playerName) {
        Long expiry = banExpiry.get(playerName);
        if (expiry == null) return 0;
        long left = expiry - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

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

    public static class AuthData {
        public String telegramId;
        public String ip;
        public long sessionStart;
        public boolean blocked;
    }
}
