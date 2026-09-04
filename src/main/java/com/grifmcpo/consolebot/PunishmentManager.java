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
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PunishmentManager implements Listener {

    private final TelegramConsoleBot plugin;
    private final AdminLogger adminLogger;
    private final SupabaseManager supabase;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");

    private final Map<String, Long> bans = new ConcurrentHashMap<>();
    private final Map<String, Long> mutes = new ConcurrentHashMap<>();
    private final Map<String, String> muteIssuers = new ConcurrentHashMap<>();
    private final Map<String, String> muteReasons = new ConcurrentHashMap<>();
    private final Map<String, String> banIssuers = new ConcurrentHashMap<>();
    private final Map<String, String> banReasons = new ConcurrentHashMap<>();
    private final Map<String, Long> playerJoinTimes = new ConcurrentHashMap<>();

    private final List<String> allowedCommands = Arrays.asList("msg", "tell", "r", "reply", "help", "pay", "balance", "bal", "me", "emote");

    public PunishmentManager(TelegramConsoleBot plugin, AdminLogger adminLogger) {
        this.plugin = plugin;
        this.adminLogger = adminLogger;
        this.supabase = plugin.getSupabaseManager();
        loadActivePunishments();
        startExpiryChecker();
        startCacheUpdater();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("✅ PunishmentManager загружен с Supabase!");
    }

    private void loadActivePunishments() {
        bans.clear();
        mutes.clear();
        muteIssuers.clear();
        muteReasons.clear();
        banIssuers.clear();
        banReasons.clear();

        supabase.getActiveBans().thenAcceptAsync(punishments -> {
            for (Map<String, Object> p : punishments) {
                String playerName = (String) p.get("player_name");
                String issuer = (String) p.get("issuer_name");
                String reason = (String) p.get("reason");
                long expiry = (Long) p.get("expiry");
                String type = (String) p.get("type");

                if (type.equals("ban")) {
                    bans.put(playerName, expiry);
                    banIssuers.put(playerName, issuer);
                    banReasons.put(playerName, reason);
                } else if (type.equals("mute")) {
                    mutes.put(playerName, expiry);
                    muteIssuers.put(playerName, issuer);
                    muteReasons.put(playerName, reason);
                }
            }
            plugin.getLogger().info("✅ Загружено банов: " + bans.size() + ", мутов: " + mutes.size());
        }).exceptionally(e -> {
            plugin.getLogger().severe("❌ Ошибка загрузки наказаний: " + e.getMessage());
            return null;
        });
    }

    private void startCacheUpdater() {
        new BukkitRunnable() {
            @Override
            public void run() {
                loadActivePunishments();
            }
        }.runTaskTimer(plugin, 20L * 60, 20L * 60);
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
                supabase.getPlayerUuidByName(playerName).thenAccept(uuid -> {
                    if (uuid != null) {
                        supabase.deactivatePunishmentsByType(uuid, "ban");
                    }
                });
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
                supabase.getPlayerUuidByName(playerName).thenAccept(uuid -> {
                    if (uuid != null) {
                        supabase.deactivatePunishmentsByType(uuid, "mute");
                    }
                });
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

    public boolean isValidTime(String time) {
        if (time == null || time.isEmpty()) return true;
        if (time.equals("навсегда")) return true;
        return time.matches("\\d+[smhdwMy]");
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
            supabase.getOrCreatePlayer(finalPlayerName, null).thenAccept(playerUuid -> {
                if (playerUuid == null) {
                    plugin.getLogger().warning("❌ Не удалось получить UUID для " + finalPlayerName);
                    return;
                }

                supabase.getOrCreatePlayer(finalIssuer, null).thenAccept(issuerUuid -> {
                    if (issuerUuid == null) issuerUuid = "CONSOLE";

                    long expiry = finalDuration.equals("навсегда") ? -1 : System.currentTimeMillis() + parseTimeToMillis(finalDuration);
                    long durationMs = finalDuration.equals("навсегда") ? -1 : parseTimeToMillis(finalDuration);

                    supabase.addPunishment(
                        playerUuid, finalPlayerName,
                        "ban", issuerUuid, finalIssuer,
                        finalReason, finalDuration, durationMs, expiry,
                        finalHidden, null
                    ).thenAccept(id -> {
                        if (id != -1) {
                            plugin.getLogger().info("✅ Бан сохранен в Supabase (ID: " + id + ")");
                            bans.put(finalPlayerName, expiry);
                            banIssuers.put(finalPlayerName, finalIssuer);
                            banReasons.put(finalPlayerName, finalReason);
                        }
                    });

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
            });
        });

        return true;
    }

    public boolean banPlayer(String playerName, String issuer, String reason, String duration) {
        return banPlayer(playerName, issuer, reason, duration, false, true);
    }

    public boolean banUuid(String uuid, String issuer, String reason, String duration, boolean hidden, boolean broadcast) {
        try {
            UUID.fromString(uuid);
            Player player = Bukkit.getPlayer(UUID.fromString(uuid));
            String playerName = player != null ? player.getName() : uuid;
            return banPlayer(playerName, issuer, reason, duration, hidden, broadcast);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean unbanPlayer(String playerName, String issuer, String reason, boolean broadcast) {
        if (!isBanned(playerName)) {
            return false;
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            supabase.getOrCreatePlayer(finalPlayerName, null).thenAccept(playerUuid -> {
                if (playerUuid == null) playerUuid = "CONSOLE";

                supabase.deactivatePunishmentsByType(playerUuid, "ban");

                supabase.getOrCreatePlayer(finalIssuer, null).thenAccept(issuerUuid -> {
                    if (issuerUuid == null) issuerUuid = "CONSOLE";
                    supabase.addPunishment(
                        playerUuid, finalPlayerName,
                        "unban", issuerUuid, finalIssuer,
                        finalReason, "навсегда", -1, -1,
                        false, null
                    );
                });

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
            supabase.getOrCreatePlayer(finalPlayerName, null).thenAccept(playerUuid -> {
                if (playerUuid == null) return;

                supabase.getOrCreatePlayer(finalIssuer, null).thenAccept(issuerUuid -> {
                    if (issuerUuid == null) issuerUuid = "CONSOLE";

                    long expiry = finalDuration.equals("навсегда") ? -1 : System.currentTimeMillis() + parseTimeToMillis(finalDuration);
                    long durationMs = finalDuration.equals("навсегда") ? -1 : parseTimeToMillis(finalDuration);

                    supabase.addPunishment(
                        playerUuid, finalPlayerName,
                        "mute", issuerUuid, finalIssuer,
                        finalReason, finalDuration, durationMs, expiry,
                        finalHidden, null
                    ).thenAccept(id -> {
                        if (id != -1) {
                            mutes.put(finalPlayerName, expiry);
                            muteIssuers.put(finalPlayerName, finalIssuer);
                            muteReasons.put(finalPlayerName, finalReason);
                        }
                    });

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
            });
        });

        return true;
    }

    public boolean mutePlayer(String playerName, String issuer, String reason, String duration) {
        return mutePlayer(playerName, issuer, reason, duration, false, true);
    }

    public boolean unmutePlayer(String playerName, String issuer, String reason, boolean broadcast) {
        if (!isMuted(playerName)) {
            return false;
        }

        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            supabase.getOrCreatePlayer(finalPlayerName, null).thenAccept(playerUuid -> {
                if (playerUuid == null) playerUuid = "CONSOLE";

                supabase.deactivatePunishmentsByType(playerUuid, "mute");

                supabase.getOrCreatePlayer(finalIssuer, null).thenAccept(issuerUuid -> {
                    if (issuerUuid == null) issuerUuid = "CONSOLE";
                    supabase.addPunishment(
                        playerUuid, finalPlayerName,
                        "unmute", issuerUuid, finalIssuer,
                        finalReason, "навсегда", -1, -1,
                        false, null
                    );
                });

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

            supabase.getOrCreatePlayer(finalPlayerName, null).thenAccept(playerUuid -> {
                if (playerUuid == null) return;

                supabase.getOrCreatePlayer(finalIssuer, null).thenAccept(issuerUuid -> {
                    if (issuerUuid == null) issuerUuid = "CONSOLE";

                    supabase.addPunishment(
                        playerUuid, finalPlayerName,
                        "kick", issuerUuid, finalIssuer,
                        finalReason, "навсегда", -1, -1,
                        finalHidden, null
                    );
                });
            });

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
    // ==== WARN =====
    // =========================================================
    public boolean warnPlayer(String playerName, String issuer, String reason, boolean hidden, boolean broadcast) {
        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final boolean finalHidden = hidden;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            supabase.getOrCreatePlayer(finalPlayerName, null).thenAccept(playerUuid -> {
                if (playerUuid == null) return;

                supabase.getOrCreatePlayer(finalIssuer, null).thenAccept(issuerUuid -> {
                    if (issuerUuid == null) issuerUuid = "CONSOLE";

                    supabase.addPunishment(
                        playerUuid, finalPlayerName,
                        "warn", issuerUuid, finalIssuer,
                        finalReason, "навсегда", -1, -1,
                        finalHidden, null
                    );
                });
            });

            if (finalBroadcast && !finalHidden) {
                String msg = "§4❨！❩ §fИгрок §9" + finalIssuer + " §fвыдал предупреждение §c" + finalPlayerName + 
                             " §fпо причине: §7" + getReason(finalReason) + " §8(глобальный)";
                Bukkit.broadcastMessage(colorize(msg));
            }

            Player player = Bukkit.getPlayer(finalPlayerName);
            if (player != null && player.isOnline()) {
                player.sendMessage("§c§lВам выдали предупреждение!\n§fПричина: " + getReason(finalReason));
            }

            if (adminLogger != null) {
                adminLogger.log("WARN", finalPlayerName, finalIssuer, finalReason, "—", finalHidden ? "СКРЫТО" : "ПУБЛИЧНО");
            }
        });

        return true;
    }

    public boolean unwarnPlayer(String playerName, String issuer, String reason, boolean broadcast) {
        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;
        final boolean finalBroadcast = broadcast;

        Bukkit.getScheduler().runTask(plugin, () -> {
            supabase.getPlayerUuidByName(finalPlayerName).thenAccept(playerUuid -> {
                if (playerUuid != null) {
                    supabase.deactivatePunishmentsByType(playerUuid, "warn");
                }
            });

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

    // =========================================================
    // ==== IP БАН =====
    // =========================================================
    public boolean banIp(String playerName, String issuer, String reason, String duration, boolean hidden) {
        Player player = Bukkit.getPlayer(playerName);
        String ip = "—";
        
        if (player != null && player.getAddress() != null) {
            ip = player.getAddress().getHostString();
        } else {
            ip = plugin.getPlayerIp(playerName);
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
            supabase.getOrCreatePlayer(finalPlayerName, finalIp).thenAccept(playerUuid -> {
                if (playerUuid == null) return;

                supabase.getOrCreatePlayer(finalIssuer, null).thenAccept(issuerUuid -> {
                    if (issuerUuid == null) issuerUuid = "CONSOLE";

                    long expiry = finalDuration.equals("навсегда") ? -1 : System.currentTimeMillis() + parseTimeToMillis(finalDuration);
                    long durationMs = finalDuration.equals("навсегда") ? -1 : parseTimeToMillis(finalDuration);

                    supabase.addPunishment(
                        playerUuid, finalPlayerName,
                        "ipban", issuerUuid, finalIssuer,
                        finalReason, finalDuration, durationMs, expiry,
                        finalHidden, finalIp
                    );

                    supabase.addPunishment(
                        playerUuid, finalPlayerName,
                        "ban", issuerUuid, finalIssuer,
                        finalReason + " (IP: " + finalIp + ")", finalDuration, durationMs, expiry,
                        finalHidden, finalIp
                    );

                    bans.put(finalPlayerName, expiry);
                    banIssuers.put(finalPlayerName, finalIssuer);
                    banReasons.put(finalPlayerName, finalReason + " (IP бан)");
                });
            });

            Player p = Bukkit.getPlayer(finalPlayerName);
            if (p != null && p.isOnline()) {
                long expiry = finalDuration.equals("навсегда") ? -1 : System.currentTimeMillis() + parseTimeToMillis(finalDuration);
                p.kickPlayer(getBanMessage(finalPlayerName, finalIssuer, finalReason, expiry, true));
            }

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
        final String finalPlayerName = playerName;
        final String finalIssuer = issuer;
        final String finalReason = reason;

        Bukkit.getScheduler().runTask(plugin, () -> {
            supabase.getPlayerUuidByName(finalPlayerName).thenAccept(playerUuid -> {
                if (playerUuid != null) {
                    supabase.deactivatePunishmentsByType(playerUuid, "ipban");
                }
            });

            bans.remove(finalPlayerName);
            banIssuers.remove(finalPlayerName);
            banReasons.remove(finalPlayerName);

            String msg = "§4❨！❩ §fИгрок §9" + finalIssuer + " §fразбанил IP §a" + finalPlayerName + 
                         " §fпо причине: §7" + getReason(finalReason) + " §8(глобальный)";
            Bukkit.broadcastMessage(colorize(msg));
        });

        return true;
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
        return bans.getOrDefault(playerName, -1L);
    }

    public long getMuteExpiry(String playerName) {
        return mutes.getOrDefault(playerName, -1L);
    }

    // =========================================================
    // ==== BANLIST / MUTELIST =====
    // =========================================================
    public List<String> getBanList() {
        List<String> result = new ArrayList<>();
        try {
            List<Map<String, Object>> bans = supabase.getActiveBans().get();
            for (Map<String, Object> ban : bans) {
                String playerName = (String) ban.get("player_name");
                String issuer = (String) ban.get("issuer_name");
                long expiry = (Long) ban.get("expiry");
                String expiryStr = expiry == -1 ? "навсегда" : formatTimeLeft(expiry);
                result.add("§c" + playerName + " §7— §f" + expiryStr + " §7(" + issuer + ")");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка получения banlist: " + e.getMessage());
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
        try {
            List<Map<String, Object>> mutes = supabase.getActiveMutes().get();
            for (Map<String, Object> mute : mutes) {
                String playerName = (String) mute.get("player_name");
                String issuer = (String) mute.get("issuer_name");
                long expiry = (Long) mute.get("expiry");
                String expiryStr = expiry == -1 ? "навсегда" : formatTimeLeft(expiry);
                result.add("§e" + playerName + " §7— §f" + expiryStr + " §7(" + issuer + ")");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка получения mutelist: " + e.getMessage());
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

    // =========================================================
    // ==== HIST / SHIST / LASTBAN / LASTMUTE =====
    // =========================================================
    public List<Map<String, Object>> getHistory(String playerName) {
        try {
            String uuid = supabase.getPlayerUuidByName(playerName).get();
            if (uuid == null) return new ArrayList<>();
            return supabase.getPunishmentHistory(uuid, 100).get();
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка получения истории: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public Map<String, Object> getLastBan(String playerName) {
        try {
            String uuid = supabase.getPlayerUuidByName(playerName).get();
            if (uuid == null) return null;
            List<Map<String, Object>> list = supabase.getPunishmentHistory(uuid, 1).get();
            for (Map<String, Object> p : list) {
                if (p.get("type").equals("ban")) {
                    return p;
                }
            }
        } catch (Exception e) {}
        return null;
    }

    public Map<String, Object> getLastMute(String playerName) {
        try {
            String uuid = supabase.getPlayerUuidByName(playerName).get();
            if (uuid == null) return null;
            List<Map<String, Object>> list = supabase.getPunishmentHistory(uuid, 1).get();
            for (Map<String, Object> p : list) {
                if (p.get("type").equals("mute")) {
                    return p;
                }
            }
        } catch (Exception e) {}
        return null;
    }

    // =========================================================
    // ==== CHECKBAN / CHECKMUTE =====
    // =========================================================
    public String getFormattedCheckBan(String playerName) {
        try {
            String uuid = supabase.getPlayerUuidByName(playerName).get();
            if (uuid == null) {
                return "[БОТ] Ответ от сервера:\nИгрок " + playerName + " не найден в базе данных!";
            }

            Map<String, Object> ban = supabase.getActiveBan(uuid).get();
            if (ban == null) {
                return "[БОТ] Ответ от сервера:\nИгрок " + playerName + " не забанен.";
            }

            String issuer = (String) ban.get("issuer_name");
            String reason = (String) ban.get("reason");
            long expiry = (Long) ban.get("expiry");
            long timestamp = (Long) ban.get("timestamp");
            boolean hidden = (Boolean) ban.get("hidden");
            boolean ipBan = ban.containsKey("ip") && ban.get("ip") != null;
            boolean isPermanent = expiry == -1;

            String expiryStr = isPermanent ? "навсегда" : formatTimeLeft(expiry);

            String response = "[БОТ] Ответ от сервера:\n";
            response += "----- " + playerName + " -----\n";
            response += " Причина: " + reason + "\n";
            response += " Время: " + getFormattedDateTime(timestamp) + "\n";
            response += " Истекает: " + expiryStr + "\n";
            response += " Сервер: выживание\n";
            response += " Выдал: " + issuer + "\n";
            response += " IP: " + (ipBan ? "да" : "нет") + ", скрыто: " + (hidden ? "да" : "нет") + ", навсегда: " + (isPermanent ? "да" : "нет");

            return response;
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Ошибка checkban: " + e.getMessage());
            return "[БОТ] Ошибка получения информации!";
        }
    }

    public String getFormattedCheckMute(String playerName) {
        try {
            String uuid = supabase.getPlayerUuidByName(playerName).get();
            if (uuid == null) {
                return "[БОТ] Ответ от сервера:\nИгрок " + playerName + " не найден в базе данных!";
            }

            Map<String, Object> mute = supabase.getActiveMute(uuid).get();
            if (mute == null) {
                return "[БОТ] Ответ от сервера:\nИгрок " + playerName + " не замучен.";
            }

            String issuer = (String) mute.get("issuer_name");
            String reason = (String) mute.get("reason");
            long expiry = (Long) mute.get("expiry");
            long timestamp = (Long) mute.get("timestamp");
            boolean hidden = (Boolean) mute.get("hidden");
            boolean isPermanent = expiry == -1;

            String expiryStr = isPermanent ? "навсегда" : formatTimeLeft(expiry);

            String response = "[БОТ] Ответ от сервера:\n";
            response += "----- " + playerName + " -----\n";
            response += " Причина: " + reason + "\n";
            response += " Время: " + getFormattedDateTime(timestamp) + "\n";
            response += " Истекает: " + expiryStr + "\n";
            response += " Сервер: выживание\n";
            response += " Выдал: " + issuer + "\n";
            response += " IP: нет, скрыто: " + (hidden ? "да" : "нет") + ", навсегда: " + (isPermanent ? "да" : "нет");

            return response;
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Ошибка checkmute: " + e.getMessage());
            return "[БОТ] Ошибка получения информации!";
        }
    }

    // =========================================================
    // ==== HIST / SHIST ФОРМАТИРОВАННЫЕ =====
    // =========================================================
    public String getFormattedHistory(String playerName, int limit) {
        try {
            String uuid = supabase.getPlayerUuidByName(playerName).get();
            if (uuid == null) {
                return "[БОТ] Ответ от сервера:\nНет наказаний для " + playerName;
            }

            List<Map<String, Object>> punishments = supabase.getPunishmentHistory(uuid, limit).get();

            if (punishments.isEmpty()) {
                return "[БОТ] Ответ от сервера:\nНет наказаний для " + playerName;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[БОТ] Ответ от сервера:\n");
            sb.append("История нарушений игрока ").append(playerName)
              .append(" (Записей: ").append(punishments.size()).append(")\n");

            for (Map<String, Object> p : punishments) {
                String type = (String) p.get("type");
                String issuer = (String) p.get("issuer_name");
                String reason = (String) p.get("reason");
                String duration = (String) p.get("duration");
                long timestamp = (Long) p.get("timestamp");
                long expiry = (Long) p.get("expiry");
                boolean active = (Boolean) p.get("active");

                String timeAgo = getTimeAgo(timestamp);
                String status = "";
                if (type.equals("ban") && active) {
                    status = " [Активен]";
                } else if (type.equals("ban") && !active) {
                    status = " [Истек]";
                } else if (type.equals("mute") && active) {
                    status = " [Активен]";
                } else if (type.equals("mute") && !active) {
                    status = " [Истек]";
                }

                String actionName = switch (type) {
                    case "ban" -> "забанен";
                    case "mute" -> "замучен";
                    case "kick" -> "кикнут";
                    case "warn" -> "предупрежден";
                    case "unban" -> "разбанен";
                    case "unmute" -> "размучен";
                    case "ipban" -> "забанен по IP";
                    default -> type;
                };

                String durationText = duration != null ? duration : "навсегда";
                
                sb.append(" - ").append(timeAgo).append(" -\n");
                sb.append(" ").append(playerName).append(" был ").append(actionName)
                  .append(" на ").append(durationText).append(" ")
                  .append(issuer).append(": ").append(reason)
                  .append(" (глобальный)").append(status).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Ошибка получения истории: " + e.getMessage());
            return "[БОТ] Ошибка получения истории!";
        }
    }

    public String getFormattedShist(String issuerName, int limit) {
        try {
            String uuid = supabase.getPlayerUuidByName(issuerName).get();
            if (uuid == null) {
                return "[БОТ] Ответ от сервера:\n" + issuerName + " не выдавал наказаний";
            }

            List<Map<String, Object>> punishments = supabase.getIssuerHistory(uuid, limit).get();
            if (punishments.isEmpty()) {
                return "[БОТ] Ответ от сервера:\n" + issuerName + " не выдавал наказаний";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[БОТ] Ответ от сервера:\n");
            sb.append("История наказаний игроком ").append(issuerName)
              .append(" (Записей: ").append(punishments.size()).append(")\n");

            for (Map<String, Object> p : punishments) {
                String type = (String) p.get("type");
                String playerName = (String) p.get("player_name");
                String reason = (String) p.get("reason");
                String duration = (String) p.get("duration");
                long timestamp = (Long) p.get("timestamp");
                long expiry = (Long) p.get("expiry");
                boolean active = (Boolean) p.get("active");

                String timeAgo = getTimeAgo(timestamp);
                String status = "";
                if (type.equals("ban") && active) {
                    status = " [Активен]";
                } else if (type.equals("ban") && !active) {
                    status = " [Истек]";
                } else if (type.equals("mute") && active) {
                    status = " [Активен]";
                } else if (type.equals("mute") && !active) {
                    status = " [Истек]";
                }

                String actionName = switch (type) {
                    case "ban" -> "забанен";
                    case "mute" -> "замучен";
                    case "kick" -> "кикнут";
                    case "warn" -> "предупрежден";
                    case "unban" -> "разбанен";
                    case "unmute" -> "размучен";
                    case "ipban" -> "забанен по IP";
                    default -> type;
                };

                String durationText = duration != null ? duration : "навсегда";
                
                sb.append(" - ").append(timeAgo).append(" -\n");
                sb.append(" ").append(playerName).append(" был ").append(actionName)
                  .append(" на ").append(durationText).append(" ")
                  .append(issuerName).append(": ").append(reason)
                  .append(" (глобальный)").append(status).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Ошибка получения shist: " + e.getMessage());
            return "[БОТ] Ошибка получения shist!";
        }
    }

    public String getSeenInfo(String playerName, boolean isOnline) {
        StringBuilder sb = new StringBuilder();
        
        try {
            String uuid = supabase.getPlayerUuidByName(playerName).get();
            if (uuid == null) {
                return "§cИгрок " + playerName + " не найден в базе данных!";
            }

            if (isOnline) {
                Player player = Bukkit.getPlayer(playerName);
                if (player != null) {
                    long joinTime = playerJoinTimes.getOrDefault(playerName, System.currentTimeMillis());
                    long onlineTime = System.currentTimeMillis() - joinTime;
                    String timeStr = formatTime(onlineTime);
                    sb.append("§6Игрок §c").append(playerName).append(" §aонлайн §6в течение §c").append(timeStr).append("\n");
                    sb.append(" §6- §6UUID: §f").append(uuid);
                }
            } else {
                long offlineTime = System.currentTimeMillis() - playerJoinTimes.getOrDefault(playerName, System.currentTimeMillis());
                String timeStr = formatTime(offlineTime);
                boolean isWhitelisted = isPlayerWhitelisted(playerName);
                
                sb.append("§6Игрок §c").append(playerName).append(" §4офлайн §6в течение §c").append(timeStr).append("\n");
                sb.append(" §6- §6UUID: §f").append(uuid).append("\n");
                sb.append(" §6- §6В белом списке: ").append(isWhitelisted ? "§aправда" : "§4ложь").append("\n");
                sb.append(" §6- §6Местоположение: §fнеизвестно");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка получения seen: " + e.getMessage());
            return "§cОшибка получения информации!";
        }
        
        return sb.toString();
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
    // ==== СОБЫТИЯ =====
    // =========================================================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        String ip = player.getAddress() != null ? player.getAddress().getHostString() : "—";

        supabase.getOrCreatePlayer(playerName, ip);

        if (isBanned(playerName)) {
            long expiry = getBanExpiry(playerName);
            String issuer = getBanIssuer(playerName);
            String reason = getBanReason(playerName);
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, 
                getBanMessage(playerName, issuer != null ? issuer : "Администрация", 
                reason != null ? reason : "Без причины", expiry, false));
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        String ip = player.getAddress() != null ? player.getAddress().getHostString() : "—";

        supabase.updatePlayerIp(player.getUniqueId().toString(), ip);
        playerJoinTimes.put(playerName, System.currentTimeMillis());
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isMuted(player.getName())) {
            long expiry = getMuteExpiry(player.getName());
            String issuer = getMuteIssuer(player.getName());
            String reason = getMuteReason(player.getName());
            player.sendMessage(getMuteMessage(player.getName(), 
                issuer != null ? issuer : "Администрация", 
                reason != null ? reason : "Без причины", expiry));
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
}
