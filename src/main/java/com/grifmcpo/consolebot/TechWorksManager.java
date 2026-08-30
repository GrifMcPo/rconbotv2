package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TechWorksManager implements Listener {

    private final TelegramConsoleBot plugin;
    private File techFile;
    private FileConfiguration techConfig;
    
    private boolean techMode = false;
    private String kickReason = "Технические работы на сервере!";
    private long endTime = -1;
    private long autoStartTime = -1;
    private String autoStartReason = "";
    private boolean autoStartEnabled = false;
    private String adminWhoStarted = "";
    private String startTime = "";
    
    private final Map<String, BlacklistEntry> blacklist = new ConcurrentHashMap<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");

    public TechWorksManager(TelegramConsoleBot plugin) {
        this.plugin = plugin;
        loadData();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startAutoStartChecker();
        startTechChecker();
        plugin.getLogger().info("✅ TechWorksManager загружен!");
    }

    private void loadData() {
        techFile = new File(plugin.getDataFolder(), "techworks.yml");
        if (!techFile.exists()) {
            try {
                techFile.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().severe("Не удалось создать techworks.yml");
            }
        }
        techConfig = YamlConfiguration.loadConfiguration(techFile);
        
        techMode = techConfig.getBoolean("tech.mode", false);
        kickReason = techConfig.getString("tech.reason", "Технические работы на сервере!");
        endTime = techConfig.getLong("tech.endTime", -1);
        adminWhoStarted = techConfig.getString("tech.admin", "");
        startTime = techConfig.getString("tech.startTime", "");
        autoStartEnabled = techConfig.getBoolean("tech.autoStart.enabled", false);
        autoStartTime = techConfig.getLong("tech.autoStart.time", -1);
        autoStartReason = techConfig.getString("tech.autoStart.reason", "");
        
        loadBlacklist();
        
        if (techMode && endTime != -1 && System.currentTimeMillis() > endTime) {
            turnOff();
        }
        
        plugin.getLogger().info("🔧 Тех. работы: " + (techMode ? "ВКЛ" : "ВЫКЛ"));
        plugin.getLogger().info("📋 Черный список: " + blacklist.size() + " игроков");
    }

    private void loadBlacklist() {
        blacklist.clear();
        if (techConfig.contains("blacklist")) {
            for (String key : techConfig.getConfigurationSection("blacklist").getKeys(false)) {
                String name = techConfig.getString("blacklist." + key + ".name");
                String uuid = techConfig.getString("blacklist." + key + ".uuid");
                long date = techConfig.getLong("blacklist." + key + ".date");
                String issuer = techConfig.getString("blacklist." + key + ".issuer");
                blacklist.put(key, new BlacklistEntry(name, uuid, date, issuer));
            }
        }
    }

    private void saveData() {
        techConfig.set("tech.mode", techMode);
        techConfig.set("tech.reason", kickReason);
        techConfig.set("tech.endTime", endTime);
        techConfig.set("tech.admin", adminWhoStarted);
        techConfig.set("tech.startTime", startTime);
        techConfig.set("tech.autoStart.enabled", autoStartEnabled);
        techConfig.set("tech.autoStart.time", autoStartTime);
        techConfig.set("tech.autoStart.reason", autoStartReason);
        
        techConfig.set("blacklist", null);
        for (Map.Entry<String, BlacklistEntry> entry : blacklist.entrySet()) {
            techConfig.set("blacklist." + entry.getKey() + ".name", entry.getValue().name);
            techConfig.set("blacklist." + entry.getKey() + ".uuid", entry.getValue().uuid);
            techConfig.set("blacklist." + entry.getKey() + ".date", entry.getValue().date);
            techConfig.set("blacklist." + entry.getKey() + ".issuer", entry.getValue().issuer);
        }
        
        try {
            techConfig.save(techFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка сохранения techworks.yml: " + e.getMessage());
        }
    }

    public boolean turnOn(String admin, String reason, String duration) {
        if (techMode) return false;
        
        techMode = true;
        this.kickReason = reason != null && !reason.isEmpty() ? reason : "Технические работы на сервере!";
        this.adminWhoStarted = admin;
        this.startTime = dateFormat.format(new Date());
        
        if (duration != null && !duration.isEmpty()) {
            this.endTime = System.currentTimeMillis() + parseTimeToMillis(duration);
        } else {
            this.endTime = -1;
        }
        
        saveData();
        plugin.getLogger().info("🔧 Тех. работы включены: " + reason + " (Админ: " + admin + ")");
        return true;
    }

    public boolean turnOff() {
        if (!techMode) return false;
        
        techMode = false;
        this.kickReason = "Технические работы на сервере!";
        this.adminWhoStarted = "";
        this.startTime = "";
        this.endTime = -1;
        
        saveData();
        plugin.getLogger().info("🔧 Тех. работы выключены");
        return true;
    }

    public boolean setAutoStart(String duration, String reason) {
        long time = parseTimeToMillis(duration);
        if (time == Long.MAX_VALUE) return false;
        
        this.autoStartTime = System.currentTimeMillis() + time;
        this.autoStartReason = reason != null && !reason.isEmpty() ? reason : "Технические работы!";
        this.autoStartEnabled = true;
        
        saveData();
        plugin.getLogger().info("🔧 Авто-включение тех. работ запланировано через " + duration);
        return true;
    }

    private void startAutoStartChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (autoStartEnabled && autoStartTime != -1 && System.currentTimeMillis() >= autoStartTime) {
                    turnOn("Auto", autoStartReason, null);
                    autoStartEnabled = false;
                    autoStartTime = -1;
                    saveData();
                    
                    for (String adminId : plugin.getConfig().getStringList("admins")) {
                        try {
                            plugin.sendMessageAsBot(Long.parseLong(adminId), 
                                "[БОТ] ⚠️ ОПОВЕЩЕНИЕ: ТЕХНИЧЕСКИЕ РАБОТЫ ВКЛЮЧЕНЫ АВТОМАТИЧЕСКИ!\n" +
                                "Причина: " + autoStartReason);
                        } catch (NumberFormatException e) {}
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void startTechChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (techMode && endTime != -1 && System.currentTimeMillis() > endTime) {
                    turnOff();
                    for (String adminId : plugin.getConfig().getStringList("admins")) {
                        try {
                            plugin.sendMessageAsBot(Long.parseLong(adminId), 
                                "[БОТ] ⚠️ ОПОВЕЩЕНИЕ: ТЕХНИЧЕСКИЕ РАБОТЫ ВЫКЛЮЧЕНЫ АВТОМАТИЧЕСКИ!");
                        } catch (NumberFormatException e) {}
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        String uuid = player.getUniqueId().toString();
        
        if (isBlacklisted(playerName) || isBlacklisted(uuid)) {
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, 
                ChatColor.translateAlternateColorCodes('&', "&4&lВы в черном списке сервера!"));
            return;
        }
        
        if (techMode) {
            if (player.isOp()) return;
            String reason = ChatColor.translateAlternateColorCodes('&', kickReason);
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, reason);
        }
    }

    // ===== ЧЕРНЫЙ СПИСОК =====
    public boolean addBlacklist(String name, String uuid, String issuer) {
        if (name == null && uuid == null) return false;
        
        String key = name != null ? name : uuid;
        if (blacklist.containsKey(key)) return false;
        
        blacklist.put(key, new BlacklistEntry(name, uuid, System.currentTimeMillis(), issuer));
        saveData();
        return true;
    }

    public boolean removeBlacklist(String nameOrUuid) {
        if (blacklist.containsKey(nameOrUuid)) {
            blacklist.remove(nameOrUuid);
            saveData();
            return true;
        }
        
        for (Map.Entry<String, BlacklistEntry> entry : blacklist.entrySet()) {
            if (entry.getValue().name != null && entry.getValue().name.equalsIgnoreCase(nameOrUuid)) {
                blacklist.remove(entry.getKey());
                saveData();
                return true;
            }
            if (entry.getValue().uuid != null && entry.getValue().uuid.equals(nameOrUuid)) {
                blacklist.remove(entry.getKey());
                saveData();
                return true;
            }
        }
        return false;
    }

    public boolean isBlacklisted(String nameOrUuid) {
        if (blacklist.containsKey(nameOrUuid)) return true;
        for (Map.Entry<String, BlacklistEntry> entry : blacklist.entrySet()) {
            if (entry.getValue().name != null && entry.getValue().name.equalsIgnoreCase(nameOrUuid)) return true;
            if (entry.getValue().uuid != null && entry.getValue().uuid.equals(nameOrUuid)) return true;
        }
        return false;
    }

    public String getBlacklistInfo() {
        if (blacklist.isEmpty()) return "[БОТ] Черный список пуст.";
        
        StringBuilder sb = new StringBuilder();
        sb.append("[БОТ] Черный список (" + blacklist.size() + " игроков):\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        for (Map.Entry<String, BlacklistEntry> entry : blacklist.entrySet()) {
            BlacklistEntry be = entry.getValue();
            sb.append("Ник: ").append(be.name != null ? be.name : "—")
              .append(" | UUID: ").append(be.uuid != null ? be.uuid : "—")
              .append(" | Выдал: ").append(be.issuer)
              .append(" | Дата: ").append(dateFormat.format(new Date(be.date)))
              .append("\n");
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        return sb.toString();
    }

    private long parseTimeToMillis(String time) {
        if (time == null) return Long.MAX_VALUE;
        char unit = time.charAt(time.length() - 1);
        long value = Long.parseLong(time.substring(0, time.length() - 1));
        switch (unit) {
            case 's': return value * 1000;
            case 'm': return value * 60 * 1000;
            case 'h': return value * 60 * 60 * 1000;
            case 'd': return value * 24 * 60 * 60 * 1000;
            case 'w': return value * 7 * 24 * 60 * 60 * 1000;
            case 'M': return value * 30L * 24 * 60 * 60 * 1000;
            case 'y': return value * 365L * 24 * 60 * 60 * 1000;
            default: return Long.MAX_VALUE;
        }
    }

    // ===== ГЕТТЕРЫ =====
    public boolean isTechMode() { return techMode; }
    public String getKickReason() { return kickReason; }
    public String getAdminWhoStarted() { return adminWhoStarted; }
    public String getStartTime() { return startTime; }
    public String getEndTimeFormatted() {
        if (endTime == -1) return "Не указано";
        return dateFormat.format(new Date(endTime));
    }
    public String getTimeLeft() {
        if (endTime == -1) return "Бесконечно";
        long diff = endTime - System.currentTimeMillis();
        if (diff <= 0) return "Истекло";
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        seconds %= 60;
        minutes %= 60;
        hours %= 24;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("д ");
        if (hours > 0) sb.append(hours).append("ч ");
        if (minutes > 0 && hours == 0) sb.append(minutes).append("м ");
        if (seconds > 0 && minutes == 0 && hours == 0) sb.append(seconds).append("с");
        return sb.toString().trim();
    }

    public static class BlacklistEntry {
        public String name;
        public String uuid;
        public long date;
        public String issuer;
        
        public BlacklistEntry(String name, String uuid, long date, String issuer) {
            this.name = name;
            this.uuid = uuid;
            this.date = date;
            this.issuer = issuer;
        }
    }
}
