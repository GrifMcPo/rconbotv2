package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
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

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PunishmentManager implements Listener {

    private final JavaPlugin plugin;
    private final AdminLogger adminLogger;
    private final DatabaseManager databaseManager;
    
    private final Map<String, List<HistoryEntry>> history = new ConcurrentHashMap<>();
    private final Map<String, Long> bans = new ConcurrentHashMap<>();
    private final Map<String, Long> mutes = new ConcurrentHashMap<>();
    private final Map<String, String> muteIssuers = new ConcurrentHashMap<>();
    private final Map<String, String> muteReasons = new ConcurrentHashMap<>();
    private final Map<String, String> banIssuers = new ConcurrentHashMap<>();
    private final Map<String, String> banReasons = new ConcurrentHashMap<>();
    private final Map<String, List<String>> bannedIps = new ConcurrentHashMap<>();
    private final Map<String, Long> ipBanExpiry = new ConcurrentHashMap<>();

    private final List<String> allowedCommands = Arrays.asList("msg", "tell", "r", "reply", "help", "pay", "balance", "bal");
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");

    public PunishmentManager(JavaPlugin plugin, AdminLogger adminLogger, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.adminLogger = adminLogger;
        this.databaseManager = databaseManager;
        loadHistory();
        loadActivePunishments();
        startExpiryChecker();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("✅ PunishmentManager загружен с SQLite!");
    }

    private void loadHistory() {
        history.clear();
        
        // Загружаем историю из SQLite
        databaseManager.getAllPlayers().thenAccept(players -> {
            for (Map<String, Object> player : players) {
                String uuid = (String) player.get("uuid");
                String playerName = (String) player.get("player_name");
                
                databaseManager.getPunishmentHistory(uuid, 1000).thenAccept(punishments -> {
                    List<HistoryEntry> entries = new ArrayList<>();
                    for (Map<String, Object> p : punishments) {
                        HistoryEntry he = new HistoryEntry();
                        he.type = (String) p.get("type");
                        he.player = playerName;
                        he.issuer = (String) p.get("issuer");
                        he.reason = (String) p.get("reason");
                        he.duration = (String) p.get("duration");
                        he.timestamp = (Long) p.get("timestamp");
                        he.hidden = (Boolean) p.get("hidden");
                        entries.add(he);
                    }
                    history.put(playerName, entries);
                });
            }
        });
        
        plugin.getLogger().info("Загружена история наказаний из SQLite");
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

        // Загружаем активные наказания из SQLite
        databaseManager.getAllActivePunishments().thenAccept(punishments -> {
            for (Map<String, Object> p : punishments) {
                String playerUuid = (String) p.get("player_uuid");
                String type = (String) p.get("type");
                String issuer = (String) p.get("issuer");
                String reason = (String) p.get("reason");
                String duration = (String) p.get("duration");
                long timestamp = (Long) p.get("timestamp");
                long expiry = (Long) p.get("expiry");

                // Получаем имя игрока по UUID
                databaseManager.getPlayerNameByUuid(playerUuid).thenAccept(playerName -> {
                    if (playerName == null) return;
                    
                    if (type.equals("ban")) {
                        bans.put(playerName, expiry);
                        banIssuers.put(playerName, issuer);
                        banReasons.put(playerName, reason);
                        plugin.getLogger().info("✅ Загружен бан для " + playerName);
                    } else if (type.equals("mute")) {
                        mutes.put(playerName, expiry);
                        muteIssuers.put(playerName, issuer);
                        muteReasons.put(playerName, reason);
                        plugin.getLogger().info("✅ Загружен мут для " + playerName);
                    }
                });
            }
        });
        
        plugin.getLogger().info("✅ Активные наказания загружены из SQLite!");
    }

    private void startExpiryChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                checkExpiredPunishments();
            }
        }.runTaskTimer(plugin, 20L, 20L);
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
                
                // Деактивируем в БД
                databaseManager.getPlayerUuidByName(playerName).thenAccept(uuid -> {
                    if (uuid != null) {
                        databaseManager.deactivatePunishmentsByType(uuid, "ban");
                    }
                });
                
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
                
                // Деактивируем в БД
                databaseManager.getPlayerUuidByName(playerName).thenAccept(uuid -> {
                    if (uuid != null) {
                        databaseManager.deactivatePunishmentsByType(uuid, "mute");
                    }
                });
                
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
                plugin.getLogger().info("Автоснятие IP бана: " + playerName);
            }
        }
    }

    // ============================================
    // ==== БАН =====
    // ============================================
    public boolean banPlayer(String playerName, String issuer, String reason, String duration, boolean hidden, boolean broadcast) {
        if (isBanned(playerName)) {
            return false;
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final String finalDuration = duration;
        final boolean finalHidden = hidden;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            // Получаем UUID игрока
            databaseManager.getPlayerUuidByName(finalPlayerName).thenAccept(playerUuid -> {
                if (playerUuid == null) {
                    // Если игрока нет в БД - создаем
                    Player player = Bukkit.getPlayer(finalPlayerName);
                    if (player != null) {
                        playerUuid = player.getUniqueId().toString();
                        databaseManager.linkPlayer(playerUuid, finalPlayerName, null, "—");
                    } else {
                        plugin.getLogger().warning("Не найден UUID для " + finalPlayerName);
                        return;
                    }
                }

                // Получаем UUID выдающего
                databaseManager.getPlayerUuidByName(finalIssuer).thenAccept(issuerUuid -> {
                    if (issuerUuid == null) {
                        issuerUuid = "CONSOLE";
                    }

                    long expiry = finalDuration.equals("навсегда") ? -1 : System.currentTimeMillis() + parseTimeToMillis(finalDuration);
                    
                    // Добавляем в БД
                    databaseManager.addPunishment(playerUuid, "ban", issuerUuid, finalReason, finalDuration, expiry, finalHidden);
                    
                    // Добавляем в память
                    bans.put(finalPlayerName, expiry);
                    banIssuers.put(finalPlayerName, finalIssuer);
                    banReasons.put(finalPlayerName, finalReason);

                    // Кикаем игрока
                    Player player = Bukkit.getPlayer(finalPlayerName);
                    if (player != null && player.isOnline()) {
                        String expiryStr = expiry == -1 ? "навсегда" : formatTimeLeft(expiry);
                        String kickMessage = "§c§lВаш аккаунт заблокирован!\n\n" +
                                "§fПричина: §c" + finalReason + "\n" +
                                "§fСервер: §cглобальный\n" +
                                "§fВыдал: §9" + finalIssuer + "\n" +
                                "§fИстекает через: §c" + expiryStr;
                        player.kickPlayer(kickMessage);
                    }

                    // Широковещательное сообщение
                    if (finalBroadcast && !finalHidden) {
                        String timeStr = finalDuration.equals("навсегда") ? "" : " §fна §b" + formatDuration(finalDuration) + " §f";
                        String msg = "§c§l(! ) §9Игрок " + finalIssuer + " §fзабанил §c" + finalPlayerName + 
                                     timeStr + "§fпо причине: §7" + finalReason + " (глобальный)";
                        Bukkit.broadcastMessage(msg);
                    }

                    // Логируем
                    if (adminLogger != null) {
                        adminLogger.log("BAN", finalPlayerName, finalIssuer, finalReason, finalDuration, finalHidden ? "СКРЫТО" : "ПУБЛИЧНО");
                    }
                });
            });
        });

        return true;
    }

    public boolean banPlayer(String playerName, String issuer, String reason, String duration) {
        return banPlayer(playerName, issuer, reason, duration, false, true);
    }

    // ============================================
    // ==== РАЗБАН =====
    // ============================================
    public boolean unbanPlayer(String playerName, String issuer, String reason, boolean broadcast) {
        if (!isBanned(playerName)) {
            return false;
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            databaseManager.getPlayerUuidByName(finalPlayerName).thenAccept(playerUuid -> {
                if (playerUuid != null) {
                    // Деактивируем бан
                    databaseManager.deactivatePunishmentsByType(playerUuid, "ban");
                }
            });

            bans.remove(finalPlayerName);
            banIssuers.remove(finalPlayerName);
            banReasons.remove(finalPlayerName);

            if (finalBroadcast) {
                String msg = "§c§l(! ) §9Игрок " + finalIssuer + " §fразбанил §c" + finalPlayerName + 
                             " §fпо причине: §7" + finalReason + " (глобальный)";
                Bukkit.broadcastMessage(msg);
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

    // ============================================
    // ==== МУТ =====
    // ============================================
    public boolean mutePlayer(String playerName, String issuer, String reason, String duration, boolean hidden, boolean broadcast) {
        if (isMuted(playerName)) {
            return false;
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final String finalDuration = duration;
        final boolean finalHidden = hidden;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            databaseManager.getPlayerUuidByName(finalPlayerName).thenAccept(playerUuid -> {
                if (playerUuid == null) {
                    Player player = Bukkit.getPlayer(finalPlayerName);
                    if (player != null) {
                        playerUuid = player.getUniqueId().toString();
                        databaseManager.linkPlayer(playerUuid, finalPlayerName, null, "—");
                    } else {
                        return;
                    }
                }

                databaseManager.getPlayerUuidByName(finalIssuer).thenAccept(issuerUuid -> {
                    if (issuerUuid == null) issuerUuid = "CONSOLE";

                    long expiry = finalDuration.equals("навсегда") ? -1 : System.currentTimeMillis() + parseTimeToMillis(finalDuration);
                    
                    databaseManager.addPunishment(playerUuid, "mute", issuerUuid, finalReason, finalDuration, expiry, finalHidden);
                    
                    mutes.put(finalPlayerName, expiry);
                    muteIssuers.put(finalPlayerName, finalIssuer);
                    muteReasons.put(finalPlayerName, finalReason);

                    Player player = Bukkit.getPlayer(finalPlayerName);
                    if (player != null && player.isOnline()) {
                        String expiryStr = expiry == -1 ? "навсегда" : formatTimeLeft(expiry);
                        String muteMessage = "§c§lВам заблокировали чат!\n\n" +
                                "§fПричина: §c" + finalReason + "\n" +
                                "§fВыдал: §9" + finalIssuer + "\n" +
                                "§fИстекает через: §c" + expiryStr;
                        player.sendMessage(muteMessage);
                    }

                    if (finalBroadcast && !finalHidden) {
                        String timeStr = finalDuration.equals("навсегда") ? "" : " §fна §b" + formatDuration(finalDuration) + " §f";
                        String msg = "§c§l(! ) §9Игрок " + finalIssuer + " §fзамутил §c" + finalPlayerName + 
                                     timeStr + "§fпо причине: §7" + finalReason + " (глобальный)";
                        Bukkit.broadcastMessage(msg);
                    }

                    if (adminLogger != null) {
                        adminLogger.log("MUTE", finalPlayerName, finalIssuer, finalReason, finalDuration, finalHidden ? "СКРЫТО" : "ПУБЛИЧНО");
                    }
                });
            });
        });

        return true;
    }

    public boolean mutePlayer(String playerName, String issuer, String reason, String duration) {
        return mutePlayer(playerName, issuer, reason, duration, false, true);
    }

    // ============================================
    // ==== РАЗМУТ =====
    // ============================================
    public boolean unmutePlayer(String playerName, String issuer, String reason, boolean broadcast) {
        if (!isMuted(playerName)) {
            return false;
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            databaseManager.getPlayerUuidByName(finalPlayerName).thenAccept(playerUuid -> {
                if (playerUuid != null) {
                    databaseManager.deactivatePunishmentsByType(playerUuid, "mute");
                }
            });

            mutes.remove(finalPlayerName);
            muteIssuers.remove(finalPlayerName);
            muteReasons.remove(finalPlayerName);

            if (finalBroadcast) {
                String msg = "§c§l(! ) §9Игрок " + finalIssuer + " §fразмутил §c" + finalPlayerName + 
                             " §fпо причине: §7" + finalReason + " (глобальный)";
                Bukkit.broadcastMessage(msg);
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

    // ============================================
    // ==== КИК =====
    // ============================================
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

            databaseManager.getPlayerUuidByName(finalPlayerName).thenAccept(playerUuid -> {
                if (playerUuid == null) {
                    playerUuid = player.getUniqueId().toString();
                    databaseManager.linkPlayer(playerUuid, finalPlayerName, null, "—");
                }

                databaseManager.getPlayerUuidByName(finalIssuer).thenAccept(issuerUuid -> {
                    if (issuerUuid == null) issuerUuid = "CONSOLE";
                    databaseManager.addPunishment(playerUuid, "kick", issuerUuid, finalReason, "навсегда", -1, finalHidden);
                });
            });

            String kickMessage = "§cВы были кикнуты!\n§7Причина: " + finalReason;
            player.kickPlayer(kickMessage);

            if (finalBroadcast && !finalHidden) {
                String msg = "§c§l(! ) §9Игрок " + finalIssuer + " §fкикнул §c" + finalPlayerName + 
                             " §fпо причине: §7" + finalReason + " (глобальный)";
                Bukkit.broadcastMessage(msg);
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

    // ============================================
    // ==== IP БАН =====
    // ============================================
    public boolean banIp(String playerName, String issuer, String reason, String duration, boolean hidden) {
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            return false;
        }

        String ip = player.getAddress() != null ? player.getAddress().getHostString() : "—";
        if (ip.equals("—")) return false;

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

            Player p = Bukkit.getPlayer(finalPlayerName);
            if (p != null && p.isOnline()) {
                p.kickPlayer("§c§lВаш IP адрес заблокирован!\n\n" +
                        "§fПричина: §c" + finalReason + "\n" +
                        "§fВыдал: §9" + finalIssuer + "\n" +
                        "§fИстекает через: §c" + (expiry == -1 ? "навсегда" : formatTimeLeft(expiry)));
            }

            banPlayer(finalPlayerName, finalIssuer, finalReason, finalDuration, finalHidden, true);
            plugin.getLogger().info("IP " + finalIp + " игрока " + finalPlayerName + " забанен на " + finalDuration);
        });

        return true;
    }

    public boolean unbanIp(String playerName, String issuer, String reason) {
        if (!bannedIps.containsKey(playerName)) {
            return false;
        }

        final String finalPlayerName = playerName;
        final String finalReason = reason;

        Bukkit.getScheduler().runTask(plugin, () -> {
            bannedIps.remove(finalPlayerName);
            ipBanExpiry.remove(finalPlayerName);
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
            return false;
        }
        
        return bannedIps.get(playerName).contains(ip);
    }

    // ============================================
    // ==== СОБЫТИЯ =====
    // ============================================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        String ip = player.getAddress() != null ? player.getAddress().getHostString() : "—";

        // Сохраняем IP в БД
        databaseManager.getPlayerUuidByName(playerName).thenAccept(uuid -> {
            if (uuid != null) {
                databaseManager.updateIp(uuid, ip);
            }
        });

        if (isIpBanned(playerName, ip)) {
            String kickMessage = "§c§lВаш IP адрес заблокирован!\n\n" +
                    "§fПричина: §cIP бан (глобальный)\n" +
                    "§fВыдал: §9Администрация";
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, kickMessage);
            return;
        }

        if (isBanned(playerName)) {
            String kickMessage = getFullBanMessage(playerName);
            if (kickMessage != null) {
                event.disallow(PlayerLoginEvent.Result.KICK_BANNED, kickMessage);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();

        // Сохраняем игрока в БД
        databaseManager.getPlayerUuidByName(playerName).thenAccept(uuid -> {
            if (uuid == null) {
                databaseManager.linkPlayer(player.getUniqueId().toString(), playerName, null, "—");
            }
        });

        if (isBanned(playerName)) {
            String kickMessage = getFullBanMessage(playerName);
            if (kickMessage != null) {
                player.kickPlayer(kickMessage);
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isMuted(player.getName())) {
            String msg = getMuteMessage(player.getName());
            if (msg != null) {
                player.sendMessage(msg);
            }
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

    // ============================================
    // ==== GETTERS =====
    // ============================================

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
        if (time == null) return false;
        if (time.equals("навсегда")) return true;
        return time.matches("\\d+[smhdwMy]");
    }

    public String getMuteIssuer(String playerName) {
        return muteIssuers.get(playerName);
    }

    public String getMuteReason(String playerName) {
        return muteReasons.get(playerName);
    }

    public String getMuteExpiry(String playerName) {
        Long expiry = mutes.get(playerName);
        if (expiry == null) return "навсегда";
        if (expiry == -1) return "навсегда";
        return formatTimeLeft(expiry);
    }

    public String getMuteMessage(String playerName) {
        if (!isMuted(playerName)) return null;
        Long expiry = mutes.get(playerName);
        String issuer = muteIssuers.get(playerName);
        String reason = muteReasons.get(playerName);
        String expiryStr = expiry == -1 ? "навсегда" : formatTimeLeft(expiry);

        return "§c§lВам заблокировали чат!\n\n" +
                "§fПричина: §c" + reason + "\n" +
                "§fВыдал: §9" + issuer + "\n" +
                "§fИстекает через: §c" + expiryStr + "\n\n" +
                "§7Вы не можете писать в чат и использовать команды!";
    }

    public String getBanExpiry(String playerName) {
        Long expiry = bans.get(playerName);
        if (expiry == null) return "навсегда";
        if (expiry == -1) return "навсегда";
        return formatTimeLeft(expiry);
    }

    public String getBanIssuer(String playerName) {
        return banIssuers.get(playerName);
    }

    public String getBanReason(String playerName) {
        return banReasons.get(playerName);
    }

    public String getFullBanMessage(String playerName) {
        if (!isBanned(playerName)) return null;
        Long expiry = bans.get(playerName);
        String issuer = banIssuers.get(playerName);
        String reason = banReasons.get(playerName);
        String expiryStr = expiry == -1 ? "навсегда" : formatTimeLeft(expiry);

        return "§c§lВаш аккаунт заблокирован!\n\n" +
                "§fПричина: §c" + reason + "\n" +
                "§fСервер: §cглобальный\n" +
                "§fВыдал: §9" + issuer + "\n" +
                "§fИстекает через: §c" + expiryStr;
    }

    public List<String> getBanList() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : bans.entrySet()) {
            String playerName = entry.getKey();
            long expiry = entry.getValue();
            String expiryStr = expiry == -1 ? "навсегда" : formatTimeLeft(expiry);
            result.add("§c" + playerName + " §7— §f" + expiryStr);
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
            result.add("§e" + playerName + " §7— §f" + expiryStr);
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
            return "[БОТ] Нет наказаний для " + playerName;
        }

        historyList.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

        List<HistoryEntry> recent = historyList.stream()
            .limit(limit > 0 ? limit : historyList.size())
            .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("[БОТ] Ответ сервера:\n");
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
            sb.append(" - ").append(timeAgo).append(" -\n");
            sb.append(" ").append(playerName).append(" был ").append(actionName)
              .append(" на ").append(entry.duration).append(" ")
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
            return "[БОТ] " + issuerName + " не выдавал наказаний";
        }

        allHistory.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

        List<HistoryEntry> recent = allHistory.stream()
            .limit(limit > 0 ? limit : allHistory.size())
            .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("[БОТ] Ответ сервера:\n");
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
            sb.append(" - ").append(timeAgo).append(" -\n");
            sb.append(" ").append(entry.player).append(" был ").append(actionName)
              .append(" на ").append(entry.duration).append(" ")
              .append(entry.issuer).append(": ").append(entry.reason)
              .append(" (глобальный)").append(status).append("\n");
        }

        return sb.toString();
    }

    // ============================================
    // ==== ФОРМАТИРОВАНИЕ =====
    // ============================================

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

    private String formatTimeLeft(long expiry) {
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

    // ============================================
    // ==== КЛАСС ИСТОРИИ =====
    // ============================================

    public static class HistoryEntry {
        public String type;
        public String player;
        public String issuer;
        public String reason;
        public String duration;
        public long timestamp;
        public boolean hidden = false;

        public String getActionName() {
            switch (type) {
                case "ban": return "забанен";
                case "mute": return "замучен";
                case "kick": return "кикнут";
                case "unban": return "разбанен";
                case "unmute": return "размучен";
                default: return type;
            }
        }
    }
}
