package com.grifmcpo.consolebot;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class PlayerCommands implements CommandExecutor {

    private final TelegramConsoleBot plugin;
    private final PunishmentManager punishmentManager;
    private final PlayerManager playerManager;
    private final CommandLogger commandLogger;

    public PlayerCommands(TelegramConsoleBot plugin, PunishmentManager punishmentManager,
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
            case "banuuid":
                return handleBanUuid(sender, args);
            case "mute":
                return handleMute(sender, args);
            case "kick":
                return handleKick(sender, args);
            case "warn":
                return handleWarn(sender, args);
            case "unwarn":
                return handleUnwarn(sender, args);
            case "bc":
                return handleBroadcast(sender, args);
            case "logs":
                return handleLogs(sender, args);
            case "dupeip":
                return handleDupeip(sender, args);
            case "seen":
                return handleSeen(sender, args);
            case "pex":
                return handlePex(sender, args);
            default:
                return false;
        }
    }

    // =========================================================
    // ==== BAN =====
    // =========================================================
    private boolean handleBan(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.ban")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /ban <ник> [время] <причина> [-s]");
            sender.sendMessage("§7Пример: /ban Stive Читер - бан навсегда");
            sender.sendMessage("§7Пример: /ban Stive 1d Читер - бан на 1 день");
            return true;
        }

        boolean hidden = false;
        String lastArg = args[args.length - 1];
        if (lastArg.equalsIgnoreCase("-s")) {
            hidden = true;
            args = Arrays.copyOf(args, args.length - 1);
        }

        String playerName = args[0];
        String duration = "навсегда";
        String reason;
        int start = 1;

        if (args.length > 1 && punishmentManager.isValidTime(args[1]) && !args[1].equalsIgnoreCase("-s")) {
            duration = args[1];
            start = 2;
        }

        if (args.length > start) {
            reason = String.join(" ", Arrays.copyOfRange(args, start, args.length));
        } else {
            reason = "Без причины";
        }

        String issuer = sender instanceof Player ? ((Player) sender).getName() : "Console";

        boolean success = punishmentManager.banPlayer(playerName, issuer, reason, duration, hidden, !hidden);
        if (success) {
            sender.sendMessage("§aИгрок " + playerName + " забанен " + (duration.equals("навсегда") ? "навсегда" : "на " + duration) + " по причине: " + reason);
            commandLogger.logCommand(sender.getName(), "ban " + playerName + " " + duration + " " + reason + (hidden ? " -s" : ""));
        } else {
            sender.sendMessage("§c" + playerName + " уже забанен!");
        }
        return true;
    }

    // =========================================================
    // ==== BANUUID =====
    // =========================================================
    private boolean handleBanUuid(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.ban")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /banuuid <uuid> [время] <причина> [-s]");
            sender.sendMessage("§7Пример: /banuuid 123e4567-e89b-12d3-a456-426614174000 Читер");
            return true;
        }

        boolean hidden = false;
        String lastArg = args[args.length - 1];
        if (lastArg.equalsIgnoreCase("-s")) {
            hidden = true;
            args = Arrays.copyOf(args, args.length - 1);
        }

        String uuidStr = args[0];
        String duration = "навсегда";
        String reason;
        int start = 1;

        try {
            UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cНеверный формат UUID!");
            return true;
        }

        if (args.length > 1 && punishmentManager.isValidTime(args[1]) && !args[1].equalsIgnoreCase("-s")) {
            duration = args[1];
            start = 2;
        }

        if (args.length > start) {
            reason = String.join(" ", Arrays.copyOfRange(args, start, args.length));
        } else {
            reason = "Без причины";
        }

        String issuer = sender instanceof Player ? ((Player) sender).getName() : "Console";

        boolean success = punishmentManager.banUuid(uuidStr, issuer, reason, duration, hidden, !hidden);
        if (success) {
            sender.sendMessage("§aИгрок с UUID " + uuidStr + " забанен " + (duration.equals("навсегда") ? "навсегда" : "на " + duration) + " по причине: " + reason);
            commandLogger.logCommand(sender.getName(), "banuuid " + uuidStr + " " + duration + " " + reason + (hidden ? " -s" : ""));
        } else {
            sender.sendMessage("§cИгрок с UUID " + uuidStr + " уже забанен или не найден!");
        }
        return true;
    }

    // =========================================================
    // ==== MUTE =====
    // =========================================================
    private boolean handleMute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.mute")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /mute <ник> [время] <причина> [-s]");
            sender.sendMessage("§7Пример: /mute Stive Читер - мут навсегда");
            return true;
        }

        boolean hidden = false;
        String lastArg = args[args.length - 1];
        if (lastArg.equalsIgnoreCase("-s")) {
            hidden = true;
            args = Arrays.copyOf(args, args.length - 1);
        }

        String playerName = args[0];
        String duration = "навсегда";
        String reason;
        int start = 1;

        if (args.length > 1 && punishmentManager.isValidTime(args[1]) && !args[1].equalsIgnoreCase("-s")) {
            duration = args[1];
            start = 2;
        }

        if (args.length > start) {
            reason = String.join(" ", Arrays.copyOfRange(args, start, args.length));
        } else {
            reason = "Без причины";
        }

        String issuer = sender instanceof Player ? ((Player) sender).getName() : "Console";

        boolean success = punishmentManager.mutePlayer(playerName, issuer, reason, duration, hidden, !hidden);
        if (success) {
            sender.sendMessage("§aИгрок " + playerName + " замучен " + (duration.equals("навсегда") ? "навсегда" : "на " + duration) + " по причине: " + reason);
            commandLogger.logCommand(sender.getName(), "mute " + playerName + " " + duration + " " + reason + (hidden ? " -s" : ""));
        } else {
            sender.sendMessage("§c" + playerName + " уже замучен!");
        }
        return true;
    }

    // =========================================================
    // ==== KICK =====
    // =========================================================
    private boolean handleKick(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.kick")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /kick <ник> <причина> [-s]");
            return true;
        }

        boolean hidden = false;
        String lastArg = args[args.length - 1];
        if (lastArg.equalsIgnoreCase("-s")) {
            hidden = true;
            args = Arrays.copyOf(args, args.length - 1);
        }

        String playerName = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String issuer = sender instanceof Player ? ((Player) sender).getName() : "Console";

        boolean success = punishmentManager.kickPlayer(playerName, issuer, reason, hidden, !hidden);
        if (success) {
            sender.sendMessage("§aИгрок " + playerName + " кикнут по причине: " + reason);
            commandLogger.logCommand(sender.getName(), "kick " + playerName + " " + reason + (hidden ? " -s" : ""));
        } else {
            sender.sendMessage("§c" + playerName + " не найден!");
        }
        return true;
    }

    // =========================================================
    // ==== WARN =====
    // =========================================================
    private boolean handleWarn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.warn")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /warn <ник> <причина> [-s]");
            return true;
        }

        boolean hidden = false;
        String lastArg = args[args.length - 1];
        if (lastArg.equalsIgnoreCase("-s")) {
            hidden = true;
            args = Arrays.copyOf(args, args.length - 1);
        }

        String playerName = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String issuer = sender instanceof Player ? ((Player) sender).getName() : "Console";

        boolean success = punishmentManager.warnPlayer(playerName, issuer, reason, hidden, !hidden);
        if (success) {
            sender.sendMessage("§aИгрок " + playerName + " получил предупреждение по причине: " + reason);
            commandLogger.logCommand(sender.getName(), "warn " + playerName + " " + reason + (hidden ? " -s" : ""));
        } else {
            sender.sendMessage("§cНе удалось выдать предупреждение!");
        }
        return true;
    }

    // =========================================================
    // ==== UNWARN =====
    // =========================================================
    private boolean handleUnwarn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.warn")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /unwarn <ник> <причина>");
            return true;
        }

        String playerName = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String issuer = sender instanceof Player ? ((Player) sender).getName() : "Console";

        boolean success = punishmentManager.unwarnPlayer(playerName, issuer, reason, true);
        if (success) {
            sender.sendMessage("§aУ игрока " + playerName + " снято предупреждение по причине: " + reason);
            commandLogger.logCommand(sender.getName(), "unwarn " + playerName + " " + reason);
        } else {
            sender.sendMessage("§cУ игрока " + playerName + " нет предупреждений!");
        }
        return true;
    }

    // =========================================================
    // ==== BROADCAST =====
    // =========================================================
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

    // =========================================================
    // ==== LOGS =====
    // =========================================================
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

    // =========================================================
    // ==== DUPEIP =====
    // =========================================================
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

    // =========================================================
    // ==== SEEN =====
    // =========================================================
    private boolean handleSeen(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.seen")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /seen <ник>");
            return true;
        }

        String playerName = args[0];
        Player target = Bukkit.getPlayer(playerName);
        boolean isOnline = target != null && target.isOnline();

        // Проверка прав по группам LuckPerms
        if (sender instanceof Player) {
            Player senderPlayer = (Player) sender;
            try {
                LuckPerms luckPerms = Bukkit.getServicesManager().getRegistration(LuckPerms.class).getProvider();
                if (luckPerms != null) {
                    User senderUser = luckPerms.getUserManager().getUser(senderPlayer.getUniqueId());
                    User targetUser = luckPerms.getUserManager().getUser(playerName);
                    
                    if (senderUser != null && targetUser != null) {
                        String senderGroup = senderUser.getPrimaryGroup();
                        String targetGroup = targetUser.getPrimaryGroup();
                        
                        // Если группа отправителя ниже группы цели
                        if (senderGroup != null && targetGroup != null) {
                            int senderWeight = getGroupWeight(luckPerms, senderGroup);
                            int targetWeight = getGroupWeight(luckPerms, targetGroup);
                            
                            if (senderWeight < targetWeight) {
                                sender.sendMessage("§cВы не можете узнать информацию об этом игроке!");
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Если ошибка с LuckPerms - пропускаем проверку
            }
        }

        String response = punishmentManager.getSeenInfo(playerName, isOnline);
        sender.sendMessage(response);
        commandLogger.logCommand(sender.getName(), "seen " + playerName);
        return true;
    }

    private int getGroupWeight(LuckPerms luckPerms, String groupName) {
        try {
            Group group = luckPerms.getGroupManager().getGroup(groupName);
            if (group != null) {
                return group.getCachedData().getMetaData().getMetaValue("weight", 0);
            }
        } catch (Exception e) {}
        return 0;
    }

    // =========================================================
    // ==== PEX =====
    // =========================================================
    private boolean handlePex(CommandSender sender, String[] args) {
        if (!sender.hasPermission("telegramconsolebot.pex")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /pex user <ник> | /pex group");
            return true;
        }

        String subCmd = args[0].toLowerCase();

        if (subCmd.equals("user") && args.length >= 2) {
            return handlePexUser(sender, args);
        } else if (subCmd.equals("group")) {
            return handlePexGroup(sender);
        } else {
            sender.sendMessage("§cИспользование: /pex user <ник> | /pex group");
            return true;
        }
    }

    private boolean handlePexUser(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /pex user <ник>");
            return true;
        }

        String playerName = args[1];
        Player target = Bukkit.getPlayer(playerName);
        boolean isOnline = target != null && target.isOnline();

        String group = "default";
        String uuid = "—";

        try {
            if (isOnline) {
                isOnline = target.isOp();
                uuid = target.getUniqueId().toString();
                try {
                    net.milkbowl.vault.permission.Permission permission = Bukkit.getServicesManager()
                            .getRegistration(net.milkbowl.vault.permission.Permission.class).getProvider();
                    if (permission != null) {
                        group = permission.getPrimaryGroup(target);
                    }
                } catch (Exception e) {
                    group = "неизвестно";
                }
            } else {
                group = "офлайн";
                uuid = "—";
                // Пробуем через LuckPerms
                try {
                    LuckPerms luckPerms = Bukkit.getServicesManager().getRegistration(LuckPerms.class).getProvider();
                    if (luckPerms != null) {
                        User user = luckPerms.getUserManager().loadUser(playerName).join();
                        if (user != null) {
                            uuid = user.getUniqueId().toString();
                            String primaryGroup = user.getPrimaryGroup();
                            if (primaryGroup != null) {
                                group = primaryGroup;
                            }
                        }
                    }
                } catch (Exception e) {}
            }

        } catch (Exception e) {
            sender.sendMessage("§cОшибка: " + e.getMessage());
            return true;
        }

        String response = "§6📋 Сканируем по нику §f" + playerName + "\n" +
                "\n" +
                "§6🆔 UUID: §f" + uuid + "\n" +
                "§6📌 Группа: §f" + group;

        sender.sendMessage(response);
        return true;
    }

    private boolean handlePexGroup(CommandSender sender) {
        try {
            LuckPerms luckPerms = Bukkit.getServicesManager().getRegistration(LuckPerms.class).getProvider();
            if (luckPerms == null) {
                sender.sendMessage("§cLuckPerms не найден!");
                return true;
            }

            List<Group> groups = new ArrayList<>(luckPerms.getGroupManager().getLoadedGroups());
            
            // Сортируем по приоритету (весу)
            groups.sort((a, b) -> {
                int weightA = a.getCachedData().getMetaData().getMetaValue("weight", 0);
                int weightB = b.getCachedData().getMetaData().getMetaValue("weight", 0);
                return Integer.compare(weightB, weightA);
            });

            StringBuilder response = new StringBuilder();
            response.append("§6Доступные группы:\n");
            response.append("§7━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            
            for (Group group : groups) {
                int count = luckPerms.getUserManager().getUsersInGroup(group.getName()).size();
                response.append("§f - ").append(group.getName()).append(" §7(").append(count).append(" игроков)\n");
            }
            
            response.append("§7━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            response.append("§7Всего групп: §f").append(groups.size());

            sender.sendMessage(response.toString());
            
        } catch (Exception e) {
            sender.sendMessage("§cОшибка получения групп: " + e.getMessage());
        }
        return true;
    }
}
