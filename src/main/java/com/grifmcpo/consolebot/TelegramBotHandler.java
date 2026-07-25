package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class TelegramBotHandler extends TelegramLongPollingBot {

    private final String botToken;
    private final TelegramConsoleBot plugin;
    private final PlayerManager playerManager;
    private final CommandLogger commandLogger;
    private final LogsCommand logsCommand;
    private final CommandExecutor commandExecutor;
    private final PunishmentManager punishmentManager;
    private final BotBanManager botBanManager;
    private final GroupManager groupManager;

    private final List<Long> hiddenViewers = new ArrayList<>();
    private final Set<Long> knownUsers = new HashSet<>();

    public TelegramBotHandler(String token, TelegramConsoleBot plugin, PlayerManager playerManager,
                              CommandLogger commandLogger, LogsCommand logsCommand,
                              CommandExecutor commandExecutor, PunishmentManager punishmentManager,
                              BotBanManager botBanManager, GroupManager groupManager) {
        this.botToken = token;
        this.plugin = plugin;
        this.playerManager = playerManager;
        this.commandLogger = commandLogger;
        this.logsCommand = logsCommand;
        this.commandExecutor = commandExecutor;
        this.punishmentManager = punishmentManager;
        this.botBanManager = botBanManager;
        this.groupManager = groupManager;
        loadHiddenViewers();
        loadKnownUsers();
    }

    private void loadHiddenViewers() {
        hiddenViewers.clear();
        hiddenViewers.add(plugin.getOwnerId());
        for (String id : plugin.getAdmins().keySet()) {
            try { hiddenViewers.add(Long.parseLong(id)); } catch (NumberFormatException e) {}
        }
        plugin.getLogger().info("Загружено зрителей скрытых наказаний: " + hiddenViewers.size());
    }

    private void loadKnownUsers() {
        File knownFile = new File(plugin.getDataFolder(), "known_users.txt");
        if (knownFile.exists()) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(knownFile.toPath());
                for (String line : lines) {
                    try { knownUsers.add(Long.parseLong(line.trim())); } catch (NumberFormatException e) {}
                }
            } catch (Exception e) {}
        }
        plugin.getLogger().info("Загружено известных пользователей: " + knownUsers.size());
    }

    private void saveKnownUser(long userId) {
        if (!knownUsers.contains(userId)) {
            knownUsers.add(userId);
            try {
                File knownFile = new File(plugin.getDataFolder(), "known_users.txt");
                java.nio.file.Files.write(knownFile.toPath(), 
                    knownUsers.stream().map(String::valueOf).collect(Collectors.toList()));
            } catch (Exception e) {}
        }
    }

    public boolean canSeeHidden(long userId) {
        return hiddenViewers.contains(userId);
    }

    private void notifyStaffOnly(String message) {
        for (long id : hiddenViewers) {
            try { sendMessage(id, "[STAFF] " + message); } catch (Exception e) {}
        }
    }

    @Override
    public String getBotUsername() { return "TelegramConsoleBot"; }

    @Override
    public String getBotToken() { return botToken; }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            String data = update.getCallbackQuery().getData();
            String chatIdStr = update.getCallbackQuery().getMessage().getChatId().toString();
            int messageId = update.getCallbackQuery().getMessage().getMessageId();
            long chatId = Long.parseLong(chatIdStr);
            deleteMessage(chatIdStr, messageId);
            return;
        }

        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        String messageText = update.getMessage().getText().trim();
        long userId = update.getMessage().getFrom().getId();
        long chatId = update.getMessage().getChatId();

        saveKnownUser(userId);
        plugin.getLogger().info("Получено: " + messageText + " от " + userId);

        if (botBanManager.isBanned(userId)) {
            sendMessage(chatId, botBanManager.getBanMessage(userId));
            return;
        }

        if (messageText.equalsIgnoreCase("!id")) {
            sendMessage(chatId, "[БОТ] Ваш ID: " + userId);
            return;
        }

        String userGroup = groupManager.getUserGroup(userId);
        if (userGroup == null && !plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
            sendMessage(chatId, "[БОТ] У Вас нет доступа к боту!");
            return;
        }

        if (messageText.startsWith("!rcon ")) {
            handleRconCommand(chatId, messageText.substring(6).trim(), userId);
            return;
        }

        if (messageText.startsWith("!ban ") || messageText.startsWith("!unban ") ||
            messageText.startsWith("!list ") || messageText.startsWith("!logs ")) {
            handleShortCommand(chatId, messageText, userId);
            return;
        }

        if (messageText.startsWith("!")) {
            sendMessage(chatId, "[БОТ] Неизвестная команда. Доступные команды: !id, !rcon ...");
        }
    }

    private void handleShortCommand(long chatId, String message, long userId) {
        String[] parts = message.split(" ");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "!ban":
                if (parts.length < 4) {
                    sendMessage(chatId, "[БОТ] Использование: !ban <id> <время> <причина>");
                    return;
                }
                handleBotBan(chatId, parts, userId);
                break;

            case "!unban":
                if (parts.length < 3) {
                    sendMessage(chatId, "[БОТ] Использование: !unban <id> <причина>");
                    return;
                }
                handleBotUnban(chatId, parts, userId);
                break;

            case "!list":
                if (parts.length < 2) {
                    sendMessage(chatId, "[БОТ] Использование: !list id | !list server");
                    return;
                }
                handleList(chatId, parts[1], userId);
                break;

            case "!logs":
                if (parts.length < 2) {
                    sendMessage(chatId, "[БОТ] Использование: !logs <ник> [кол-во]");
                    return;
                }
                handleLogs(chatId, parts, userId);
                break;

            default:
                sendMessage(chatId, "[БОТ] Неизвестная команда.");
        }
    }

    private void handleBotBan(long chatId, String[] parts, long userId) {
        long targetId;
        try {
            targetId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            sendMessage(chatId, "[БОТ] Неверный ID!");
            return;
        }

        if (targetId == plugin.getOwnerId()) {
            sendMessage(chatId, "[БОТ] Нельзя забанить владельца!");
            return;
        }

        if (plugin.isAdmin(targetId)) {
            sendMessage(chatId, "[БОТ] Нельзя забанить администратора!");
            return;
        }

        String duration = parts[2];
        String reason = String.join(" ", Arrays.copyOfRange(parts, 3, parts.length));
        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON@" + userId;

        boolean success = botBanManager.banUser(targetId, reason, duration, issuer);
        if (success) {
            String msg = "[БОТ] Игрок " + targetId + " забанен в боте на " + duration + " по причине: " + reason;
            sendMessage(chatId, msg);
            botBanManager.notifyBannedUser(targetId, reason, duration, issuer);
        } else {
            sendMessage(chatId, "[БОТ] ID " + targetId + " уже забанен в боте!");
        }
    }

    private void handleBotUnban(long chatId, String[] parts, long userId) {
        long targetId;
        try {
            targetId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            sendMessage(chatId, "[БОТ] Неверный ID!");
            return;
        }

        String reason = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON@" + userId;

        boolean success = botBanManager.unbanUser(targetId, reason, issuer);
        if (success) {
            sendMessage(chatId, "[БОТ] Игрок " + targetId + " разбанен в боте по причине: " + reason);
            botBanManager.notifyUnbannedUser(targetId, reason, issuer);
        } else {
            sendMessage(chatId, "[БОТ] ID " + targetId + " не забанен в боте!");
        }
    }

    private void handleList(long chatId, String type, long userId) {
        if (type.equalsIgnoreCase("id")) {
            if (knownUsers.isEmpty()) {
                sendMessage(chatId, "[БОТ] Список ID пуст.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[БОТ] Список ID (").append(knownUsers.size()).append("):\n");
            int count = 0;
            for (long id : knownUsers) {
                sb.append(id);
                if (++count < knownUsers.size()) sb.append("\n");
            }
            sendMessage(chatId, sb.toString());
        } else if (type.equalsIgnoreCase("server")) {
            int online = Bukkit.getOnlinePlayers().size();
            int max = Bukkit.getMaxPlayers();
            sendMessage(chatId, "[БОТ] Онлайн: " + online + "/" + max);
        } else {
            sendMessage(chatId, "[БОТ] Использование: !list id | !list server");
        }
    }

    private void handleLogs(long chatId, String[] parts, long userId) {
        String playerName = parts[1];
        int limit = 10;
        if (parts.length >= 3) {
            try { limit = Integer.parseInt(parts[2]); } catch (NumberFormatException e) {}
            if (limit < 1) limit = 1;
            if (limit > 50) limit = 50;
        }

        List<CommandLogger.LogEntry> logs = commandLogger.getLogs(playerName, 30);
        if (logs.isEmpty()) {
            sendMessage(chatId, "[БОТ] Нет логов для " + playerName);
            return;
        }

        List<CommandLogger.LogEntry> recent = logs.stream()
            .limit(limit)
            .collect(Collectors.toList());

        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));

        StringBuilder response = new StringBuilder();
        response.append("[БОТ] Логи игрока ").append(playerName).append(" (последние ").append(recent.size()).append("):\n");
        response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        for (CommandLogger.LogEntry entry : recent) {
            String time = entry.timestamp;
            try {
                SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                inputFormat.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));
                Date date = inputFormat.parse(entry.timestamp);
                time = sdf.format(date);
            } catch (Exception e) {}
            response.append(time).append(" | ").append(entry.command).append("\n");
        }

        response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        response.append("Всего записей: ").append(logs.size());

        sendMessage(chatId, response.toString());
    }

    private void handleRconCommand(long chatId, String command, long userId) {
        String[] parts = command.split(" ");
        if (parts.length == 0) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global <команда>");
            return;
        }

        String server = parts[0].toLowerCase();
        if (!server.equals("global")) {
            sendMessage(chatId, "[БОТ] Доступен только сервер global");
            return;
        }

        String cmd = command.substring(7).trim();
        if (cmd.isEmpty()) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global <команда>");
            return;
        }

        String cmdName = cmd.split(" ")[0];

        if (!groupManager.hasPermission(userId, "!rcon global " + cmdName) && 
            !plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
            sendMessage(chatId, "[БОТ] У вас нет доступа к данной команде!");
            plugin.getLogger().info("Доступ запрещён: " + userId + " -> " + cmdName);
            return;
        }

        if (cmd.startsWith("bc ") || cmd.startsWith("bcast ")) {
            handleBroadcast(chatId, cmd, userId);
            return;
        }

        if (cmd.startsWith("ban ") || cmd.startsWith("mute ") || 
            cmd.startsWith("kick ") || cmd.startsWith("unban ") || cmd.startsWith("unmute ")) {
            handlePunishment(chatId, cmd, userId);
            return;
        }

        if (cmd.startsWith("checkban ")) {
            handleCheckBan(chatId, cmd);
            return;
        }

        if (cmd.startsWith("banlist")) {
            handleBanList(chatId, cmd);
            return;
        }

        if (cmd.startsWith("shist ") || cmd.startsWith("hist ")) {
            handleShist(chatId, cmd);
            return;
        }

        executeServerCommand(chatId, cmd, userId);
    }

    private void handleBroadcast(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global bc <сообщение>");
            return;
        }

        String message = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        String sender = plugin.getCustomSender(userId);
        if (sender == null) sender = "RCON@" + userId;

        String chatMessage = "§e[Объявление] §f" + message + " §7(пишет: " + sender + "§7)";
        Bukkit.broadcastMessage(chatMessage);

        sendMessage(chatId, "[БОТ] Ответ от сервера:\n[Объявление] " + message + " (пишет: " + sender + ")");
    }

    private void handlePunishment(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        String action = parts[0];
        String playerName = parts[1];

        boolean hidden = false;
        String lastArg = parts[parts.length - 1];
        if (lastArg.equals("-s") || lastArg.equals("-S")) {
            hidden = true;
            if (!canSeeHidden(userId)) {
                sendMessage(chatId, "[БОТ] У вас нет прав на скрытые наказания!");
                return;
            }
            cmd = String.join(" ", Arrays.copyOf(parts, parts.length - 1));
            parts = cmd.split(" ");
        }

        String duration = "навсегда";
        String reason = "";
        int start = 2;
        int end = parts.length;

        if (end > start + 1 && punishmentManager.isValidTime(parts[2])) {
            duration = parts[2];
            start = 3;
        }

        if (end > start) {
            reason = String.join(" ", Arrays.copyOfRange(parts, start, end));
        } else {
            reason = "Без причины";
        }

        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON@" + userId;

        boolean success = false;
        String result = "";

        switch (action) {
            case "ban":
                success = punishmentManager.banPlayer(playerName, issuer, reason, duration, hidden);
                if (success) {
                    result = "[БОТ] " + issuer + " забанил " + playerName + " на " + duration + " по причине: " + reason;
                    if (hidden) {
                        result += " (СКРЫТОЕ)";
                        notifyStaffOnly("СКРЫТЫЙ БАН\nИгрок: " + playerName + "\nПричина: " + reason + "\nСрок: " + duration + "\nВыдал: " + issuer);
                    }
                } else {
                    result = "[БОТ] " + playerName + " уже забанен!";
                }
                break;

            case "mute":
                success = punishmentManager.mutePlayer(playerName, issuer, reason, duration, hidden);
                if (success) {
                    result = "[БОТ] " + issuer + " замутил " + playerName + " на " + duration + " по причине: " + reason;
                    if (hidden) {
                        result += " (СКРЫТОЕ)";
                        notifyStaffOnly("СКРЫТЫЙ МУТ\nИгрок: " + playerName + "\nПричина: " + reason + "\nСрок: " + duration + "\nВыдал: " + issuer);
                    }
                } else {
                    result = "[БОТ] " + playerName + " уже замучен!";
                }
                break;

            case "kick":
                success = punishmentManager.kickPlayer(playerName, issuer, reason, hidden);
                if (success) {
                    result = "[БОТ] " + issuer + " кикнул " + playerName + " по причине: " + reason;
                    if (hidden) {
                        result += " (СКРЫТОЕ)";
                        notifyStaffOnly("СКРЫТЫЙ КИК\nИгрок: " + playerName + "\nПричина: " + reason + "\nВыдал: " + issuer);
                    }
                } else {
                    result = "[БОТ] " + playerName + " не найден!";
                }
                break;

            case "unban":
                success = punishmentManager.unbanPlayer(playerName, issuer, reason);
                if (success) {
                    result = "[БОТ] " + issuer + " разбанил " + playerName + " по причине: " + reason;
                } else {
                    result = "[БОТ] " + playerName + " не забанен!";
                }
                break;

            case "unmute":
                success = punishmentManager.unmutePlayer(playerName, issuer, reason);
                if (success) {
                    result = "[БОТ] " + issuer + " размутил " + playerName + " по причине: " + reason;
                } else {
                    result = "[БОТ] " + playerName + " не замучен!";
                }
                break;
        }

        sendMessage(chatId, result);
    }

    private void handleCheckBan(long chatId, String cmd) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global checkban <ник>");
            return;
        }
        String playerName = parts[1];

        boolean isBanned = punishmentManager.isBanned(playerName);
        if (!isBanned) {
            sendMessage(chatId, "[БОТ] Игрок " + playerName + " не забанен.");
            return;
        }

        String issuer = punishmentManager.getBanIssuer(playerName);
        String reason = punishmentManager.getBanReason(playerName);
        String expiry = punishmentManager.getBanExpiry(playerName);
        PunishmentManager.HistoryEntry entry = punishmentManager.getLastBan(playerName);

        String response = "[БОТ] Ответ сервера:\n";
        response += "----- " + playerName + " -----\n";
        response += " Причина: " + reason + "\n";
        response += " Время: " + punishmentManager.getFormattedDateTime(entry.timestamp) + "\n";
        response += " Истекает: " + expiry + "\n";
        response += " Выдал: " + issuer;

        sendMessage(chatId, response);
    }

    private void handleBanList(long chatId, String cmd) {
        int page = 1;
        String[] parts = cmd.split(" ");
        if (parts.length > 1) {
            try { page = Integer.parseInt(parts[1]); } catch (NumberFormatException e) {}
        }
        int pageSize = 10;
        List<String> allBans = punishmentManager.getBanList();
        List<String> bans = punishmentManager.getBanList(page, pageSize);
        int totalPages = (int) Math.ceil((double) allBans.size() / pageSize);

        if (bans.isEmpty()) {
            sendMessage(chatId, "[БОТ] Список банов пуст.");
        } else {
            StringBuilder response = new StringBuilder();
            response.append("[БОТ] Список банов (Страница ").append(page).append("/").append(totalPages).append(")\n");
            response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            for (String ban : bans) {
                response.append(ban).append("\n");
            }
            response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            if (totalPages > 1) {
                response.append("Используй: !rcon global banlist ").append(page + 1);
            }
            sendMessage(chatId, response.toString());
        }
    }

    private void handleShist(long chatId, String cmd) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global shist <ник>");
            return;
        }
        String target = parts[1];

        List<PunishmentManager.HistoryEntry> history = punishmentManager.getHistory(target);
        if (history.isEmpty()) {
            sendMessage(chatId, "[БОТ] История наказаний для " + target + " пуста.");
            return;
        }

        StringBuilder response = new StringBuilder();
        response.append("[БОТ] История наказаний игрока ").append(target).append(" (Записей: ").append(history.size()).append(")\n");
        response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        int count = 0;
        for (PunishmentManager.HistoryEntry entry : history) {
            if (count >= 20) {
                response.append("... и ещё ").append(history.size() - 20).append(" записей");
                break;
            }
            String timeAgo = punishmentManager.getTimeAgo(entry.timestamp);
            String status = entry.type.equals("ban") ?
                (punishmentManager.isBanned(target) ? "[Активен]" : "[Истек]") : 
                (punishmentManager.isMuted(target) ? "[Активен]" : "[Истек]");
            String actionName = entry.getActionName();

            response.append(timeAgo).append(" | ")
                    .append(target).append(" был ").append(actionName)
                    .append(" на ").append(entry.duration).append(" ")
                    .append(entry.issuer).append(": ").append(entry.reason).append(" ").append(status).append("\n");
            count++;
        }
        response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        sendMessage(chatId, response.toString());
    }

    // ===== ВЫПОЛНЕНИЕ КОМАНД НА СЕРВЕРЕ =====
    private void executeServerCommand(long chatId, String command, long userId) {
        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON@" + userId;

        final long finalChatId = chatId;
        final String finalCommand = command;
        final String finalIssuer = issuer;

        final int[] tempMsgId = {0};
        try {
            SendMessage temp = new SendMessage();
            temp.setChatId(String.valueOf(chatId));
            temp.setText("[БОТ] Выполняю команду на сервере...");
            var sent = execute(temp);
            tempMsgId[0] = sent.getMessageId();
        } catch (Exception e) {}

        final int finalTempMsgId = tempMsgId[0];

        Bukkit.getScheduler().runTask(plugin, () -> {
            commandExecutor.executeCommand(finalCommand, finalIssuer);
            if (finalTempMsgId != 0) {
                deleteMessage(String.valueOf(finalChatId), finalTempMsgId);
            }
            sendMessage(finalChatId, "[БОТ] Ответ от сервера:\nКоманда выполнена: " + finalCommand);
        });
    }

    public void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void deleteMessage(String chatId, int messageId) {
        try {
            DeleteMessage delete = new DeleteMessage();
            delete.setChatId(chatId);
            delete.setMessageId(messageId);
            execute(delete);
        } catch (TelegramApiException e) {}
    }
}
