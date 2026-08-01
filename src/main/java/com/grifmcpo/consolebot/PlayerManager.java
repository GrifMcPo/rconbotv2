package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlayerManager {

    private final JavaPlugin plugin;
    private File authFile;
    private FileConfiguration authConfig;
    private final Map<String, String> pendingCodes = new HashMap<>();
    private final Map<UUID, String> playerSessions = new HashMap<>();
    private final Map<String, Long> codeTimestamps = new HashMap<>();

    public PlayerManager(JavaPlugin plugin) {
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
    }

    public void saveAuthData() {
        try {
            authConfig.save(authFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка сохранения auth.yml");
        }
    }

    public String generateCode(String playerName) {
        String code = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        pendingCodes.put(code, playerName);
        codeTimestamps.put(code, System.currentTimeMillis());
        plugin.getLogger().info("Код для " + playerName + ": " + code);
        return code;
    }

    public boolean registerPlayer(String code, String telegramId) {
        if (!pendingCodes.containsKey(code)) return false;

        Long timestamp = codeTimestamps.get(code);
        if (timestamp == null || (System.currentTimeMillis() - timestamp) > 5 * 60 * 1000) {
            pendingCodes.remove(code);
            codeTimestamps.remove(code);
            return false;
        }

        String playerName = pendingCodes.remove(code);
        codeTimestamps.remove(code);
        UUID uuid = Bukkit.getPlayerUniqueId(playerName);
        if (uuid == null) {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player != null) uuid = player.getUniqueId();
            else return false;
        }

        authConfig.set(playerName + ".telegramId", telegramId);
        authConfig.set(playerName + ".uuid", uuid.toString());
        authConfig.set(playerName + ".ip", getPlayerIP(playerName));
        authConfig.set(playerName + ".sessionTime", System.currentTimeMillis());
        authConfig.set(playerName + ".registered", true);
        saveAuthData();
        playerSessions.put(uuid, telegramId);
        return true;
    }

    public boolean isRegistered(String playerName) {
        return authConfig.getBoolean(playerName + ".registered", false);
    }

    public boolean isRegistered(UUID uuid) {
        return playerSessions.containsKey(uuid);
    }

    public String getTelegramId(String playerName) {
        return authConfig.getString(playerName + ".telegramId");
    }

    public String getPlayerNameByTelegram(String telegramId) {
        if (telegramId == null) return null;
        for (String key : authConfig.getKeys(false)) {
            String id = authConfig.getString(key + ".telegramId");
            if (id != null && id.equals(telegramId)) return key;
        }
        return null;
    }

    public boolean isSessionValid(String playerName) {
        long sessionTime = authConfig.getLong(playerName + ".sessionTime", 0);
        return (System.currentTimeMillis() - sessionTime) < 12 * 60 * 60 * 1000;
    }

    public void refreshSession(String playerName) {
        authConfig.set(playerName + ".sessionTime", System.currentTimeMillis());
        saveAuthData();
    }

    public boolean isIPMatch(String playerName, String ip) {
        String savedIP = authConfig.getString(playerName + ".ip");
        return savedIP != null && savedIP.equals(ip);
    }

    public void updateIP(String playerName, String ip) {
        authConfig.set(playerName + ".ip", ip);
        saveAuthData();
    }

    public String getPlayerIp(String playerName) {
        String ip = authConfig.getString(playerName + ".ip");
        if (ip != null && !ip.isEmpty() && !ip.equals("0.0.0.0")) {
            return ip;
        }
        
        // Если IP не найден в auth.yml, пробуем через другие источники
        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null && player.isOnline() && player.getAddress() != null) {
            return player.getAddress().getHostString();
        }
        
        // Пробуем через usercache.json
        try {
            File userCache = new File("usercache.json");
            if (userCache.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(userCache.toPath()));
                // Ищем IP в логах
                File logsDir = new File("logs");
                if (logsDir.exists()) {
                    for (File file : logsDir.listFiles()) {
                        if (file.getName().endsWith(".log") || file.getName().endsWith(".txt")) {
                            try {
                                String logContent = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                                String[] lines = logContent.split("\n");
                                for (int i = lines.length - 1; i >= 0; i--) {
                                    if (lines[i].contains("logged in") && lines[i].contains(playerName)) {
                                        // Ищем IP в строке логина
                                        String[] parts = lines[i].split(" ");
                                        for (String part : parts) {
                                            if (part.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                                                return part;
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {}
                        }
                    }
                }
            }
        } catch (Exception e) {}
        
        return "—";
    }

    public List<String> getPlayersByIp(String ip) {
        List<String> players = new ArrayList<>();
        
        // Проверяем auth.yml
        for (String key : authConfig.getKeys(false)) {
            String savedIp = authConfig.getString(key + ".ip");
            if (ip.equals(savedIp)) {
                if (!players.contains(key)) {
                    players.add(key);
                }
            }
        }
        
        // Проверяем онлайн игроков
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getAddress() != null && ip.equals(player.getAddress().getHostString())) {
                if (!players.contains(player.getName())) {
                    players.add(player.getName());
                }
            }
        }
        
        // Проверяем логи
        try {
            File logsDir = new File("logs");
            if (logsDir.exists()) {
                for (File file : logsDir.listFiles()) {
                    if (file.getName().endsWith(".log") || file.getName().endsWith(".txt")) {
                        try {
                            String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                            String[] lines = content.split("\n");
                            for (String line : lines) {
                                if (line.contains(ip) && line.contains("logged in")) {
                                    String playerName = extractPlayerNameFromLog(line);
                                    if (playerName != null && !players.contains(playerName)) {
                                        players.add(playerName);
                                    }
                                }
                            }
                        } catch (Exception e) {}
                    }
                }
            }
        } catch (Exception e) {}
        
        return players;
    }

    private String extractPlayerNameFromLog(String logLine) {
        try {
            String[] parts = logLine.split(" ");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equalsIgnoreCase("player") || parts[i].equalsIgnoreCase("Player")) {
                    if (i + 1 < parts.length) {
                        String name = parts[i + 1];
                        if (name != null && !name.isEmpty()) {
                            return name;
                        }
                    }
                }
            }
            // Альтернативный поиск
            for (String part : parts) {
                if (part.contains("logged in") && part.contains("/")) {
                    String[] subParts = part.split("/");
                    if (subParts.length > 0) {
                        return subParts[0];
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private String getPlayerIP(String playerName) {
        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null && player.getAddress() != null) {
            return player.getAddress().getHostString();
        }
        return "0.0.0.0";
    }

    public void unregister(String playerName) {
        authConfig.set(playerName, null);
        playerSessions.entrySet().removeIf(entry -> {
            String name = getPlayerNameByTelegram(entry.getValue());
            return name != null && name.equals(playerName);
        });
        saveAuthData();
    }

    public void kickAccount(String playerName) {
        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null && player.isOnline()) {
            player.kickPlayer("Аккаунт был исключен с бота");
        }
    }
}
