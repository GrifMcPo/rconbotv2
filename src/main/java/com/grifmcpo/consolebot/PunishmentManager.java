package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PunishmentManager implements Listener {

    private final JavaPlugin plugin;
    private final AdminLogger adminLogger;
    private File historyFile;
    private org.bukkit.configuration.file.FileConfiguration historyConfig;
    private final Map<String, List<HistoryEntry>> history = new ConcurrentHashMap<>();
    private final Map<String, Long> bans = new ConcurrentHashMap<>();
    private final Map<String, Long> mutes = new ConcurrentHashMap<>();
    private final Map<String, String> muteIssuers = new ConcurrentHashMap<>();
    private final Map<String, String> muteReasons = new ConcurrentHashMap<>();
    private final Map<String, String> banIssuers = new ConcurrentHashMap<>();
    private final Map<String, String> banReasons = new ConcurrentHashMap<>();
    private final Map<String, List<String>> bannedIps = new ConcurrentHashMap<>();
    private final Map<String, Long> ipBanExpiry = new ConcurrentHashMap<>();
    private final Map<String, String> ipBanIssuers = new ConcurrentHashMap<>();
    private final Map<String, String> ipBanReasons = new ConcurrentHashMap<>();
    private final Map<String, List<WarnEntry>> warns = new ConcurrentHashMap<>();
    private final Map<String, Long> warnExpiry = new ConcurrentHashMap<>();
    private final Map<String, String> warnIssuers = new ConcurrentHashMap<>();
    private final Map<String, String> warnReasons = new ConcurrentHashMap<>();
    private final Map<String, Long> playerJoinTimes = new ConcurrentHashMap<>();

    private final List<String> allowedCommands = Arrays.asList("msg", "tell", "r", "reply", "help", "pay", "balance", "bal", "me", "emote");
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");

    public PunishmentManager(JavaPlugin plugin, AdminLogger adminLogger) {
        this.plugin = plugin;
        this.adminLogger = adminLogger;
        loadHistory();
        loadActivePunishments();
        startExpiryChecker();
        startWarnExpiryChecker();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("✅ PunishmentManager загружен!");
    }

    @SuppressWarnings("unchecked")
    private void loadHistory() {
        historyFile = new File(plugin.getDataFolder(), "history.yml");
        if (!historyFile.exists()) {
            try {
                historyFile.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().severe("Не удалось создать history.yml");
            }
        }
        historyConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(historyFile);

        history.clear();
        for (String playerName : historyConfig.getKeys(false)) {
            List<Map<?, ?>> entries = (List<Map<?, ?>>) historyConfig.getList(playerName);
            if (entries == null) continue;
            List<HistoryEntry> list = new ArrayList<>();
            for (Map<?, ?> entry : entries) {
                HistoryEntry he = new HistoryEntry();
                he.type = (String) entry.get("type");
                he.player = (String) entry.get("player");
                he.issuer = (String) entry.get("issuer");
                he.reason = (String) entry.get("reason");
                he.duration = (String) entry.get("duration");
                he.timestamp = ((Number) entry.get("timestamp")).longValue();
                Object hiddenObj = entry.get("hidden");
                he.hidden = hiddenObj != null && (boolean) hiddenObj;
                he.ipBan = entry.containsKey("ipBan") && (boolean) entry.get("ipBan");
                he.expiry = entry.containsKey("expiry") ? ((Number) entry.get("expiry")).longValue() : -1;
                list.add(he);
            }
            history.put(playerName, list);
        }
        plugin.getLogger().info("Загружена история наказаний: " + history.size() + " игроков");
    }

    private void loadActivePunishments() {
        bans.clear();
        mutes.clear();
        muteIssuers.clear();
        muteReasons.clear();
        banIssuers.clear();
        banReasons.clear();
        bannedIps.clear();
        ipBanExpiry.clear();
        ipBanIssuers.clear();
        ipBanReasons.clear();
        warns.clear();
        warnExpiry.clear();
        warnIssuers.clear();
        warnReasons.clear();

        for (Map.Entry<String, List<HistoryEntry>> entry : history.entrySet()) {
            String playerName = entry.getKey();
            List<HistoryEntry> list = entry.getValue();
            
            if (list.isEmpty()) continue;

            for (int i = list.size() - 1; i >= 0; i--) {
                HistoryEntry he = list.get(i);

                if (he.type.equals("ban")) {
                    boolean wasUnbanned = false;
                    for (int j = i + 1; j < list.size(); j++) {
                        if (list.get(j).type.equals("unban")) {
                            wasUnbanned = true;
                            break;
                        }
                    }
                    if (!wasUnbanned) {
                        long expiry = he.duration.equals("навсегда") ? -1 : he.timestamp + parseTimeToMillis(he.duration);
                        if (expiry == -1 || expiry > System.currentTimeMillis()) {
                            bans.put(playerName, expiry);
                            banIssuers.put(playerName, he.issuer);
                            banReasons.put(playerName, he.reason);
                        }
                    }
                    break;
                }

                if (he.type.equals("mute")) {
                    boolean wasUnmuted = false;
                    for (int j = i + 1; j < list.size(); j++) {
                        if (list.get(j).type.equals("unmute")) {
                            wasUnmuted = true;
                            break;
                        }
                    }
                    if (!wasUnmuted) {
                        long expiry = he.duration.equals("навсегда") ? -1 : he.timestamp + parseTimeToMillis(he.duration);
                        if (expiry == -1 || expiry > System.currentTimeMillis()) {
                            mutes.put(playerName, expiry);
                            muteIssuers.put(playerName, he.issuer);
                            muteReasons.put(playerName, he.reason);
                        }
                    }
                    break;
                }

                if (he.type.equals("warn")) {
                    long expiry = he.duration.equals("навсегда") ? -1 : he.timestamp + parseTimeToMillis(he.duration);
                    if (expiry == -1 || expiry > System.currentTimeMillis()) {
                        List<WarnEntry> warnsList = warns.computeIfAbsent(playerName, k -> new ArrayList<>());
                        WarnEntry we = new WarnEntry();
                        we.issuer = he.issuer;
                        we.reason = he.reason;
                        we.timestamp = he.timestamp;
                        warnsList.add(we);
                    }
                    break;
                }

                if (he.type.equals("ipban")) {
                    boolean wasUnbanned = false;
                    for (int j = i + 1; j < list.size(); j++) {
                        if (list.get(j).type.equals("unbanip")) {
                            wasUnbanned = true;
                            break;
                        }
                    }
                    if (!wasUnbanned) {
                        long expiry = he.duration.equals("навсегда") ? -1 : he.timestamp + parseTimeToMillis(he.duration);
                        if (expiry == -1 || expiry > System.currentTimeMillis()) {
                            List<String> ips = bannedIps.getOrDefault(playerName, new ArrayList<>());
                            if (!ips.contains(he.reason.replaceAll(".*\\(IP: ", "").replaceAll("\\)", ""))) {
                                ips.add(he.reason.replaceAll(".*\\(IP: ", "").replaceAll("\\)", ""));
                            }
                            bannedIps.put(playerName, ips);
                            ipBanExpiry.put(playerName, expiry);
                            ipBanIssuers.put(playerName, he.issuer);
                            ipBanReasons.put(playerName, he.reason);
                        }
                    }
                    break;
                }
            }
        }
        plugin.getLogger().info("✅ Загружено активных банов: " + bans.size() + ", мутов: " + mutes.size() + ", варнов: " + warns.size());
    }

    private void startExpiryChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                checkExpiredPunishments();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void startWarnExpiryChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                checkExpiredWarns();
            }
        }.runTaskTimer(plugin, 20L * 60, 20L * 60); // Каждую минуту
    }

    private void checkExpiredWarns() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, List<WarnEntry>> entry : new HashMap<>(warns).entrySet()) {
            String playerName = entry.getKey();
            List<WarnEntry> list = entry.getValue();
            if (list.isEmpty()) {
                warns.remove(playerName);
                continue;
            }
            // Удаляем просроченные варны
            list.removeIf(we -> {
                Long expiry = warnExpiry.get(playerName);
                return expiry != null && expiry <= now;
            });
            if (list.isEmpty()) {
                warns.remove(playerName);
                warnExpiry.remove(playerName);
            }
        }
    }

    private void checkExpiredPunishments() {
        long now = System.currentTimeMillis();

        for (Map.Entry<String, Long> entry : new HashMap<>(bans).entrySet()) {
            String playerName = entry.getKey();
            long expiry = entry.getValue();
            if (expiry != -1 && expiry <= now) {
                bans.remove(playerName);
                banIssuers.remove(playerName);
                banReasons.remove(playerName);
                plugin.getLogger().info("Автоснятие бана: " + playerName);
            }
        }

        for (Map.Entry<String, Long> entry : new HashMap<>(mutes).entrySet()) {
            String playerName = entry.getKey();
            long expiry = entry.getValue();
            if (expiry != -1 && expiry <= now) {
                mutes.remove(playerName);
                muteIssuers.remove(playerName);
                muteReasons.remove(playerName);
                plugin.getLogger().info("Автоснятие мута: " + playerName);
                Player p = Bukkit.getPlayer(playerName);
                if (p != null && p.isOnline()) {
                    p.sendMessage("§aВаш мут был автоматически снят (срок истек)");
                }
            }
        }

        for (Map.Entry<String, Long> entry : new HashMap<>(ipBanExpiry).entrySet()) {
            String playerName = entry.getKey();
            long expiry = entry.getValue();
            if (expiry > 0 && expiry <= now) {
                bannedIps.remove(playerName);
                ipBanExpiry.remove(playerName);
                ipBanIssuers.remove(playerName);
                ipBanReasons.remove(playerName);
                plugin.getLogger().info("Автоснятие IP бана: " + playerName);
            }
        }
    }

    public void saveHistory() {
        for (Map.Entry<String, List<HistoryEntry>> entry : history.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (HistoryEntry he : entry.getValue()) {
                Map<String, Object> map = new HashMap<>();
                map.put("type", he.type);
                map.put("player", he.player);
                map.put("issuer", he.issuer);
                map.put("reason", he.reason);
                map.put("duration", he.duration);
                map.put("timestamp", he.timestamp);
                map.put("hidden", he.hidden);
                map.put("ipBan", he.ipBan);
                map.put("expiry", he.expiry);
                list.add(map);
            }
            historyConfig.set(entry.getKey(), list);
        }
        try {
            historyConfig.save(historyFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка сохранения history.yml: " + e.getMessage());
        }
    }

    private void addHistorySync(String playerName, HistoryEntry entry) {
        List<HistoryEntry> list = history.computeIfAbsent(playerName, k -> new ArrayList<>());
        list.add(entry);
        saveHistory();
    }

    // =========================================================
    // ==== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ =====
    // =========================================================

    public String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public String getReason(String reason) {
        if (reason == null || reason.isEmpty()) return "§7Без причины";
        if (reason.contains("&")) {
            return colorize(reason);
        }
        return "§7" + reason;
    }

    private String getExpiryDate(long expiry) {
        if (expiry == -1) return "навсегда";
        return dateFormat.format(new Date(expiry));
    }

    public String getBanMessage(String playerName, String issuer, String reason, long expiry, boolean ipBan) {
        StringBuilder sb = new StringBuilder();
        sb.append("§c§lВаш аккаунт заблокирован!\n\n");
        sb.append("§fПричина: ").append(getReason(reason)).append("\n");
        sb.append("§fСервер: §cглобальный\n");
        sb.append("§fВыдал: §9").append(issuer).append("\n");
        sb.append("§fИстекает через: §c").append(getExpiryDate(expiry)).append(" MSK\n");
        
        if (ipBan) {
            sb.append("§7Блокировка выдана по IP!\n");
        }
        if (expiry == -1) {
            sb.append("§7Блокировка выдана навсегда!\n");
        }
        return sb.toString();
    }

    public String getMuteMessage(String playerName, String issuer, String reason, long expiry) {
        StringBuilder sb = new StringBuilder();
        sb.append("§c§lВам заблокировали чат!\n\n");
        sb.append("§fПричина: ").append(getReason(reason)).append("\n");
        sb.append("§fИстекает через: §c").append(getExpiryDate(expiry)).append(" MSK\n");
        sb.append("§fВыдал: §9").append(issuer).append("\n");
        if (expiry == -1) {
            sb.append("§7Блокировка выдана навсегда!\n");
        }
        return sb.toString();
    }

    public String getWarnMessage(String playerName, String issuer, String reason) {
        return "§c§lВам выдали предупреждение!\n§fПричина: " + getReason(reason) + "\n§fВыдал: §9" + issuer;
    }

    // =========================================================
    // ==== БАН =====
    // =========================================================
    public boolean banPlayer(String playerName, String issuer, String reason, String duration, boolean hidden, boolean broadcast) {
        if (isBanned(playerName)) {
            return false;
        }

        if (duration == null || duration.isEmpty()) {
            duration = "навсегда";
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final String finalDuration = duration;
        final boolean finalHidden = hidden;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            long expiry = finalDuration.equals("навсегда") ? -1 : System.currentTimeMillis() + parseTimeToMillis(finalDuration);
            
            HistoryEntry entry = new HistoryEntry();
            entry.type = "ban";
            entry.player = finalPlayerName;
            entry.issuer = finalIssuer;
            entry.reason = finalReason;
            entry.duration = finalDuration;
            entry.timestamp = System.currentTimeMillis();
            entry.hidden = finalHidden;
            entry.ipBan = false;
            entry.expiry = expiry;
            addHistorySync(finalPlayerName, entry);

            bans.put(finalPlayerName, expiry);
            banIssuers.put(finalPlayerName, finalIssuer);
            banReasons.put(finalPlayerName, finalReason);

            Player player = Bukkit.getPlayer(finalPlayerName);
            if (player != null && player.isOnline()) {
                player.kickPlayer(getBanMessage(finalPlayerName, finalIssuer, finalReason, expiry, false));
            }

            if (finalBroadcast && !finalHidden) {
                String timeStr = finalDuration.equals("навсегда") ? "" : " §fна §b" + formatDuration(finalDuration) + " §f";
                String msg = "§4❨！❩ §fИгрок §9" + finalIssuer + " §fзабанил §c" + finalPlayerName + 
                             timeStr + "§fпо причине: §7" + getReason(finalReason) + " §8(глобальный)";
                Bukkit.broadcastMessage(colorize(msg));
            }

            if (adminLogger != null) {
                adminLogger.log("BAN", finalPlayerName, finalIssuer, finalReason, finalDuration, finalHidden ? "СКРЫТО" : "ПУБЛИЧНО");
            }
        });

        return true;
    }

    public boolean banPlayer(String playerName, String issuer, String reason, String duration) {
        return banPlayer(playerName, issuer, reason, duration, false, true);
    }

    // =========================================================
    // ==== БАН ПО UUID =====
    // =========================================================
    public boolean banUuid(String uuid, String issuer, String reason, String duration, boolean hidden, boolean broadcast) {
        try {
            UUID uuidObj = UUID.fromString(uuid);
            Player player = Bukkit.getPlayer(uuidObj);
            String playerName = player != null ? player.getName() : uuid;
            return banPlayer(playerName, issuer, reason, duration, hidden, broadcast);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // =========================================================
    // ==== РАЗБАН =====
    // =========================================================
    public boolean unbanPlayer(String playerName, String issuer, String reason, boolean broadcast) {
        if (!isBanned(playerName)) {
            return false;
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            HistoryEntry entry = new HistoryEntry();
            entry.type = "unban";
            entry.player = finalPlayerName;
            entry.issuer = finalIssuer;
            entry.reason = finalReason;
            entry.duration = "навсегда";
            entry.timestamp = System.currentTimeMillis();
            entry.hidden = false;
            entry.ipBan = false;
            entry.expiry = -1;
            addHistorySync(finalPlayerName, entry);

            bans.remove(finalPlayerName);
            banIssuers.remove(finalPlayerName);
            banReasons.remove(finalPlayerName);

            if (finalBroadcast) {
                String msg = "§4❨！❩ §fИгрок §9" + finalIssuer + " §fразбанил §a" + finalPlayerName + 
                             " §fпо причине: §7" + getReason(finalReason) + " §8(глобальный)";
                Bukkit.broadcastMessage(colorize(msg));
            }

            if (adminLogger != null) {
                adminLogger.log("UNBAN", finalPlayerName, finalIssuer, finalReason, "навсегда", "ПУБЛИЧНО");
            }
        });

        return true;
    }

    public boolean unbanPlayer(String playerName, String issuer, String reason) {
        return unbanPlayer(playerName, issuer, reason, true);
    }

    // =========================================================
    // ==== МУТ =====
    // =========================================================
    public boolean mutePlayer(String playerName, String issuer, String reason, String duration, boolean hidden, boolean broadcast) {
        if (isMuted(playerName)) {
            return false;
        }

        if (duration == null || duration.isEmpty()) {
            duration = "навсегда";
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final String finalDuration = duration;
        final boolean finalHidden = hidden;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            long expiry = finalDuration.equals("навсегда") ? -1 : System.currentTimeMillis() + parseTimeToMillis(finalDuration);

            HistoryEntry entry = new HistoryEntry();
            entry.type = "mute";
            entry.player = finalPlayerName;
            entry.issuer = finalIssuer;
            entry.reason = finalReason;
            entry.duration = finalDuration;
            entry.timestamp = System.currentTimeMillis();
            entry.hidden = finalHidden;
            entry.ipBan = false;
            entry.expiry = expiry;
            addHistorySync(finalPlayerName, entry);

            mutes.put(finalPlayerName, expiry);
            muteIssuers.put(finalPlayerName, finalIssuer);
            muteReasons.put(finalPlayerName, finalReason);

            Player player = Bukkit.getPlayer(finalPlayerName);
            if (player != null && player.isOnline()) {
                player.sendMessage(getMuteMessage(finalPlayerName, finalIssuer, finalReason, expiry));
            }

            if (finalBroadcast && !finalHidden) {
                String timeStr = finalDuration.equals("навсегда") ? "" : " §fна §b" + formatDuration(finalDuration) + " §f";
                String msg = "§4❨！❩ §fИгрок §9" + finalIssuer + " §fзамутил §c" + finalPlayerName + 
                             timeStr + "§fпо причине: §7" + getReason(finalReason) + " §8(глобальный)";
                Bukkit.broadcastMessage(colorize(msg));
            }

            if (adminLogger != null) {
                adminLogger.log("MUTE", finalPlayerName, finalIssuer, finalReason, finalDuration, finalHidden ? "СКРЫТО" : "ПУБЛИЧНО");
            }
        });

        return true;
    }

    public boolean mutePlayer(String playerName, String issuer, String reason, String duration) {
        return mutePlayer(playerName, issuer, reason, duration, false, true);
    }

    // =========================================================
    // ==== РАЗМУТ =====
    // =========================================================
    public boolean unmutePlayer(String playerName, String issuer, String reason, boolean broadcast) {
        if (!isMuted(playerName)) {
            return false;
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            HistoryEntry entry = new HistoryEntry();
            entry.type = "unmute";
            entry.player = finalPlayerName;
            entry.issuer = finalIssuer;
            entry.reason = finalReason;
            entry.duration = "навсегда";
            entry.timestamp = System.currentTimeMillis();
            entry.hidden = false;
            entry.ipBan = false;
            entry.expiry = -1;
            addHistorySync(finalPlayerName, entry);

            mutes.remove(finalPlayerName);
            muteIssuers.remove(finalPlayerName);
            muteReasons.remove(finalPlayerName);

            if (finalBroadcast) {
                String msg = "§4❨！❩ §fИгрок §9" + finalIssuer + " §fразмутил §a" + finalPlayerName + 
                             " §fпо причине: §7" + getReason(finalReason) + " §8(глобальный)";
                Bukkit.broadcastMessage(colorize(msg));
            }

            Player player = Bukkit.getPlayer(finalPlayerName);
            if (player != null && player.isOnline()) {
                player.sendMessage("§aВаш мут был снят!");
            }

            if (adminLogger != null) {
                adminLogger.log("UNMUTE", finalPlayerName, finalIssuer, finalReason, "навсегда", "ПУБЛИЧНО");
            }
        });

        return true;
    }

    public boolean unmutePlayer(String playerName, String issuer, String reason) {
        return unmutePlayer(playerName, issuer, reason, true);
    }

    // =========================================================
    // ==== КИК =====
    // =========================================================
    public boolean kickPlayer(String playerName, String issuer, String reason, boolean hidden, boolean broadcast) {
        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final boolean finalHidden = hidden;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(finalPlayerName);
            if (player == null) {
                plugin.getLogger().warning("Игрок " + finalPlayerName + " не найден для кика!");
                return;
            }

            HistoryEntry entry = new HistoryEntry();
            entry.type = "kick";
            entry.player = finalPlayerName;
            entry.issuer = finalIssuer;
            entry.reason = finalReason;
            entry.duration = "навсегда";
            entry.timestamp = System.currentTimeMillis();
            entry.hidden = finalHidden;
            entry.ipBan = false;
            entry.expiry = -1;
            addHistorySync(finalPlayerName, entry);

            String kickMessage = "§cВы были кикнуты!\n§7Причина: " + getReason(finalReason);
            player.kickPlayer(kickMessage);

            if (finalBroadcast && !finalHidden) {
                String msg = "§4❨！❩ §fИгрок §9" + finalIssuer + " §fкикнул §c" + finalPlayerName + 
                             " §fпо причине: §7" + getReason(finalReason) + " §8(глобальный)";
                Bukkit.broadcastMessage(colorize(msg));
            }

            if (adminLogger != null) {
                adminLogger.log("KICK", finalPlayerName, finalIssuer, finalReason, "навсегда", finalHidden ? "СКРЫТО" : "ПУБЛИЧНО");
            }
        });

        return true;
    }

    public boolean kickPlayer(String playerName, String issuer, String reason) {
        return kickPlayer(playerName, issuer, reason, false, true);
    }

    // =========================================================
    // ==== IP БАН =====
    // =========================================================
    public boolean banIp(String playerName, String issuer, String reason, String duration, boolean hidden) {
        String ip = getPlayerIp(playerName);
        
        // Если IP не найден, создаем бан с пометкой что IP будет забанен при заходе
        if (ip == null || ip.equals("—") || ip.equals("0.0.0.0")) {
            plugin.getLogger().info("⚠ IP для " + playerName + " не найден, сохраняем IP бан для первого захода");
            // Сохраняем в историю без IP
            final String finalPlayerName = playerName;
            final String finalIssuer = issuer;
            final String finalReason = reason;
            final String finalDuration = duration;
            final boolean finalHidden = hidden;
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                long expiry = finalDuration.equals("навсегда") ? -1 : System.currentTimeMillis() + parseTimeToMillis(finalDuration);
                
                HistoryEntry entry = new HistoryEntry();
                entry.type = "ipban";
                entry.player = finalPlayerName;
                entry.issuer = finalIssuer;
                entry.reason = finalReason + " (IP будет забанен при заходе)";
                entry.duration = finalDuration;
                entry.timestamp = System.currentTimeMillis();
                entry.hidden = finalHidden;
                entry.ipBan = true;
                entry.expiry = expiry;
                addHistorySync(finalPlayerName, entry);
                
                // Добавляем в память
                if (expiry == -1 || expiry > System.currentTimeMillis()) {
                    ipBanExpiry.put(finalPlayerName, expiry);
                    ipBanIssuers.put(finalPlayerName, finalIssuer);
                    ipBanReasons.put(finalPlayerName, finalReason + " (IP будет забанен при заходе)");
                }
            });
            
            return true;
        }

        if (duration == null || duration.isEmpty()) {
            duration = "навсегда";
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final String finalDuration = duration;
        final String finalIp = ip;
        final boolean finalHidden = hidden;

        Bukkit.getScheduler().runTask(plugin, () -> {
            long expiry = finalDuration.equals("навсегда") ? -1 : System.currentTimeMillis() + parseTimeToMillis(finalDuration);
            
            List<String> ips = bannedIps.getOrDefault(finalPlayerName, new ArrayList<>());
            if (!ips.contains(finalIp)) {
                ips.add(finalIp);
            }
            bannedIps.put(finalPlayerName, ips);
            ipBanExpiry.put(finalPlayerName, expiry);
            ipBanIssuers.put(finalPlayerName, finalIssuer);
            ipBanReasons.put(finalPlayerName, finalReason);

            HistoryEntry entry = new HistoryEntry();
            entry.type = "ipban";
            entry.player = finalPlayerName;
            entry.issuer = finalIssuer;
            entry.reason = finalReason + " (IP: " + finalIp + ")";
            entry.duration = finalDuration;
            entry.timestamp = System.currentTimeMillis();
            entry.hidden = finalHidden;
            entry.ipBan = true;
            entry.expiry = expiry;
            addHistorySync(finalPlayerName, entry);

            // Также добавляем обычный бан для истории
            HistoryEntry banEntry = new HistoryEntry();
            banEntry.type = "ban";
            banEntry.player = finalPlayerName;
            banEntry.issuer = finalIssuer;
            banEntry.reason = finalReason + " (IP бан)";
            banEntry.duration = finalDuration;
            banEntry.timestamp = System.currentTimeMillis();
            banEntry.hidden = finalHidden;
            banEntry.ipBan = true;
            banEntry.expiry = expiry;
            addHistorySync(finalPlayerName, banEntry);

            bans.put(finalPlayerName, expiry);
            banIssuers.put(finalPlayerName, finalIssuer);
            banReasons.put(finalPlayerName, finalReason + " (IP бан)");

            Player p = Bukkit.getPlayer(finalPlayerName);
            if (p != null && p.isOnline()) {
                p.kickPlayer(getBanMessage(finalPlayerName, finalIssuer, finalReason, expiry, true));
            }

            // Сообщение в чат для IP бана
            if (!finalHidden) {
                String timeStr = finalDuration.equals("навсегда") ? "" : " §fна §b" + formatDuration(finalDuration) + " §f";
                String msg = "§4❨！❩ §fИгрок §9" + finalIssuer + " §fзабанил IP §c" + finalPlayerName + 
                             timeStr + "§fпо причине: §7" + getReason(finalReason) + " §8(глобальный)";
                Bukkit.broadcastMessage(colorize(msg));
            }

            plugin.getLogger().info("IP " + finalIp + " игрока " + finalPlayerName + " забанен на " + finalDuration);
        });

        return true;
    }

    public boolean unbanIp(String playerName, String issuer, String reason) {
        if (!bannedIps.containsKey(playerName)) {
            return false;
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;

        Bukkit.getScheduler().runTask(plugin, () -> {
            bannedIps.remove(finalPlayerName);
            ipBanExpiry.remove(finalPlayerName);
            ipBanIssuers.remove(finalPlayerName);
            ipBanReasons.remove(finalPlayerName);
            
            // Добавляем в историю
            HistoryEntry entry = new HistoryEntry();
            entry.type = "unbanip";
            entry.player = finalPlayerName;
            entry.issuer = finalIssuer;
            entry.reason = finalReason;
            entry.duration = "навсегда";
            entry.timestamp = System.currentTimeMillis();
            entry.hidden = false;
            entry.ipBan = true;
            entry.expiry = -1;
            addHistorySync(finalPlayerName, entry);

            String msg = "§4❨！❩ §fИгрок §9" + finalIssuer + " §fразбанил IP §a" + finalPlayerName + 
                         " §fпо причине: §7" + getReason(finalReason) + " §8(глобальный)";
            Bukkit.broadcastMessage(colorize(msg));
            
            plugin.getLogger().info("IP бан снят с " + finalPlayerName + " по причине: " + finalReason);
        });

        return true;
    }

    public boolean isIpBanned(String playerName, String ip) {
        if (!bannedIps.containsKey(playerName)) return false;
        
        Long expiry = ipBanExpiry.get(playerName);
        if (expiry != null && expiry != -1 && System.currentTimeMillis() > expiry) {
            bannedIps.remove(playerName);
            ipBanExpiry.remove(playerName);
            ipBanIssuers.remove(playerName);
            ipBanReasons.remove(playerName);
            return false;
        }
        
        return bannedIps.get(playerName).contains(ip);
    }

    public String getPlayerIp(String playerName) {
        // Ищем в истории IP банов
        List<HistoryEntry> list = history.get(playerName);
        if (list != null && !list.isEmpty()) {
            for (HistoryEntry entry : list) {
                if (entry.reason != null && entry.reason.contains("IP: ")) {
                    String ip = entry.reason.substring(entry.reason.indexOf("IP: ") + 4);
                    if (ip.contains(")")) {
                        ip = ip.substring(0, ip.indexOf(")"));
                    }
                    if (ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                        return ip;
                    }
                }
            }
        }
        
        // Ищем онлайн игрока
        Player player = Bukkit.getPlayer(playerName);
        if (player != null && player.getAddress() != null) {
            return player.getAddress().getHostString();
        }
        
        return "—";
    }

    public void setPlayerIp(String playerName, String ip) {
        // Сохраняем IP в историю
        List<HistoryEntry> list = history.computeIfAbsent(playerName, k -> new ArrayList<>());
        // Добавляем запись о IP если её нет
        boolean hasIp = false;
        for (HistoryEntry entry : list) {
            if (entry.type.equals("ip") || (entry.reason != null && entry.reason.contains("IP: "))) {
                hasIp = true;
                break;
            }
        }
        if (!hasIp) {
            HistoryEntry entry = new HistoryEntry();
            entry.type = "ip";
            entry.player = playerName;
            entry.issuer = "System";
            entry.reason = "IP: " + ip;
            entry.duration = "навсегда";
            entry.timestamp = System.currentTimeMillis();
            entry.hidden = false;
            entry.ipBan = false;
            entry.expiry = -1;
            list.add(entry);
            saveHistory();
        }
    }

    // =========================================================
    // ==== WARN =====
    // =========================================================
    public boolean warnPlayer(String playerName, String issuer, String reason, boolean hidden, boolean broadcast) {
        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final boolean finalHidden = hidden;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            // Добавляем в историю
            HistoryEntry entry = new HistoryEntry();
            entry.type = "warn";
            entry.player = finalPlayerName;
            entry.issuer = finalIssuer;
            entry.reason = finalReason;
            entry.duration = "навсегда";
            entry.timestamp = System.currentTimeMillis();
            entry.hidden = finalHidden;
            entry.ipBan = false;
            entry.expiry = -1;
            addHistorySync(finalPlayerName, entry);
            
            WarnEntry we = new WarnEntry();
            we.issuer = finalIssuer;
            we.reason = finalReason;
            we.timestamp = System.currentTimeMillis();
            
            List<WarnEntry> list = warns.computeIfAbsent(finalPlayerName, k -> new ArrayList<>());
            list.add(we);

            if (finalBroadcast && !finalHidden) {
                String msg = "§4❨！❩ §fИгрок §9" + finalIssuer + " §fвыдал предупреждение §c" + finalPlayerName + 
                             " §fпо причине: §7" + getReason(finalReason) + " §8(глобальный)";
                Bukkit.broadcastMessage(colorize(msg));
            }

            Player player = Bukkit.getPlayer(finalPlayerName);
            if (player != null && player.isOnline()) {
                player.sendMessage(getWarnMessage(finalPlayerName, finalIssuer, finalReason));
            }

            if (adminLogger != null) {
                adminLogger.log("WARN", finalPlayerName, finalIssuer, finalReason, "—", finalHidden ? "СКРЫТО" : "ПУБЛИЧНО");
            }
        });

        return true;
    }

    public boolean unwarnPlayer(String playerName, String issuer, String reason, boolean broadcast) {
        List<WarnEntry> list = warns.get(playerName);
        if (list == null || list.isEmpty()) {
            return false;
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            list.remove(list.size() - 1);
            if (list.isEmpty()) {
                warns.remove(finalPlayerName);
                warnExpiry.remove(finalPlayerName);
            }
            
            // Добавляем в историю
            HistoryEntry entry = new HistoryEntry();
            entry.type = "unwarn";
            entry.player = finalPlayerName;
            entry.issuer = finalIssuer;
            entry.reason = finalReason;
            entry.duration = "навсегда";
            entry.timestamp = System.currentTimeMillis();
            entry.hidden = false;
            entry.ipBan = false;
            entry.expiry = -1;
            addHistorySync(finalPlayerName, entry);

            if (finalBroadcast) {
                String msg = "§4❨！❩ §fИгрок §9" + finalIssuer + " §fснял предупреждение §a" + finalPlayerName + 
                             " §fпо причине: §7" + getReason(finalReason) + " §8(глобальный)";
                Bukkit.broadcastMessage(colorize(msg));
            }

            if (adminLogger != null) {
                adminLogger.log("UNWARN", finalPlayerName, finalIssuer, finalReason, "—", "ПУБЛИЧНО");
            }
        });

        return true;
    }

    public List<WarnEntry> getWarns(String playerName) {
        return warns.getOrDefault(playerName, new ArrayList<>());
    }

    // =========================================================
    // ==== СОБЫТИЯ =====
    // =========================================================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        String ip = player.getAddress() != null ? player.getAddress().getHostString() : "—";
        UUID uuid = player.getUniqueId();

        plugin.getLogger().info("🔍 ПРОВЕРКА ВХОДА: " + playerName + " | БАН=" + isBanned(playerName) + " | IP БАН=" + isIpBanned(playerName, ip));

        // Проверяем IP бан
        if (isIpBanned(playerName, ip)) {
            long expiry = ipBanExpiry.getOrDefault(playerName, -1L);
            String issuer = ipBanIssuers.getOrDefault(playerName, "Администрация");
            String reason = ipBanReasons.getOrDefault(playerName, "IP бан");
            plugin.getLogger().info("❌ IP БАН для " + playerName);
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, getBanMessage(playerName, issuer, reason, expiry, true));
            return;
        }

        // Проверяем обычный бан
        if (isBanned(playerName)) {
            long expiry = bans.getOrDefault(playerName, -1L);
            String issuer = banIssuers.getOrDefault(playerName, "Администрация");
            String reason = banReasons.getOrDefault(playerName, "Без причины");
            plugin.getLogger().info("❌ БАН ДЛЯ " + playerName + " НАЙДЕН! Кикаем...");
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, getBanMessage(playerName, issuer, reason, expiry, false));
            return;
        }

        // Сохраняем IP игрока
        setPlayerIp(playerName, ip);
        playerJoinTimes.put(playerName, System.currentTimeMillis());
        plugin.getLogger().info("✅ ВХОД РАЗРЕШЕН для " + playerName);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        UUID uuid = player.getUniqueId();

        playerJoinTimes.put(playerName, System.currentTimeMillis());

        if (isBanned(playerName)) {
            long expiry = bans.getOrDefault(playerName, -1L);
            String issuer = banIssuers.getOrDefault(playerName, "Администрация");
            String reason = banReasons.getOrDefault(playerName, "Без причины");
            player.kickPlayer(getBanMessage(playerName, issuer, reason, expiry, false));
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isMuted(player.getName())) {
            long expiry = mutes.getOrDefault(player.getName(), -1L);
            String issuer = muteIssuers.getOrDefault(player.getName(), "Администрация");
            String reason = muteReasons.getOrDefault(player.getName(), "Без причины");
            player.sendMessage(getMuteMessage(player.getName(), issuer, reason, expiry));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage().substring(1).split(" ")[0];

        if (isMuted(player.getName())) {
            String cmdLower = command.toLowerCase();
            for (String allowed : allowedCommands) {
                if (cmdLower.startsWith(allowed)) {
                    return;
                }
            }
            event.setCancelled(true);
            player.sendMessage("§cВы не можете использовать команды во время мута!");
        }
    }

    // =========================================================
    // ==== SEEN =====
    // =========================================================
    public String getSeenInfo(String playerName, boolean isOnline) {
        StringBuilder sb = new StringBuilder();
        UUID uuid = getPlayerUuid(playerName);
        
        if (isOnline) {
            Player player = Bukkit.getPlayer(playerName);
            if (player != null) {
                long joinTime = playerJoinTimes.getOrDefault(playerName, System.currentTimeMillis());
                long onlineTime = System.currentTimeMillis() - joinTime;
                String timeStr = formatTime(onlineTime);
                sb.append("§6Игрок §c").append(playerName).append(" §aонлайн §6в течение §c").append(timeStr).append("\n");
                sb.append(" §6- §6UUID: §f").append(uuid != null ? uuid.toString() : "—");
            }
        } else {
            long offlineTime = System.currentTimeMillis() - playerJoinTimes.getOrDefault(playerName, System.currentTimeMillis());
            String timeStr = formatTime(offlineTime);
            boolean isWhitelisted = isPlayerWhitelisted(playerName);
            
            sb.append("§6Игрок §c").append(playerName).append(" §4офлайн §6в течение §c").append(timeStr).append("\n");
            sb.append(" §6- §6UUID: §f").append(uuid != null ? uuid.toString() : "—").append("\n");
            sb.append(" §6- §6В белом списке: ").append(isWhitelisted ? "§aправда" : "§4ложь").append("\n");
            
            String location = getPlayerLastLocation(playerName);
            if (location != null && !location.isEmpty()) {
                sb.append(" §6- §6Местоположение: §f").append(location);
            }
        }
        
        return sb.toString();
    }

    private UUID getPlayerUuid(String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        if (player != null) {
            return player.getUniqueId();
        }
        try {
            File usercache = new File("usercache.json");
            if (usercache.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(usercache.toPath()));
                int idx = content.indexOf("\"name\":\"" + playerName + "\"");
                if (idx != -1) {
                    int uuidIdx = content.lastIndexOf("\"uuid\":\"", idx);
                    if (uuidIdx != -1) {
                        String uuidStr = content.substring(uuidIdx + 9, content.indexOf("\"", uuidIdx + 10));
                        return UUID.fromString(uuidStr);
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private boolean isPlayerWhitelisted(String playerName) {
        try {
            File whitelist = new File("whitelist.json");
            if (whitelist.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(whitelist.toPath()));
                return content.contains("\"name\":\"" + playerName + "\"");
            }
        } catch (Exception e) {}
        return false;
    }

    private String getPlayerLastLocation(String playerName) {
        return "неизвестно";
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000;
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
        
        if (sb.length() == 0) return "только что";
        return sb.toString().trim();
    }

    // =========================================================
    // ==== GETTERS =====
    // =========================================================

    public boolean isBanned(String playerName) {
        Long expiry = bans.get(playerName);
        if (expiry != null) {
            if (expiry == -1) return true;
            if (System.currentTimeMillis() > expiry) {
                bans.remove(playerName);
                banIssuers.remove(playerName);
                banReasons.remove(playerName);
                return false;
            }
            return true;
        }
        return false;
    }

    public boolean isMuted(String playerName) {
        Long expiry = mutes.get(playerName);
        if (expiry == null) return false;
        if (expiry == -1) return true;
        if (System.currentTimeMillis() > expiry) {
            mutes.remove(playerName);
            muteIssuers.remove(playerName);
            muteReasons.remove(playerName);
            return false;
        }
        return true;
    }

    public boolean isValidTime(String time) {
        if (time == null || time.isEmpty()) return true;
        if (time.equals("навсегда")) return true;
        return time.matches("\\d+[smhdwMy]");
    }

    public String getBanIssuer(String playerName) {
        return banIssuers.get(playerName);
    }

    public String getBanReason(String playerName) {
        return banReasons.get(playerName);
    }

    public String getMuteIssuer(String playerName) {
        return muteIssuers.get(playerName);
    }

    public String getMuteReason(String playerName) {
        return muteReasons.get(playerName);
    }

    public long getBanExpiry(String playerName) {
        Long value = bans.get(playerName);
        return value != null ? value : -1L;
    }

    public long getMuteExpiry(String playerName) {
        Long value = mutes.get(playerName);
        return value != null ? value : -1L;
    }

    public List<String> getBanList() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : bans.entrySet()) {
            String playerName = entry.getKey();
            long expiry = entry.getValue();
            String expiryStr = expiry == -1 ? "навсегда" : formatTimeLeft(expiry);
            String issuer = banIssuers.getOrDefault(playerName, "—");
            result.add("§c" + playerName + " §7— §f" + expiryStr + " §7(" + issuer + ")");
        }
        return result;
    }

    public List<String> getBanList(int page, int pageSize) {
        List<String> all = getBanList();
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, all.size());
        if (start >= all.size()) return new ArrayList<>();
        return all.subList(start, end);
    }

    public List<String> getMuteList() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : mutes.entrySet()) {
            String playerName = entry.getKey();
            long expiry = entry.getValue();
            String expiryStr = expiry == -1 ? "навсегда" : formatTimeLeft(expiry);
            String issuer = muteIssuers.getOrDefault(playerName, "—");
            result.add("§e" + playerName + " §7— §f" + expiryStr + " §7(" + issuer + ")");
        }
        return result;
    }

    public List<String> getMuteList(int page, int pageSize) {
        List<String> all = getMuteList();
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, all.size());
        if (start >= all.size()) return new ArrayList<>();
        return all.subList(start, end);
    }

    public List<HistoryEntry> getHistory(String playerName) {
        return history.getOrDefault(playerName, new ArrayList<>());
    }

    public HistoryEntry getLastBan(String playerName) {
        List<HistoryEntry> list = history.get(playerName);
        if (list == null || list.isEmpty()) return null;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).type.equals("ban")) {
                return list.get(i);
            }
        }
        return null;
    }

    public HistoryEntry getLastMute(String playerName) {
        List<HistoryEntry> list = history.get(playerName);
        if (list == null || list.isEmpty()) return null;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).type.equals("mute")) {
                return list.get(i);
            }
        }
        return null;
    }

    public String getFormattedHistory(String playerName, int limit) {
        List<HistoryEntry> historyList = getHistory(playerName);
        if (historyList.isEmpty()) {
            return "[БОТ] Ответ от сервера:\nНет наказаний для " + playerName;
        }

        historyList.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

        List<HistoryEntry> recent = historyList.stream()
            .limit(limit > 0 ? limit : historyList.size())
            .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("[БОТ] Ответ от сервера:\n");
        sb.append("История нарушений игрока ").append(playerName)
          .append(" (Записей: ").append(historyList.size()).append(")\n");

        for (HistoryEntry entry : recent) {
            String timeAgo = getTimeAgo(entry.timestamp);
            String status = "";
            if (entry.type.equals("ban") && isBanned(playerName)) {
                status = " [Активен]";
            } else if (entry.type.equals("ban") && !isBanned(playerName)) {
                status = " [Истек]";
            } else if (entry.type.equals("mute") && isMuted(playerName)) {
                status = " [Активен]";
            } else if (entry.type.equals("mute") && !isMuted(playerName)) {
                status = " [Истек]";
            }

            String actionName = entry.getActionName();
            String durationText = entry.duration.equals("навсегда") ? "навсегда" : entry.duration;
            
            sb.append(" - ").append(timeAgo).append(" -\n");
            sb.append(" ").append(playerName).append(" был ").append(actionName)
              .append(" на ").append(durationText).append(" ")
              .append(entry.issuer).append(": ").append(entry.reason)
              .append(" (глобальный)").append(status).append("\n");
        }

        return sb.toString();
    }

    public String getFormattedShist(String issuerName, int limit) {
        List<HistoryEntry> allHistory = new ArrayList<>();

        for (Map.Entry<String, List<HistoryEntry>> entry : history.entrySet()) {
            for (HistoryEntry he : entry.getValue()) {
                if (he.issuer.equalsIgnoreCase(issuerName)) {
                    allHistory.add(he);
                }
            }
        }

        if (allHistory.isEmpty()) {
            return "[БОТ] Ответ от сервера:\n" + issuerName + " не выдавал наказаний";
        }

        allHistory.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

        List<HistoryEntry> recent = allHistory.stream()
            .limit(limit > 0 ? limit : allHistory.size())
            .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("[БОТ] Ответ от сервера:\n");
        sb.append("История наказаний игроком ").append(issuerName)
          .append(" (Записей: ").append(allHistory.size()).append(")\n");

        for (HistoryEntry entry : recent) {
            String timeAgo = getTimeAgo(entry.timestamp);
            String status = "";
            if (entry.type.equals("ban") && isBanned(entry.player)) {
                status = " [Активен]";
            } else if (entry.type.equals("ban") && !isBanned(entry.player)) {
                status = " [Истек]";
            } else if (entry.type.equals("mute") && isMuted(entry.player)) {
                status = " [Активен]";
            } else if (entry.type.equals("mute") && !isMuted(entry.player)) {
                status = " [Истек]";
            }

            String actionName = entry.getActionName();
            String durationText = entry.duration.equals("навсегда") ? "навсегда" : entry.duration;
            
            sb.append(" - ").append(timeAgo).append(" -\n");
            sb.append(" ").append(entry.player).append(" был ").append(actionName)
              .append(" на ").append(durationText).append(" ")
              .append(entry.issuer).append(": ").append(entry.reason)
              .append(" (глобальный)").append(status).append("\n");
        }

        return sb.toString();
    }

    // =========================================================
    // ==== ФОРМАТИРОВАНИЕ =====
    // =========================================================

    public String formatDuration(String duration) {
        if (duration == null || duration.equals("навсегда")) return "навсегда";
        char unit = duration.charAt(duration.length() - 1);
        long value = Long.parseLong(duration.substring(0, duration.length() - 1));
        switch (unit) {
            case 's': return value + " сек";
            case 'm': return value + " мин";
            case 'h': return value + " ч";
            case 'd': return value + " дн";
            case 'w': return value + " нед";
            case 'M': return value + " мес";
            case 'y': return value + " лет";
            default: return duration;
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

    public String formatTimeLeft(long expiry) {
        if (expiry == -1) return "навсегда";
        long diff = expiry - System.currentTimeMillis();
        if (diff <= 0) return "истек";
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        seconds %= 60;
        minutes %= 60;
        hours %= 24;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append(" дн ");
        if (hours > 0) sb.append(hours).append(" ч ");
        if (minutes > 0 && (days == 0 || hours == 0)) sb.append(minutes).append(" мин ");
        if (sb.length() == 0) return "менее минуты";
        return sb.toString().trim();
    }

    public String getTimeAgo(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        long months = days / 30;
        long years = days / 365;

        seconds %= 60;
        minutes %= 60;
        hours %= 24;
        days %= 30;
        months %= 12;

        StringBuilder sb = new StringBuilder();
        if (years > 0) sb.append(years).append(" лет ");
        if (months > 0) sb.append(months).append(" месяцев ");
        if (days > 0) sb.append(days).append(" дней ");
        if (hours > 0) sb.append(hours).append(" часов ");
        if (minutes > 0) sb.append(minutes).append(" минут ");
        if (seconds > 0 && sb.length() == 0) sb.append(seconds).append(" секунд ");

        if (sb.length() == 0) return "только что";
        return sb.toString().trim() + " назад";
    }

    public String getFormattedDateTime(long timestamp) {
        return dateFormat.format(new Date(timestamp));
    }

    // =========================================================
    // ==== ВНУТРЕННИЕ КЛАССЫ =====
    // =========================================================

    public static class HistoryEntry {
        public String type;
        public String player;
        public String issuer;
        public String reason;
        public String duration;
        public long timestamp;
        public boolean hidden = false;
        public boolean ipBan = false;
        public long expiry = -1;

        public String getActionName() {
            switch (type) {
                case "ban": return "забанен";
                case "mute": return "замучен";
                case "kick": return "кикнут";
                case "warn": return "предупрежден";
                case "unban": return "разбанен";
                case "unmute": return "размучен";
                case "unwarn": return "снят варн";
                case "ipban": return "забанен по IP";
                case "unbanip": return "разбанен по IP";
                default: return type;
            }
        }
    }

    public static class WarnEntry {
        public String issuer;
        public String reason;
        public long timestamp;
    }
}
