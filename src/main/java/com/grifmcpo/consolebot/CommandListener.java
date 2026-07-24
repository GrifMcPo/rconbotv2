package com.grifmcpo.consolebot;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class CommandListener implements Listener {

    private final CommandLogger commandLogger;
    private final PunishmentManager punishmentManager;

    public CommandListener(CommandLogger commandLogger, PunishmentManager punishmentManager) {
        this.commandLogger = commandLogger;
        this.punishmentManager = punishmentManager;
    }

    // ===== БЛОКИРОВКА ЧАТА ПРИ МУТЕ =====
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        
        if (!punishmentManager.canPlayerChat(player)) {
            event.setCancelled(true);
        }
    }

    // ===== БЛОКИРОВКА КОМАНД ПРИ МУТЕ =====
    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage().substring(1).split(" ")[0];
        
        if (!punishmentManager.canUseCommand(player, command)) {
            event.setCancelled(true);
        }
    }
}
