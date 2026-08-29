package com.grifmcpo.consolebot;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class BotBanManager {

    private final TelegramConsoleBot plugin;
    private final DatabaseManager databaseManager;
    private final Map<Long, BanData> bans = new ConcurrentHashMap<>();

    public BotBanManager(TelegramConsoleBot plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        loadBans();
    }

    private void loadBans() {
        bans.clear();
        
        databaseManager.getAllBotBans().thenAccept(bansList -> {
            for (Map<String, Object> banData : bansList) {
                long userId = Long.parseLong((String) banData.get("telegram_id"));
                String reason = (String) banData.get("reason");
                long timestamp = (Long) banData.get("timestamp");
                long expiry = (Long) banData.get("expiry");
                String issuer = (String) banData.get("issuer");
                
                String duration = expiry == -1 ? "навсегда" : getDurationString(expiry - timestamp);
                bans.put(userId, new BanData(userId, reason, duration, timestamp, expiry, issuer));
            }
            plugin.getLogger().info("Загружено банов в боте: " + bans.size());
        });
    }

    private String getDurationString(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) return days + "d";
        if (hours > 0) return hours + "h";
        if (minutes > 0) return minutes + "m";
        return seconds + "s";
    }

    public boolean banUser(long userId, String reason, String duration, String issuer) {
        if (userId == plugin.getOwnerId()) {
            return false;
        }

        if (plugin.isAdmin(userId)) {
            return false;
        }

        if (isBanned(userId)) {
            return false;
        }

        long timestamp = System.currentTimeMillis();
        long expiry = duration.equals("навсегда") ? -1 : timestamp + parseTimeToMillis(duration);

        // Сохраняем в БД
        databaseManager.banBotUser(String.valueOf(userId), reason, issuer, expiry);
        
        BanData ban = new BanData(userId, reason, duration, timestamp, expiry, issuer);
        bans.put(userId, ban);

        return true;
    }

    public boolean unbanUser(long userId, String reason, String issuer) {
        if (!isBanned(userId)) {
            return false;
        }

        databaseManager.unbanBotUser(String.valueOf(userId));
        bans.remove(userId);
        return true;
    }

    public boolean isBanned(long userId) {
        removeExpiredBans();
        return bans.containsKey(userId);
    }

    public BanData getBanData(long userId) {
        removeExpiredBans();
        return bans.get(userId);
    }

    public String getBanReason(long userId) {
        BanData ban = bans.get(userId);
        return ban != null ? ban.reason : null;
    }

    public String getBanMessage(long userId) {
        BanData ban = bans.get(userId);
        if (ban == null) return null;

        String timeLeft = ban.duration.equals("навсегда") ? "навсегда" : getTimeLeft(ban.expires);
        return "[БОТ] У вас имеется активный бан в боте!\n" +
                "Причина: " + ban.reason + "\n" +
                "Осталось: " + timeLeft + "\n" +
                "Выдал: " + ban.issuer;
    }

    public List<BanData> getAllBans() {
        removeExpiredBans();
        return new ArrayList<>(bans.values());
    }

    private void removeExpiredBans() {
        long now = System.currentTimeMillis();
        List<Long> toRemove = new ArrayList<>();
        for (Map.Entry<Long, BanData> entry : bans.entrySet()) {
            if (entry.getValue().isExpired()) {
                toRemove.add(entry.getKey());
            }
        }
        for (long id : toRemove) {
            bans.remove(id);
            databaseManager.unbanBotUser(String.valueOf(id));
        }
    }

    private long parseTimeToMillis(String time) {
        if (time == null || time.equals("навсегда")) return Long.MAX_VALUE;
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

    private String getTimeLeft(long expires) {
        if (expires == -1) return "навсегда";
        long diff = expires - System.currentTimeMillis();
        if (diff <= 0) return "истек";

        long days = diff / (24 * 60 * 60 * 1000);
        long hours = (diff % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
        long minutes = (diff % (60 * 60 * 1000)) / (60 * 1000);

        if (days > 0) return days + "д " + hours + "ч";
        if (hours > 0) return hours + "ч " + minutes + "м";
        return minutes + "м";
    }

    public static class BanData {
        public final long userId;
        public final String reason;
        public final String duration;
        public final long timestamp;
        public final long expires;
        public final String issuer;

        public BanData(long userId, String reason, String duration, long timestamp, long expires, String issuer) {
            this.userId = userId;
            this.reason = reason;
            this.duration = duration;
            this.timestamp = timestamp;
            this.expires = expires;
            this.issuer = issuer;
        }

        public boolean isExpired() {
            return expires != -1 && System.currentTimeMillis() > expires;
        }

        public String getTimeAgo() {
            long diff = System.currentTimeMillis() - timestamp;
            long days = diff / (24 * 60 * 60 * 1000);
            long hours = (diff % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
            if (days > 0) return days + "д " + hours + "ч назад";
            if (hours > 0) return hours + "ч назад";
            return "только что";
        }

        public String getStatus() {
            if (isExpired()) return "Истек";
            if (duration.equals("навсегда")) return "Навсегда";
            return "Активен";
        }
    }
}
