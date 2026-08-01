package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

public class PlayerCommands implements CommandExecutor {

    private final JavaPlugin plugin;
    private final PunishmentManager punishmentManager;
    private final PlayerManager playerManager;
    private final CommandLogger commandLogger;

    public PlayerCommands(JavaPlugin plugin, PunishmentManager punishmentManager, 
                          PlayerManager playerManager, CommandLogger commandLogger) {
        this.plugin = plugin;
        this.punishmentManager = punishmentManager;
        this.playerManager = playerManager;
        this.commandLogger = commandLogger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        switch (cmd) {
            case "ban":
                return handleBan(sender, args);
            case "mute":
                return handleMute(sender, args);
            case "kick":
                return handleKick(sender, args);
            case "bc":
                return handleBroadcast(sender, args);
            case "logs":
                return handleLogs(sender, args);
            case "hist":
                return handleHist(sender, args);
            case "shist":
                return handleShist(sender, args);
            case "dupeip":
                return handleDupeip(sender, args);
            default:
                return false;
        }
    }

    private boolean handleBan(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.ban")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cИспользование: /ban <ник> <время> <причина>");
            sender.sendMessage("§cПример: /ban pley1657 1d спам");
            return true;
        }

        String playerName = args[0];
        String duration = args[1];
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        String issuer = sender instanceof Player ? ((Player) sender).getName() : "Console";

        // banPlayer(playerName, issuer, reason, duration, hidden, broadcast)
        boolean success = punishmentManager.banPlayer(playerName, issuer, reason, duration, false, true);
        if (success) {
            sender.sendMessage("§aИгрок " + playerName + " забанен на " + duration + " по причине: " + reason);
            commandLogger.logCommand(sender.getName(), "ban " + playerName + " " + duration + " " + reason);
        } else {
            sender.sendMessage("§c" + playerName + " уже забанен!");
        }
        return true;
    }

    private boolean handleMute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.mute")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cИспользование: /mute <ник> <время> <причина>");
            sender.sendMessage("§cПример: /mute pley1657 30m флуд");
            return true;
        }

        String playerName = args[0];
        String duration = args[1];
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        String issuer = sender instanceof Player ? ((Player) sender).getName() : "Console";

        // mutePlayer(playerName, issuer, reason, duration, hidden, broadcast)
        boolean success = punishmentManager.mutePlayer(playerName, issuer, reason, duration, false, true);
        if (success) {
            sender.sendMessage("§aИгрок " + playerName + " замучен на " + duration + " по причине: " + reason);
            commandLogger.logCommand(sender.getName(), "mute " + playerName + " " + duration + " " + reason);
        } else {
            sender.sendMessage("§c" + playerName + " уже замучен!");
        }
        return true;
    }

    private boolean handleKick(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.kick")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /kick <ник> <причина>");
            return true;
        }

        String playerName = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String issuer = sender instanceof Player ? ((Player) sender).getName() : "Console";

        // kickPlayer(playerName, issuer, reason, hidden, broadcast)
        boolean success = punishmentManager.kickPlayer(playerName, issuer, reason, false, true);
        if (success) {
            sender.sendMessage("§aИгрок " + playerName + " кикнут по причине: " + reason);
            commandLogger.logCommand(sender.getName(), "kick " + playerName + " " + reason);
        } else {
            sender.sendMessage("§c" + playerName + " не найден!");
        }
        return true;
    }

    private boolean handleBroadcast(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.bc")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /bc <сообщение>");
            return true;
        }

        String message = String.join(" ", args);
        String senderName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        String chatMessage = "§e[Объявление] §f" + message + " §7(пишет: " + senderName + "§7)";
        Bukkit.broadcastMessage(chatMessage);
        commandLogger.logCommand(sender.getName(), "bc " + message);
        return true;
    }

    private boolean handleLogs(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.logs")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /logs <ник> [кол-во]");
            return true;
        }

        String playerName = args[0];
        int limit = 10;
        if (args.length >= 2) {
            try { limit = Integer.parseInt(args[1]); } catch (NumberFormatException e) {}
        }

        String response = commandLogger.getFormattedLogs(playerName, limit);
        sender.sendMessage(response.replace("[БОТ] Ответ сервера:\n", ""));
        commandLogger.logCommand(sender.getName(), "logs " + playerName);
        return true;
    }

    private boolean handleHist(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.hist")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /hist <ник> [кол-во]");
            return true;
        }

        String playerName = args[0];
        int limit = 10;
        if (args.length >= 2) {
            try { limit = Integer.parseInt(args[1]); } catch (NumberFormatException e) {}
        }

        String response = punishmentManager.getFormattedHistory(playerName, limit);
        sender.sendMessage(response.replace("[БОТ] Ответ сервера:\n", ""));
        commandLogger.logCommand(sender.getName(), "hist " + playerName);
        return true;
    }

    private boolean handleShist(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.shists")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /shist <ник> [кол-во]");
            return true;
        }

        String playerName = args[0];
        int limit = 10;
        if (args.length >= 2) {
            try { limit = Integer.parseInt(args[1]); } catch (NumberFormatException e) {}
        }

        String response = punishmentManager.getFormattedShist(playerName, limit);
        sender.sendMessage(response.replace("[БОТ] Ответ сервера:\n", ""));
        commandLogger.logCommand(sender.getName(), "shist " + playerName);
        return true;
    }

    private boolean handleDupeip(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.dupeip")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /dupeip <ник>");
            return true;
        }

        String playerName = args[0];
        String targetIp = playerManager.getPlayerIp(playerName);

        if (targetIp == null || targetIp.equals("—") || targetIp.equals("0.0.0.0")) {
            sender.sendMessage("§cНе удалось определить IP игрока " + playerName);
            return true;
        }

        java.util.List<String> playersWithSameIp = playerManager.getPlayersByIp(targetIp);
        playersWithSameIp.remove(playerName);

        StringBuilder response = new StringBuilder();
        response.append("§6Сканируем по нику: ").append(playerName).append("\n");
        response.append("§7");

        if (playersWithSameIp.isEmpty()) {
            response.append("Нет других аккаунтов с этим IP");
        } else {
            response.append(String.join(", ", playersWithSameIp));
        }

        sender.sendMessage(response.toString());
        commandLogger.logCommand(sender.getName(), "dupeip " + playerName);
        return true;
    }
}
