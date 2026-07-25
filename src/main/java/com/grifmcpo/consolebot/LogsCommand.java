package com.grifmcpo.consolebot;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;

public class LogsCommand {

    private final TelegramConsoleBot plugin;
    private static final String SEPARATOR = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

    public LogsCommand(TelegramConsoleBot plugin) {
        this.plugin = plugin;
    }

    public SendMessage handleLogs(long chatId, String[] args) {
        if (args.length < 2) {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText("[БОТ] Использование: !logs <ник> [кол-во]");
            return msg;
        }

        String playerName = args[1];
        int limit = 10;

        if (args.length >= 3) {
            try {
                limit = Integer.parseInt(args[2]);
                if (limit < 1) limit = 1;
                if (limit > 50) limit = 50;
            } catch (NumberFormatException e) {}
        }

        List<CommandLogger.LogEntry> logs = plugin.getCommandLogger().getLogs(playerName, 30);
        if (logs.isEmpty()) {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText("[БОТ] Нет логов для " + playerName);
            return msg;
        }

        List<CommandLogger.LogEntry> recent = logs.stream()
            .limit(limit)
            .toList();

        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));

        StringBuilder response = new StringBuilder();
        response.append("[БОТ] Логи игрока ").append(playerName)
                .append(" (последние ").append(recent.size()).append("):\n");
        response.append(SEPARATOR).append("\n");

        for (CommandLogger.LogEntry entry : recent) {
            String time = entry.timestamp;
            try {
                SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                inputFormat.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));
                java.util.Date date = inputFormat.parse(entry.timestamp);
                time = sdf.format(date);
            } catch (Exception e) {}
            response.append(time).append(" | ").append(entry.command).append("\n");
        }

        response.append(SEPARATOR).append("\n");
        response.append("Всего записей: ").append(logs.size());

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(response.toString());
        return msg;
    }
}
