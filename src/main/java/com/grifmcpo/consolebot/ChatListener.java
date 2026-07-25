package com.grifmcpo.consolebot;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class ChatListener implements Listener {

    private final PunishmentManager punishmentManager;

    public ChatListener(PunishmentManager punishmentManager) {
        this.punishmentManager = punishmentManager;
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage().toLowerCase();

        // Разрешённые команды во время мута
        String[] allowed = {"/msg", "/tell", "/r", "/reply", "/help", "/pay", "/balance"};

        if (punishmentManager.isMuted(player.getName())) {
            for (String cmd : allowed) {
                if (command.startsWith(cmd)) {
                    return; // Разрешено
                }
            }
            event.setCancelled(true);
            player.sendMessage("§cВы не можете использовать команды во время мута!");
        }
    }
}
