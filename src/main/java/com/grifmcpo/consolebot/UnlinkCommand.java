package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class UnlinkCommand implements CommandExecutor {

    private final TelegramConsoleBot plugin;
    private final TelegramBotHandler botHandler;

    public UnlinkCommand(TelegramConsoleBot plugin, TelegramBotHandler botHandler) {
        this.plugin = plugin;
        this.botHandler = botHandler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.unlink")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /untgm <ник>");
            return true;
        }

        String playerName = args[0];
        String adminName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        if (!plugin.getAuthManager().isLinked(playerName)) {
            sender.sendMessage("§cАккаунт " + playerName + " не привязан к Telegram!");
            return true;
        }

        boolean success = botHandler.unlinkByAdmin(playerName, adminName);
        if (success) {
            sender.sendMessage("§aАккаунт " + playerName + " успешно отвязан от Telegram!");
            plugin.getLogger().info("Администратор " + adminName + " отвязал аккаунт " + playerName);
        } else {
            sender.sendMessage("§cНе удалось отвязать аккаунт " + playerName);
        }

        return true;
    }
}
