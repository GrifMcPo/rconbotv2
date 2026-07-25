package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class ChatManager implements Listener {

    private final JavaPlugin plugin;
    private final PunishmentManager punishmentManager;
    private final ColorParser colorParser;
    private String chatFormat;
    private boolean allowColors;

    public ChatManager(JavaPlugin plugin, PunishmentManager punishmentManager) {
        this.plugin = plugin;
        this.punishmentManager = punishmentManager;
        this.colorParser = new ColorParser();
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("✅ ChatManager загружен!");
    }

    private void loadConfig() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        chatFormat = config.getString("chat.format", "[G] {clan} {prefix} {player}: {message}");
        allowColors = config.getBoolean("chat.allow-colors", true);
        plugin.getLogger().info("✅ Формат чата: " + chatFormat);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // Проверка мута
        if (punishmentManager.isMuted(player.getName())) {
            String muteMsg = punishmentManager.getMuteMessage(player.getName());
            if (muteMsg != null) {
                player.sendMessage(muteMsg);
            }
            event.setCancelled(true);
            return;
        }

        // Форматирование сообщения
        String formattedMessage = formatMessage(player, message);
        
        // ВАЖНО: setFormat использует String.format(), поэтому экранируем %
        String safeFormat = formattedMessage.replace("%", "%%");
        event.setFormat(safeFormat);

        plugin.getLogger().info("[CHAT] " + formattedMessage);
    }

    private String formatMessage(Player player, String message) {
        String clanName = getClanName(player);
        String prefix = getPrefix(player);
        String playerName = player.getDisplayName();

        String formatted = chatFormat
                .replace("{clan}", clanName)
                .replace("{prefix}", prefix)
                .replace("{player}", playerName)
                .replace("{message}", message);

        if (allowColors) {
            formatted = colorParser.parseColors(formatted);
        } else {
            formatted = formatted.replace('&', '§');
        }

        return formatted;
    }

    private String getClanName(Player player) {
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                Object papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
                if (papi != null) {
                    try {
                        java.lang.reflect.Method method = papi.getClass().getMethod("setPlaceholders", Player.class, String.class);
                        if (method != null) {
                            Object result = method.invoke(null, player, "%simpleclans_clan_name%");
                            if (result instanceof String) {
                                String clan = (String) result;
                                if (clan != null && !clan.isEmpty() && !clan.equals("%simpleclans_clan_name%")) {
                                    return clan;
                                }
                            }
                        }
                    } catch (Exception e) {}
                }
            }
        } catch (Exception e) {}
        return "—";
    }

    private String getPrefix(Player player) {
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                Object papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
                if (papi != null) {
                    try {
                        java.lang.reflect.Method method = papi.getClass().getMethod("setPlaceholders", Player.class, String.class);
                        if (method != null) {
                            Object result = method.invoke(null, player, "%vault_prefix%");
                            if (result instanceof String) {
                                String prefix = (String) result;
                                if (prefix != null && !prefix.isEmpty() && !prefix.equals("%vault_prefix%")) {
                                    return prefix;
                                }
                            }
                        }
                    } catch (Exception e) {}
                }
            }
        } catch (Exception e) {}
        return "";
    }

    public void reload() {
        loadConfig();
    }

    public String getChatFormat() {
        return chatFormat;
    }

    public void setChatFormat(String format) {
        this.chatFormat = format;
        plugin.getConfig().set("chat.format", format);
        plugin.saveConfig();
    }
}
