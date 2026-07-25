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
        chatFormat = config.getString("chat.format", "[G] «%clan%» %prefix% %player%: %message%");
        allowColors = config.getBoolean("chat.allow-colors", true);
        plugin.getLogger().info("✅ Формат чата: " + chatFormat);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (punishmentManager.isMuted(player.getName())) {
            String muteMsg = punishmentManager.getMuteMessage(player.getName());
            if (muteMsg != null) {
                player.sendMessage(muteMsg);
            }
            event.setCancelled(true);
            return;
        }

        String formattedMessage = formatMessage(player, message);
        event.setFormat(formattedMessage);

        plugin.getLogger().info("[CHAT] " + formattedMessage);
    }

    private String formatMessage(Player player, String message) {
        String clanName = getClanName(player);
        String prefix = getPrefix(player);
        String playerName = player.getDisplayName();

        String formatted = chatFormat
                .replace("%clan%", clanName)
                .replace("%prefix%", prefix)
                .replace("%player%", playerName)
                .replace("%message%", message);

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
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%simpleclans_clan_name%");
            }
        } catch (Exception e) {}
        return "—";
    }

    private String getPrefix(Player player) {
        try {
            net.milkbowl.vault.permission.Permission permission = Bukkit.getServicesManager()
                    .getRegistration(net.milkbowl.vault.permission.Permission.class).getProvider();
            if (permission != null) {
                return permission.getPlayerPrefix(player);
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
