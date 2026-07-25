package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
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
        event.setFormat(formattedMessage);

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
                .replace("%message%", message);

        // Парсим цвета
        if (allowColors) {
            formatted = colorParser.parseColors(formatted);
        } else {
            formatted = formatted.replace('&', '§');
        }

        return formatted;
    }

    private String getClanName(Player player) {
        // Пробуем получить клан через SimpleClans напрямую (если плагин есть)
        try {
            if (Bukkit.getPluginManager().getPlugin("simpleclans-PLUS") != null) {
                return "Клан"; // Заглушка, пока нет API
            }
        } catch (Exception e) {}
        return "—";
    }

    private String getPrefix(Player player) {
        // Пробуем через Vault
        try {
            RegisteredServiceProvider<net.milkbowl.vault.permission.Permission> rsp =
                    Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.permission.Permission.class);
            if (rsp != null) {
                net.milkbowl.vault.permission.Permission permission = rsp.getProvider();
                if (permission != null) {
                    // В Vault 1.7+ метод getPlayerPrefix принимает Player
                    return permission.getPlayerPrefix(player);
                }
            }
        } catch (Throwable e) {
            // Если не сработало, пробуем через мир и имя
            try {
                RegisteredServiceProvider<net.milkbowl.vault.permission.Permission> rsp2 =
                        Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.permission.Permission.class);
                if (rsp2 != null) {
                    net.milkbowl.vault.permission.Permission permission = rsp2.getProvider();
                    if (permission != null) {
                        return permission.getPlayerPrefix(player.getWorld().getName(), player.getName());
                    }
                }
            } catch (Throwable e2) {}
        }
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
