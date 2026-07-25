package com.grifmcpo.consolebot; 

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;

public class ChatListener implements Listener {

    private final PunishmentManager punishmentManager;
    private final List<String> allowedCommands = List.of(
        "msg", "tell", "r", "reply", "help", "pay", "balance", "bal"
    );

    public ChatListener(PunishmentManager punishmentManager) {
        this.punishmentManager = punishmentManager;
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage().toLowerCase();

        if (punishmentManager.isMuted(player.getName())) {
            for (String allowed : allowedCommands) {
                if (command.startsWith("/" + allowed) || command.startsWith("/minecraft:" + allowed)) {
                    return;
                }
            }
            event.setCancelled(true);
            player.sendMessage("§cВы не можете использовать команды во время мута!");
        }
    }
}
