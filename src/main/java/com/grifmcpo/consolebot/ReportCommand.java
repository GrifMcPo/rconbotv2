package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ReportCommand implements CommandExecutor {

    private final TelegramConsoleBot plugin;
    private final ReportManager reportManager;

    public ReportCommand(TelegramConsoleBot plugin, ReportManager reportManager) {
        this.plugin = plugin;
        this.reportManager = reportManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cЭта команда только для игроков!");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("telegramconsolebot.report")) {
            player.sendMessage("§cУ вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 2) {
            player.sendMessage("§cИспользование: /report <ник> <причина>");
            return true;
        }

        String targetName = args[0];
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        
        // Проверяем, существует ли игрок
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            player.sendMessage("§cИгрок " + targetName + " не найден!");
            return true;
        }

        if (player.equals(target)) {
            player.sendMessage("§cВы не можете пожаловаться на себя!");
            return true;
        }

        // Создаем репорт
        ReportManager.Report report = reportManager.createReport(player.getName(), targetName, reason);
        
        // Отправляем титул игроку
        player.sendTitle("§d§lБлагодарим за отправку жалобы.", "§7Жалоба #" + report.id + " принята в обработку.", 10, 40, 10);

        // Отправляем уведомление в Telegram (только owner)
        sendReportToTelegram(report);

        plugin.getLogger().info("[REPORT] " + player.getName() + " -> " + targetName + ": " + reason);
        return true;
    }

    private void sendReportToTelegram(ReportManager.Report report) {
        // Отправляем только owner (владельцу)
        long ownerId = plugin.getOwnerId();
        
        String message = "[БОТ] Ответ от сервера:\n" +
                "Новый report.\n" +
                "Ник отправителя: " + report.reporter + "\n" +
                "Ник на кого жалоба: " + report.target + "\n" +
                "Причина жалобы: " + report.reason + "\n" +
                "Время: " + report.timestamp + " MSK\n" +
                "Номер REPORT: #" + report.id;

        plugin.sendMessageAsBot(ownerId, message);
    }
}
