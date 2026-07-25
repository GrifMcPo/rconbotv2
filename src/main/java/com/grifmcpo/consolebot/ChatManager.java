package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatManager implements Listener {

    private final JavaPlugin plugin;
    private final PunishmentManager punishmentManager;
    private final ColorParser colorParser;

    // Формат чата: [G] «Клан» [Префикс] Ник: сообщение
    private String chatFormat = "[G] «%clan%» %prefix% %player%: %message%";

    public ChatManager(JavaPlugin plugin, PunishmentManager punishmentManager) {
        this.plugin = plugin;
        this.punishmentManager = punishmentManager;
        this.colorParser = new ColorParser();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("✅ ChatManager загружен!");
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // ===== ПРОВЕРКА МУТА =====
        if (punishmentManager.isMuted(player.getName())) {
            player.sendMessage("§cВы не можете писать в чат! Вы замучены.");
            event.setCancelled(true);
            return;
        }

        // ===== ФОРМАТИРОВАНИЕ СООБЩЕНИЯ =====
        String formattedMessage = formatMessage(player, message);
        event.setFormat(formattedMessage);

        // ===== ОТПРАВКА В КОНСОЛЬ =====
        plugin.getLogger().info("[CHAT] " + formattedMessage);
    }

    private String formatMessage(Player player, String message) {
        // Получаем данные
        String clanName = getClanName(player);
        String prefix = getPrefix(player);
        String playerName = player.getDisplayName();

        // Собираем формат
        String formatted = chatFormat
                .replace("%clan%", clanName)
                .replace("%prefix%", prefix)
                .replace("%player%", playerName)
                .replace("%message%", colorParser.parseColors(message));

        // Парсим HEX и RGB
        return colorParser.parseColors(formatted);
    }

    private String getClanName(Player player) {
        // Пытаемся получить через PlaceholderAPI
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%simpleclans_clan_name%");
            }
        } catch (Exception e) {}
        return "—";
    }

    private String getPrefix(Player player) {
        // Пытаемся получить через Vault
        try {
            net.milkbowl.vault.permission.Permission permission = Bukkit.getServicesManager()
                    .getRegistration(net.milkbowl.vault.permission.Permission.class).getProvider();
            if (permission != null) {
                return permission.getPlayerPrefix(player);
            }
        } catch (Exception e) {}
        return "";
    }

    public void setChatFormat(String format) {
        this.chatFormat = format;
    }

    public String getChatFormat() {
        return chatFormat;
    }
}
