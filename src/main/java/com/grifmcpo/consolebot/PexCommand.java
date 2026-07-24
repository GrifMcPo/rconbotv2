package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;

public class PexCommand implements CommandExecutor {

    private final TelegramConsoleBot plugin;

    public PexCommand(TelegramConsoleBot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2 || !args[0].equalsIgnoreCase("user")) {
            sender.sendMessage("§cИспользование: /pex user <ник>");
            return true;
        }

        String playerName = args[1];

        Player target = Bukkit.getPlayer(playerName);
        boolean isOnline = target != null && target.isOnline();

        String group = "default";
        boolean isOp = false;
        boolean isWhitelisted = false;
        String uuid = "—";
        String displayName = playerName;

        try {
            if (isOnline) {
                isOp = target.isOp();
                uuid = target.getUniqueId().toString();
                displayName = target.getDisplayName();
            } else {
                File opsFile = new File("ops.json");
                if (opsFile.exists()) {
                    try {
                        String content = new String(java.nio.file.Files.readAllBytes(opsFile.toPath()));
                        isOp = content.contains("\"name\":\"" + playerName + "\"");
                    } catch (Exception e) {}
                }
            }

            File whitelistFile = new File("whitelist.json");
            if (whitelistFile.exists()) {
                try {
                    String content = new String(java.nio.file.Files.readAllBytes(whitelistFile.toPath()));
                    isWhitelisted = content.contains("\"name\":\"" + playerName + "\"");
                } catch (Exception e) {}
            }

            try {
                net.milkbowl.vault.permission.Permission permission = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.permission.Permission.class).getProvider();
                if (permission != null) {
                    if (isOnline) {
                        group = permission.getPrimaryGroup(target);
                    } else {
                        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
                        if (offlinePlayer != null) {
                            group = permission.getPrimaryGroup(offlinePlayer);
                        }
                    }
                }
            } catch (Exception e) {
                group = "неизвестно (Vault недоступен)";
            }

        } catch (Exception e) {
            sender.sendMessage("§cОшибка получения данных: " + e.getMessage());
            return true;
        }

        String response = "§6📋 Сканируем по нику §f" + playerName + "\n" +
                "\n" +
                "§6📌 Группа: §f" + group + "\n" +
                "§6🔑 OP: §f" + (isOp ? "§aДа" : "§cНет") + "\n" +
                "§6📋 Белый список: §f" + (isWhitelisted ? "§aДа" : "§cНет") + "\n" +
                "§6👤 Ник игрока: §f" + displayName;

        sender.sendMessage(response);
        return true;
    }
}
