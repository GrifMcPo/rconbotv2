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

        if (messageText.startsWith("!rcon global ")) {
            handleRconCommand(chatId, messageText.substring(13).trim(), userId);
            return;
        }

        if (messageText.startsWith("!")) {
            sendMessage(chatId, "[БОТ] Неизвестная команда. Доступные команды: !id, !rcon global ...");
        }
    }

    private void handleRconCommand(long chatId, String cmd, long userId) {
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

        if (cmd.startsWith("logs ")) {
            handleLogs(chatId, cmd, userId);
            return;
        }

        if (cmd.startsWith("shist ")) {
            handleShist(chatId, cmd, userId);
            return;
        }

        if (cmd.startsWith("hist ")) {
            handleHist(chatId, cmd, userId);
            return;
        }

        if (cmd.startsWith("dupeip ")) {
            handleDupeip(chatId, cmd, userId);
            return;
        }

        if (cmd.startsWith("pex user ")) {
            handlePexUser(chatId, cmd, userId);
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

        executeServerCommand(chatId, cmd, userId);
    }

    // ===== LOGS =====
    private void handleLogs(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global logs <ник> [кол-во]");
            return;
        }
        
        String playerName = parts[1];
        int limit = 10;
        if (parts.length >= 3) {
            try { limit = Integer.parseInt(parts[2]); } catch (NumberFormatException e) {}
            if (limit < 1) limit = 1;
            if (limit > 50) limit = 50;
        }

        String response = commandLogger.getFormattedLogs(playerName, limit);
        sendMessage(chatId, response);
    }

    // ===== SHIST =====
    private void handleShist(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global shist <ник> [кол-во]");
            return;
        }
        
        String issuerName = parts[1];
        int limit = 10;
        if (parts.length >= 3) {
            try { limit = Integer.parseInt(parts[2]); } catch (NumberFormatException e) {}
            if (limit < 1) limit = 1;
            if (limit > 50) limit = 50;
        }

        String response = punishmentManager.getFormattedShist(issuerName, limit);
        sendMessage(chatId, response);
    }

    // ===== HIST =====
    private void handleHist(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global hist <ник> [кол-во]");
            return;
        }
        
        String playerName = parts[1];
        int limit = 10;
        if (parts.length >= 3) {
            try { limit = Integer.parseInt(parts[2]); } catch (NumberFormatException e) {}
            if (limit < 1) limit = 1;
            if (limit > 50) limit = 50;
        }

        String response = punishmentManager.getFormattedHistory(playerName, limit);
        sendMessage(chatId, response);
    }

    // ===== DUPEIP =====
    private void handleDupeip(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global dupeip <ник>");
            return;
        }
        
        String playerName = parts[1];
        String targetIp = playerManager.getPlayerIp(playerName);
        
        if (targetIp == null || targetIp.equals("—") || targetIp.equals("0.0.0.0")) {
            sendMessage(chatId, "[БОТ] Не удалось определить IP игрока " + playerName);
            return;
        }

        List<String> playersWithSameIp = playerManager.getPlayersByIp(targetIp);
        playersWithSameIp.remove(playerName);

        StringBuilder response = new StringBuilder();
        response.append("[БОТ] Сканируем по нику: ").append(playerName).append("\n");
        
        if (playersWithSameIp.isEmpty()) {
            response.append("Нет других аккаунтов с этим IP");
        } else {
            response.append(String.join(", ", playersWithSameIp));
        }
        
        sendMessage(chatId, response.toString());
    }

    // ===== PEX USER =====
    private void handlePexUser(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 3) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global pex user <ник>");
            return;
        }
        
        String playerName = parts[2];
        Player target = Bukkit.getPlayer(playerName);
        boolean isOnline = target != null && target.isOnline();

        String group = "default";
        boolean isOp = false;
        boolean isWhitelisted = false;
        String ip = "—";

        try {
            if (isOnline) {
                isOp = target.isOp();
                ip = target.getAddress() != null ? target.getAddress().getHostString() : "—";
                
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
                ip = playerManager.getPlayerIp(playerName);
                
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

        } catch (Exception e) {
            sendMessage(chatId, "[БОТ] Ошибка: " + e.getMessage());
            return;
        }

        String response = "[БОТ] Ответ сервера:\n";
        response += "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n";
        response += "Ник: " + playerName + "\n";
        response += "Группа: " + group + "\n";
        response += "OP: " + (isOp ? "да" : "нет") + "\n";
        response += "IP: " + ip + "\n";
        response += "Белый список: " + (isWhitelisted ? "да" : "нет") + "\n";
        response += "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

        sendMessage(chatId, response);
    }

    // ===== BROADCAST =====
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

    // ===== НАКАЗАНИЯ С ФЛАГОМ -s =====
    private void handlePunishment(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        String action = parts[0];
        String playerName = parts[1];

        // Проверяем флаг -s в конце
        boolean hidden = false;
        String lastArg = parts[parts.length - 1];
        if (lastArg.equalsIgnoreCase("-s")) {
            hidden = true;
            // Убираем -s из массива
            parts = Arrays.copyOf(parts, parts.length - 1);
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
                // banPlayer(playerName, issuer, reason, duration, hidden, broadcast)
                success = punishmentManager.banPlayer(playerName, issuer, reason, duration, hidden, false);
                if (success) {
                    result = "[БОТ] Ответ сервера:\n";
                    if (hidden) {
                        result += "[Скрыто] ❨！❩ Игрок " + issuer + " забанил " + playerName + 
                                  " на " + duration + " по причине: " + reason + " (глобальный)";
                    } else {
                        result += "❨！❩ Игрок " + issuer + " забанил " + playerName + 
                                  " на " + duration + " по причине: " + reason + " (глобальный)";
                    }
                } else {
                    result = "[БОТ] " + playerName + " уже забанен!";
                }
                break;

            case "mute":
                // mutePlayer(playerName, issuer, reason, duration, hidden, broadcast)
                success = punishmentManager.mutePlayer(playerName, issuer, reason, duration, hidden, false);
                if (success) {
                    result = "[БОТ] Ответ сервера:\n";
                    if (hidden) {
                        result += "[Скрыто] ❨！❩ Игрок " + issuer + " замутил " + playerName + 
                                  " на " + duration + " по причине: " + reason + " (глобальный)";
                    } else {
                        result += "❨！❩ Игрок " + issuer + " замутил " + playerName + 
                                  " на " + duration + " по причине: " + reason + " (глобальный)";
                    }
                } else {
                    result = "[БОТ] " + playerName + " уже замучен!";
                }
                break;

            case "kick":
                // kickPlayer(playerName, issuer, reason, hidden, broadcast)
                success = punishmentManager.kickPlayer(playerName, issuer, reason, hidden, false);
                if (success) {
                    result = "[БОТ] Ответ сервера:\n";
                    if (hidden) {
                        result += "[Скрыто] ❨！❩ Игрок " + issuer + " кикнул " + playerName + 
                                  " по причине: " + reason + " (глобальный)";
                    } else {
                        result += "❨！❩ Игрок " + issuer + " кикнул " + playerName + 
                                  " по причине: " + reason + " (глобальный)";
                    }
                } else {
                    result = "[БОТ] " + playerName + " не найден!";
                }
                break;

            case "unban":
                // unbanPlayer(playerName, issuer, reason, broadcast)
                success = punishmentManager.unbanPlayer(playerName, issuer, reason, false);
                if (success) {
                    result = "[БОТ] Ответ сервера:\n❨！❩ Игрок " + issuer + " разбанил " + playerName + 
                              " по причине: " + reason + " (глобальный)";
                } else {
                    result = "[БОТ] " + playerName + " не забанен!";
                }
                break;

            case "unmute":
                // unmutePlayer(playerName, issuer, reason, broadcast)
                success = punishmentManager.unmutePlayer(playerName, issuer, reason, false);
                if (success) {
                    result = "[БОТ] Ответ сервера:\n❨！❩ Игрок " + issuer + " размутил " + playerName + 
                              " по причине: " + reason + " (глобальный)";
                } else {
                    result = "[БОТ] " + playerName + " не замучен!";
                }
                break;
        }

        sendMessage(chatId, result);
        if (hidden) {
            notifyStaffOnly("СКРЫТОЕ НАКАЗАНИЕ\n" + action + " " + playerName + "\nПричина: " + reason + "\nСрок: " + duration + "\nВыдал: " + issuer);
        }
    }

    // ===== CHECKBAN =====
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

    // ===== BANLIST =====
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
