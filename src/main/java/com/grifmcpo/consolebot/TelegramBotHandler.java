package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
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
    private final Map<Long, String> pendingUnlinkRequests = new HashMap<>();

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
            try {
                hiddenViewers.add(Long.parseLong(id));
            } catch (NumberFormatException e) {}
        }
        plugin.getLogger().info("Загружено зрителей скрытых наказаний: " + hiddenViewers.size());
    }

    private void loadKnownUsers() {
        File knownFile = new File(plugin.getDataFolder(), "known_users.txt");
        if (knownFile.exists()) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(knownFile.toPath());
                for (String line : lines) {
                    try {
                        knownUsers.add(Long.parseLong(line.trim()));
                    } catch (NumberFormatException e) {}
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

    @Override
    public String getBotUsername() {
        return "TelegramConsoleBot";
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update);
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

        if (pendingUnlinkRequests.containsKey(userId)) {
            handleUnlinkReason(chatId, userId, messageText);
            return;
        }

        if (messageText.equalsIgnoreCase("/start")) {
            sendStartMessage(chatId);
            return;
        }

        if (messageText.equalsIgnoreCase("!id") || messageText.equalsIgnoreCase("/id")) {
            sendMessage(chatId, "[БОТ] Ваш ID: " + userId);
            return;
        }

        if (messageText.equalsIgnoreCase("/помощь") || messageText.equalsIgnoreCase("!помощь")) {
            sendHelp(chatId);
            return;
        }

        if (messageText.equalsIgnoreCase("/помощь2") || messageText.equalsIgnoreCase("!помощь2")) {
            sendHelp2(chatId);
            return;
        }

        if (!messageText.startsWith("!rcon global ")) {
            if (messageText.startsWith("!")) {
                sendMessage(chatId, "[БОТ] Неизвестная команда. Введите /помощь для списка команд.");
            }
            return;
        }

        String userGroup = groupManager.getUserGroup(userId);
        if (userGroup == null && !plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
            sendMessage(chatId, "[БОТ] У Вас нет доступа к боту!");
            return;
        }

        handleRconCommand(chatId, messageText.substring(13).trim(), userId);
    }

    private void handleCallbackQuery(Update update) {
        String data = update.getCallbackQuery().getData();
        String chatIdStr = update.getCallbackQuery().getMessage().getChatId().toString();
        int messageId = update.getCallbackQuery().getMessage().getMessageId();
        long chatId = Long.parseLong(chatIdStr);

        if (data.startsWith("auth_allow_")) {
            handleAuthAllow(chatId, data, messageId);
            return;
        }
        if (data.startsWith("auth_deny_")) {
            handleAuthDeny(chatId, data, messageId);
            return;
        }

        if (data.startsWith("reply_")) {
            String[] parts = data.split("_");
            String playerName = parts[1];
            long playerId = Long.parseLong(parts[2]);
            plugin.getLogger().info("Ответ на репорт от " + playerName + " (ID: " + playerId + ")");
            sendMessage(chatId, "Введите сообщение для ответа игроку " + playerName + ":");
            deleteMessage(chatIdStr, messageId);
            return;
        }

        if (data.startsWith("confirm_")) {
            handleConfirm(chatId, data, messageId);
            return;
        }

        if (data.startsWith("cancel_")) {
            deleteMessage(chatIdStr, messageId);
            sendMessage(chatId, "Операция отменена.");
            return;
        }

        if (data.startsWith("page_")) {
            String[] parts = data.split("_");
            String type = parts[1];
            String playerName = parts[2];
            int page = Integer.parseInt(parts[3]);
            handlePagination(chatId, type, playerName, page, messageId);
            return;
        }
    }

    private void handleAuthAllow(long chatId, String data, int messageId) {
        String[] parts = data.split("_", 4);
        if (parts.length < 4) return;
        String playerName = parts[2];
        String ip = parts[3];

        deleteMessage(String.valueOf(chatId), messageId);
        sendMessage(chatId, "[БОТ] Авторизация через бот отключена.");

        Player player = Bukkit.getPlayer(playerName);
        if (player != null && player.isOnline()) {
            player.setWalkSpeed(0.2f);
            player.setFlySpeed(0.1f);
            player.resetTitle();
        }
    }

    private void handleAuthDeny(long chatId, String data, int messageId) {
        String[] parts = data.split("_", 4);
        if (parts.length < 4) return;
        String playerName = parts[2];
        String ip = parts[3];

        deleteMessage(String.valueOf(chatId), messageId);
        sendMessage(chatId, "[БОТ] Авторизация через бот отключена.");

        Player player = Bukkit.getPlayer(playerName);
        if (player != null && player.isOnline()) {
            player.kickPlayer("Авторизация через бот отключена");
        }
    }

    private void handleUnlinkReason(long chatId, long userId, String reason) {
        String playerName = pendingUnlinkRequests.remove(userId);
        if (playerName == null) return;
        sendMessage(chatId, "[БОТ] Привязка аккаунтов отключена.");
    }

    private void sendStartMessage(long chatId) {
        String msg = "[БОТ] Приветствую! Это официальный бот GrifMc!\n\n" +
                "Команды:\n" +
                "/помощь - список всех команд\n" +
                "!rcon global <команда> - выполнение RCON команд";
        sendMessage(chatId, msg);
    }

    private void sendHelp(long chatId) {
        String msg = "[БОТ] Вот полный список команд:\n\n" +
                "/помощь2 - помощь по RCON командам\n" +
                "/id - показать ваш Telegram ID\n\n" +
                "Для выполнения команд используйте:\n" +
                "!rcon global <команда>";
        sendMessage(chatId, msg);
    }

    private void sendHelp2(long chatId) {
        String msg = "[БОТ] Помощь по RCON командам:\n\n" +
                "!rcon global ban <ник> [время] <причина> [-s] - забанить игрока\n" +
                "!rcon global banuuid <uuid> [время] <причина> [-s] - бан по UUID\n" +
                "!rcon global mute <ник> [время] <причина> [-s] - замутить игрока\n" +
                "!rcon global kick <ник> <причина> [-s] - кикнуть игрока\n" +
                "!rcon global warn <ник> <причина> [-s] - выдать предупреждение\n" +
                "!rcon global unwarn <ник> <причина> - снять предупреждение\n" +
                "!rcon global unban <ник> <причина> - разбанить игрока\n" +
                "!rcon global unmute <ник> <причина> - размутить игрока\n" +
                "!rcon global banip <ник> [время] <причина> [-s] - забанить IP игрока\n" +
                "!rcon global unbanip <ник> <причина> - разбанить IP\n" +
                "!rcon global checkban <ник> - проверить бан\n" +
                "!rcon global checkmute <ник> - проверить мут\n" +
                "!rcon global banlist - список банов\n" +
                "!rcon global mutelist - список мутов\n" +
                "!rcon global logs <ник> [кол-во] - логи команд\n" +
                "!rcon global hist <ник> [кол-во] - история наказаний игрока\n" +
                "!rcon global shist <ник> [кол-во] - наказания выданные игроком\n" +
                "!rcon global dupeip <ник> - поиск по IP\n" +
                "!rcon global seen <ник> - узнать онлайн/офлайн игрока\n" +
                "!rcon global lpinfo <ник> - информация о игроке\n" +
                "!rcon global pex user <ник> - информация о игроке\n" +
                "!rcon global pex group - список групп\n" +
                "!rcon global console <текст> - отправить текст в консоль\n" +
                "!rcon global bc <текст> - объявление в чат\n\n" +
                "Флаг -s делает наказание скрытым (без оповещений)";
        sendMessage(chatId, msg);
    }

    private void handleRconCommand(long chatId, String cmd, long userId) {
        if (cmd.isEmpty()) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global <команда>");
            return;
        }

        String cmdName = cmd.split(" ")[0].toLowerCase();
        String fullCommand = "!rcon global " + cmdName;

        if (!groupManager.hasPermission(userId, fullCommand) &&
                !plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
            sendMessage(chatId, "[БОТ] У вас нет доступа к данной команде!");
            plugin.getLogger().info("Доступ запрещён: " + userId + " -> " + cmdName);
            return;
        }

        switch (cmdName) {
            case "ban":
                handlePunishment(chatId, cmd, userId, "ban");
                break;
            case "banuuid":
                handlePunishment(chatId, cmd, userId, "banuuid");
                break;
            case "mute":
                handlePunishment(chatId, cmd, userId, "mute");
                break;
            case "kick":
                handlePunishment(chatId, cmd, userId, "kick");
                break;
            case "warn":
                handlePunishment(chatId, cmd, userId, "warn");
                break;
            case "unwarn":
                handlePunishment(chatId, cmd, userId, "unwarn");
                break;
            case "unban":
                handlePunishment(chatId, cmd, userId, "unban");
                break;
            case "unmute":
                handlePunishment(chatId, cmd, userId, "unmute");
                break;
            case "banip":
                handleBanIp(chatId, cmd, userId);
                break;
            case "unbanip":
                handleUnbanIp(chatId, cmd, userId);
                break;
            case "checkban":
                handleCheckBan(chatId, cmd);
                break;
            case "checkmute":
                handleCheckMute(chatId, cmd);
                break;
            case "banlist":
                handleBanList(chatId, cmd);
                break;
            case "mutelist":
                handleMuteList(chatId, cmd);
                break;
            case "logs":
                handleLogs(chatId, cmd);
                break;
            case "shist":
                handleShist(chatId, cmd);
                break;
            case "hist":
                handleHist(chatId, cmd);
                break;
            case "dupeip":
                handleDupeip(chatId, cmd);
                break;
            case "seen":
                handleSeen(chatId, cmd);
                break;
            case "lpinfo":
                handleLpInfo(chatId, cmd);
                break;
            case "pex":
                handlePex(chatId, cmd);
                break;
            case "console":
                handleConsole(chatId, cmd, userId);
                break;
            case "bc":
            case "bcast":
                handleBroadcast(chatId, cmd, userId);
                break;
            default:
                executeServerCommand(chatId, cmd, userId);
                break;
        }
    }

    // =========================================================
    // ==== НАКАЗАНИЯ =====
    // =========================================================
    private void handlePunishment(long chatId, String cmd, long userId, String action) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global " + action + " ...");
            return;
        }

        boolean hidden = false;
        String lastArg = parts[parts.length - 1];
        if (lastArg.equalsIgnoreCase("-s")) {
            hidden = true;
            parts = Arrays.copyOf(parts, parts.length - 1);
        }

        String playerName = parts[1];
        String duration = "навсегда";
        String reason;
        int start = 2;

        if (!action.equals("unwarn") && !action.equals("unban") && !action.equals("unmute") && !action.equals("kick") && !action.equals("warn")) {
            if (parts.length > 2 && punishmentManager.isValidTime(parts[2])) {
                duration = parts[2];
                start = 3;
            }
        }

        if (parts.length > start) {
            reason = String.join(" ", Arrays.copyOfRange(parts, start, parts.length));
        } else {
            if (action.equals("unwarn") || action.equals("unban") || action.equals("unmute")) {
                reason = "Без причины";
            } else {
                sendMessage(chatId, "[БОТ] Вы должны указать причину!");
                return;
            }
        }

        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON@" + userId;

        boolean success = false;
        String result = "";
        boolean broadcast = !hidden;

        switch (action) {
            case "ban":
                success = punishmentManager.banPlayer(playerName, issuer, reason, duration, hidden, broadcast);
                if (success) {
                    String timeStr = duration.equals("навсегда") ? "" : " на " + punishmentManager.formatDuration(duration) + " ";
                    result = "[БОТ] Ответ от сервера:\n" + (hidden ? "[СКРЫТНО] " : "") + "Игрок " + issuer + " забанил " + playerName + timeStr + "по причине: " + reason + " (глобальный)";
                } else {
                    result = "[БОТ] Ответ от сервера:\n" + playerName + " уже забанен!";
                }
                break;

            case "banuuid":
                success = punishmentManager.banUuid(playerName, issuer, reason, duration, hidden, broadcast);
                if (success) {
                    String timeStr = duration.equals("навсегда") ? "" : " на " + punishmentManager.formatDuration(duration) + " ";
                    result = "[БОТ] Ответ от сервера:\n" + (hidden ? "[СКРЫТНО] " : "") + "Игрок " + issuer + " забанил " + playerName + " (по UUID)" + timeStr + "по причине: " + reason + " (глобальный)";
                } else {
                    result = "[БОТ] Ответ от сервера:\n" + playerName + " уже забанен или не найден!";
                }
                break;

            case "mute":
                success = punishmentManager.mutePlayer(playerName, issuer, reason, duration, hidden, broadcast);
                if (success) {
                    String timeStr = duration.equals("навсегда") ? "" : " на " + punishmentManager.formatDuration(duration) + " ";
                    result = "[БОТ] Ответ от сервера:\n" + (hidden ? "[СКРЫТНО] " : "") + "Игрок " + issuer + " замутил " + playerName + timeStr + "по причине: " + reason + " (глобальный)";
                } else {
                    result = "[БОТ] Ответ от сервера:\n" + playerName + " уже замучен!";
                }
                break;

            case "kick":
                success = punishmentManager.kickPlayer(playerName, issuer, reason, hidden, broadcast);
                if (success) {
                    result = "[БОТ] Ответ от сервера:\n" + (hidden ? "[СКРЫТНО] " : "") + "Игрок " + issuer + " кикнул " + playerName + " по причине: " + reason + " (глобальный)";
                } else {
                    result = "[БОТ] Ответ от сервера:\n" + playerName + " не найден!";
                }
                break;

            case "warn":
                success = punishmentManager.warnPlayer(playerName, issuer, reason, hidden, broadcast);
                if (success) {
                    result = "[БОТ] Ответ от сервера:\n" + (hidden ? "[СКРЫТНО] " : "") + "Игрок " + issuer + " выдал предупреждение " + playerName + " по причине: " + reason + " (глобальный)";
                } else {
                    result = "[БОТ] Ответ от сервера:\nНе удалось выдать предупреждение!";
                }
                break;

            case "unwarn":
                success = punishmentManager.unwarnPlayer(playerName, issuer, reason, broadcast);
                if (success) {
                    result = "[БОТ] Ответ от сервера:\nИгрок " + issuer + " снял предупреждение " + playerName + " по причине: " + reason + " (глобальный)";
                } else {
                    result = "[БОТ] Ответ от сервера:\nУ игрока " + playerName + " нет предупреждений!";
                }
                break;

            case "unban":
                success = punishmentManager.unbanPlayer(playerName, issuer, reason, broadcast);
                if (success) {
                    result = "[БОТ] Ответ от сервера:\nИгрок " + issuer + " разбанил " + playerName + " по причине: " + reason + " (глобальный)";
                } else {
                    result = "[БОТ] Ответ от сервера:\n" + playerName + " не забанен!";
                }
                break;

            case "unmute":
                success = punishmentManager.unmutePlayer(playerName, issuer, reason, broadcast);
                if (success) {
                    result = "[БОТ] Ответ от сервера:\nИгрок " + issuer + " размутил " + playerName + " по причине: " + reason + " (глобальный)";
                } else {
                    result = "[БОТ] Ответ от сервера:\n" + playerName + " не замучен!";
                }
                break;
        }

        sendMessage(chatId, result);
    }

    // =========================================================
    // ==== BANIP =====
    // =========================================================
    private void handleBanIp(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 3) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global banip <ник> [время] <причина> [-s]");
            return;
        }

        boolean hidden = false;
        String lastArg = parts[parts.length - 1];
        if (lastArg.equalsIgnoreCase("-s")) {
            hidden = true;
            parts = Arrays.copyOf(parts, parts.length - 1);
        }

        String playerName = parts[1];
        String duration = "навсегда";
        String reason;
        int start = 2;

        if (parts.length > 2 && punishmentManager.isValidTime(parts[2])) {
            duration = parts[2];
            start = 3;
        }

        if (parts.length > start) {
            reason = String.join(" ", Arrays.copyOfRange(parts, start, parts.length));
        } else {
            sendMessage(chatId, "[БОТ] Вы должны указать причину!");
            return;
        }

        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON@" + userId;

        boolean success = punishmentManager.banIp(playerName, issuer, reason, duration, hidden);
        if (success) {
            String timeStr = duration.equals("навсегда") ? "" : " на " + punishmentManager.formatDuration(duration) + " ";
            String msg = "[БОТ] Ответ от сервера:\n" + (hidden ? "[СКРЫТНО] " : "") + "Игрок " + issuer + " забанил IP " + playerName + timeStr + "по причине: " + reason + " (глобальный)";
            sendMessage(chatId, msg);
        } else {
            sendMessage(chatId, "[БОТ] Ответ от сервера:\nНе удалось забанить IP " + playerName);
        }
    }

    // =========================================================
    // ==== UNBANIP =====
    // =========================================================
    private void handleUnbanIp(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 3) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global unbanip <ник> <причина>");
            return;
        }

        String playerName = parts[1];
        String reason = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON@" + userId;

        boolean success = punishmentManager.unbanIp(playerName, issuer, reason);
        if (success) {
            sendMessage(chatId, "[БОТ] Ответ от сервера:\nИгрок " + issuer + " разбанил IP " + playerName + " по причине: " + reason + " (глобальный)");
        } else {
            sendMessage(chatId, "[БОТ] Ответ от сервера:\nIP игрока " + playerName + " не забанен!");
        }
    }

    // =========================================================
    // ==== CHECKBAN =====
    // =========================================================
    private void handleCheckBan(long chatId, String cmd) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global checkban <ник>");
            return;
        }
        String playerName = parts[1];

        boolean isBanned = punishmentManager.isBanned(playerName);
        if (!isBanned) {
            sendMessage(chatId, "[БОТ] Ответ от сервера:\nИгрок " + playerName + " не забанен.");
            return;
        }

        String issuer = punishmentManager.getBanIssuer(playerName);
        String reason = punishmentManager.getBanReason(playerName);
        long expiry = punishmentManager.getBanExpiry(playerName);
        String expiryStr = expiry == -1 ? "навсегда" : punishmentManager.formatTimeLeft(expiry);
        boolean isPermanent = expiry == -1;

        PunishmentManager.HistoryEntry entry = punishmentManager.getLastBan(playerName);
        boolean isHidden = entry != null && entry.hidden;
        boolean isIpBan = entry != null && entry.ipBan;

        String response = "[БОТ] Ответ от сервера:\n";
        response += "----- " + playerName + " -----\n";
        response += " Причина: " + reason + "\n";
        response += " Время: " + punishmentManager.getFormattedDateTime(entry != null ? entry.timestamp : System.currentTimeMillis()) + "\n";
        response += " Истекает: " + expiryStr + "\n";
        response += " Сервер: выживание\n";
        response += " Выдал: " + issuer + "\n";
        response += " IP: " + (isIpBan ? "да" : "нет") + ", скрыто: " + (isHidden ? "да" : "нет") + ", навсегда: " + (isPermanent ? "да" : "нет");

        sendMessage(chatId, response);
    }

    // =========================================================
    // ==== CHECKMUTE =====
    // =========================================================
    private void handleCheckMute(long chatId, String cmd) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global checkmute <ник>");
            return;
        }
        String playerName = parts[1];

        boolean isMuted = punishmentManager.isMuted(playerName);
        if (!isMuted) {
            sendMessage(chatId, "[БОТ] Ответ от сервера:\nИгрок " + playerName + " не замучен.");
            return;
        }

        String issuer = punishmentManager.getMuteIssuer(playerName);
        String reason = punishmentManager.getMuteReason(playerName);
        long expiry = punishmentManager.getMuteExpiry(playerName);
        String expiryStr = expiry == -1 ? "навсегда" : punishmentManager.formatTimeLeft(expiry);
        boolean isPermanent = expiry == -1;

        PunishmentManager.HistoryEntry entry = punishmentManager.getLastMute(playerName);
        boolean isHidden = entry != null && entry.hidden;

        String response = "[БОТ] Ответ от сервера:\n";
        response += "----- " + playerName + " -----\n";
        response += " Причина: " + reason + "\n";
        response += " Время: " + punishmentManager.getFormattedDateTime(entry != null ? entry.timestamp : System.currentTimeMillis()) + "\n";
        response += " Истекает: " + expiryStr + "\n";
        response += " Сервер: выживание\n";
        response += " Выдал: " + issuer + "\n";
        response += " IP: нет, скрыто: " + (isHidden ? "да" : "нет") + ", навсегда: " + (isPermanent ? "да" : "нет");

        sendMessage(chatId, response);
    }

    // =========================================================
    // ==== BANLIST =====
    // =========================================================
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
            sendMessage(chatId, "[БОТ] Ответ от сервера:\nСписок банов пуст.");
        } else {
            StringBuilder response = new StringBuilder();
            response.append("[БОТ] Ответ от сервера:\nСписок банов (Страница ").append(page).append("/").append(totalPages).append(")\n");
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

    // =========================================================
    // ==== MUTELIST =====
    // =========================================================
    private void handleMuteList(long chatId, String cmd) {
        int page = 1;
        String[] parts = cmd.split(" ");
        if (parts.length > 1) {
            try { page = Integer.parseInt(parts[1]); } catch (NumberFormatException e) {}
        }
        int pageSize = 10;
        List<String> allMutes = punishmentManager.getMuteList();
        List<String> mutes = punishmentManager.getMuteList(page, pageSize);
        int totalPages = (int) Math.ceil((double) allMutes.size() / pageSize);

        if (mutes.isEmpty()) {
            sendMessage(chatId, "[БОТ] Ответ от сервера:\nСписок мутов пуст.");
        } else {
            StringBuilder response = new StringBuilder();
            response.append("[БОТ] Ответ от сервера:\nСписок мутов (Страница ").append(page).append("/").append(totalPages).append(")\n");
            response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            for (String mute : mutes) {
                response.append(mute).append("\n");
            }
            response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            if (totalPages > 1) {
                response.append("Используй: !rcon global mutelist ").append(page + 1);
            }
            sendMessage(chatId, response.toString());
        }
    }

    // =========================================================
    // ==== LOGS =====
    // =========================================================
    private void handleLogs(long chatId, String cmd) {
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

    // =========================================================
    // ==== SHIST =====
    // =========================================================
    private void handleShist(long chatId, String cmd) {
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

    // =========================================================
    // ==== HIST =====
    // =========================================================
    private void handleHist(long chatId, String cmd) {
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

    // =========================================================
    // ==== DUPEIP =====
    // =========================================================
    private void handleDupeip(long chatId, String cmd) {
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
        response.append("[БОТ] Ответ от сервера:\nСканируем по нику: ").append(playerName).append("\n");
        if (playersWithSameIp.isEmpty()) {
            response.append("Нет других аккаунтов с этим IP");
        } else {
            response.append(String.join(", ", playersWithSameIp));
        }

        sendMessage(chatId, response.toString());
    }

    // =========================================================
    // ==== SEEN =====
    // =========================================================
    private void handleSeen(long chatId, String cmd) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global seen <ник>");
            return;
        }

        String playerName = parts[1];
        Player player = Bukkit.getPlayer(playerName);
        boolean isOnline = player != null && player.isOnline();

        String response = "[БОТ] Ответ от сервера:\n";
        
        if (isOnline) {
            long onlineTime = System.currentTimeMillis() - player.getFirstPlayed();
            response += "Игрок " + playerName + " онлайн в течение " + formatTime(onlineTime) + "\n";
            response += " - UUID: " + player.getUniqueId().toString();
        } else {
            String offlineTime = getOfflineTime(playerName);
            UUID uuid = getPlayerUuid(playerName);
            boolean isWhitelisted = isPlayerWhitelisted(playerName);
            
            response += "Игрок " + playerName + " офлайн в течение " + offlineTime + "\n";
            response += " - UUID: " + (uuid != null ? uuid.toString() : "—") + "\n";
            response += " - В белом списке: " + (isWhitelisted ? "правда" : "ложь") + "\n";
            response += " - Местоположение: неизвестно";
        }

        sendMessage(chatId, response);
    }

    // =========================================================
    // ==== LPINFO =====
    // =========================================================
    private void handleLpInfo(long chatId, String cmd) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global lpinfo <ник>");
            return;
        }

        String playerName = parts[1];
        Player target = Bukkit.getPlayer(playerName);
        boolean isOnline = target != null && target.isOnline();

        try {
            String group = "default";
            String uuid = isOnline ? target.getUniqueId().toString() : "—";
            String status = isOnline ? "Online" : "Offline";
            String primaryGroup = "default";
            
            net.milkbowl.vault.permission.Permission permission = Bukkit.getServicesManager()
                    .getRegistration(net.milkbowl.vault.permission.Permission.class).getProvider();
            
            if (permission != null && isOnline) {
                group = permission.getPrimaryGroup(target);
                primaryGroup = group != null ? group : "default";
            }

            String response = "[БОТ] Ответ от сервера:\n";
            response += "[LP] > User Info: " + playerName + "\n";
            response += "[LP] - UUID: " + uuid + "\n";
            response += "[LP]     (type: " + (isOnline ? "online" : "offline") + ")\n";
            response += "[LP] - Status: " + status + "\n";
            response += "[LP] - Parent Groups:\n";
            response += "[LP]     > " + primaryGroup + "\n";
            response += "[LP] - Contextual Data: \n";
            response += "[LP]     Contexts: None\n";
            response += "[LP]     Prefix: \"\"\n";
            response += "[LP]     Suffix: \"\"\n";
            response += "[LP]     Primary Group: " + primaryGroup + "\n";
            response += "[LP]     Meta: (default=true) (primarygroup=" + primaryGroup + ")";

            sendMessage(chatId, response);
            
        } catch (Exception e) {
            sendMessage(chatId, "[БОТ] Ошибка: " + e.getMessage());
            plugin.getLogger().warning("LPInfo error: " + e.getMessage());
        }
    }

    // =========================================================
    // ==== PEX =====
    // =========================================================
    private void handlePex(long chatId, String cmd) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global pex user <ник> | !rcon global pex group");
            return;
        }

        String subCmd = parts[1].toLowerCase();

        if (subCmd.equals("user") && parts.length >= 3) {
            handlePexUser(chatId, cmd);
        } else if (subCmd.equals("group")) {
            handlePexGroup(chatId);
        } else {
            sendMessage(chatId, "[БОТ] Использование: !rcon global pex user <ник> | !rcon global pex group");
        }
    }

    private void handlePexUser(long chatId, String cmd) {
        String[] parts = cmd.split(" ");
        if (parts.length < 3) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global pex user <ник>");
            return;
        }

        String playerName = parts[2];
        Player target = Bukkit.getPlayer(playerName);
        boolean isOnline = target != null && target.isOnline();

        String group = "default";
        String uuid = "—";

        try {
            if (isOnline) {
                uuid = target.getUniqueId().toString();
                net.milkbowl.vault.permission.Permission permission = Bukkit.getServicesManager()
                        .getRegistration(net.milkbowl.vault.permission.Permission.class).getProvider();
                if (permission != null) {
                    group = permission.getPrimaryGroup(target);
                }
            } else {
                group = "офлайн";
                uuid = "—";
            }
        } catch (Exception e) {
            sendMessage(chatId, "[БОТ] Ошибка: " + e.getMessage());
            return;
        }

        String response = "[БОТ] Ответ от сервера:\n";
        response += "Ник: " + playerName + "\n";
        response += "UUID: " + uuid + "\n";
        response += "Группа: " + group;

        sendMessage(chatId, response);
    }

    private void handlePexGroup(long chatId) {
        try {
            net.milkbowl.vault.permission.Permission permission = Bukkit.getServicesManager()
                    .getRegistration(net.milkbowl.vault.permission.Permission.class).getProvider();
            
            if (permission == null) {
                sendMessage(chatId, "[БОТ] Vault не найден!");
                return;
            }

            Map<String, Integer> groupCounts = new HashMap<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                String group = permission.getPrimaryGroup(player);
                if (group != null) {
                    groupCounts.put(group, groupCounts.getOrDefault(group, 0) + 1);
                }
            }

            List<String> sortedGroups = Arrays.asList("owner", "admin", "mod", "helper", "builder", "default");

            String response = "[БОТ] Ответ от сервера:\n";
            response += "Доступные группы:\n";
            response += "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n";
            
            for (String group : sortedGroups) {
                int count = groupCounts.getOrDefault(group, 0);
                response += " - " + group + " (" + count + " игроков)\n";
            }
            
            response += "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n";
            response += "Всего групп: " + sortedGroups.size();

            sendMessage(chatId, response);
            
        } catch (Exception e) {
            sendMessage(chatId, "[БОТ] Ошибка: " + e.getMessage());
        }
    }

    // =========================================================
    // ==== CONSOLE =====
    // =========================================================
    private void handleConsole(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global console <текст>");
            return;
        }

        String text = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        String sender = plugin.getCustomSender(userId);
        if (sender == null) sender = "RCON@" + userId;

        Bukkit.getConsoleSender().sendMessage("§7[TelegramConsoleBot] " + text + " §7(От: " + sender + "§7)");
        plugin.getLogger().info("[Console] " + text + " (От: " + sender + ")");

        sendMessage(chatId, "[БОТ] Ответ от сервера:\nКоманда отправлена в консоль: " + text);
    }

    // =========================================================
    // ==== BROADCAST =====
    // =========================================================
    private void handleBroadcast(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global bc <сообщение>");
            return;
        }

        String message = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        String sender = plugin.getCustomSender(userId);
        if (sender == null) sender = "RCON@" + userId;

        Bukkit.broadcastMessage("§e[Объявление] §f" + message + " §7(пишет: " + sender + "§7)");
        sendMessage(chatId, "[БОТ] Ответ от сервера:\n[Объявление] " + message + " (пишет: " + sender + ")");
    }

    // =========================================================
    // ==== CONFIRM =====
    // =========================================================
    private void handleConfirm(long chatId, String data, int messageId) {
        String[] parts = data.split("_");
        String action = parts[1];
        String playerName = parts[2];
        String duration = parts.length > 3 ? parts[3] : "навсегда";
        String reason = parts.length > 4 ? parts[4] : "Без причины";
        String issuer = plugin.getCustomSender(chatId);
        if (issuer == null) issuer = "RCON";
        boolean hidden = data.contains("_hidden_");

        boolean success = false;
        String result = "";

        switch (action) {
            case "ban":
                success = punishmentManager.banPlayer(playerName, issuer, reason, duration, hidden, !hidden);
                if (success) {
                    result = "[БОТ] Ответ от сервера:\n" + issuer + " забанил " + playerName + (duration.equals("навсегда") ? "" : " на " + duration) + " по причине: " + reason + (hidden ? " (СКРЫТО)" : "");
                } else {
                    result = "[БОТ] Ответ от сервера:\n" + playerName + " уже забанен!";
                }
                break;

            case "mute":
                success = punishmentManager.mutePlayer(playerName, issuer, reason, duration, hidden, !hidden);
                if (success) {
                    result = "[БОТ] Ответ от сервера:\n" + issuer + " замутил " + playerName + (duration.equals("навсегда") ? "" : " на " + duration) + " по причине: " + reason + (hidden ? " (СКРЫТО)" : "");
                } else {
                    result = "[БОТ] Ответ от сервера:\n" + playerName + " уже замучен!";
                }
                break;

            case "kick":
                success = punishmentManager.kickPlayer(playerName, issuer, reason, hidden, !hidden);
                if (success) {
                    result = "[БОТ] Ответ от сервера:\n" + issuer + " кикнул " + playerName + " по причине: " + reason + (hidden ? " (СКРЫТО)" : "");
                } else {
                    result = "[БОТ] Ответ от сервера:\n" + playerName + " не найден!";
                }
                break;

            case "unban":
                success = punishmentManager.unbanPlayer(playerName, issuer, reason, true);
                if (success) {
                    result = "[БОТ] Ответ от сервера:\n" + issuer + " разбанил " + playerName + " по причине: " + reason;
                } else {
                    result = "[БОТ] Ответ от сервера:\n" + playerName + " не забанен!";
                }
                break;

            case "unmute":
                success = punishmentManager.unmutePlayer(playerName, issuer, reason, true);
                if (success) {
                    result = "[БОТ] Ответ от сервера:\n" + issuer + " размутил " + playerName + " по причине: " + reason;
                } else {
                    result = "[БОТ] Ответ от сервера:\n" + playerName + " не замучен!";
                }
                break;

            default:
                result = "[БОТ] Ответ от сервера:\nНеизвестное действие!";
                break;
        }

        deleteMessage(String.valueOf(chatId), messageId);
        sendMessage(chatId, result);
    }

    // =========================================================
    // ==== PAGINATION =====
    // =========================================================
    private void handlePagination(long chatId, String type, String playerName, int page, int messageId) {
        int pageSize = 10;
        List<String> items = new ArrayList<>();

        switch (type) {
            case "banlist":
                items = punishmentManager.getBanList();
                break;
            case "mutelist":
                items = punishmentManager.getMuteList();
                break;
            case "shist":
                List<PunishmentManager.HistoryEntry> history = punishmentManager.getHistory(playerName);
                for (PunishmentManager.HistoryEntry entry : history) {
                    String timeAgo = punishmentManager.getTimeAgo(entry.timestamp);
                    String status = entry.type.equals("ban") ?
                        (punishmentManager.isBanned(playerName) ? "[Активен]" : "[Истек]") :
                        (punishmentManager.isMuted(playerName) ? "[Активен]" : "[Истек]");
                    items.add("- " + timeAgo + " -\n   " + playerName + " был " + entry.getActionName() +
                            " на " + entry.duration + " " + entry.issuer + ": " + entry.reason + " " + status);
                }
                break;
            default: return;
        }

        int totalPages = (int) Math.ceil((double) items.size() / pageSize);
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, items.size());
        List<String> pageItems = items.subList(start, end);

        StringBuilder response = new StringBuilder();
        String title = type.equals("banlist") ? "Список банов" :
                       type.equals("mutelist") ? "Список мутов" :
                       "История наказаний для " + playerName;
        response.append("[БОТ] Ответ от сервера:\n");
        response.append(title).append(" (Страница ").append(page).append("/").append(totalPages).append(")");

        for (String item : pageItems) {
            response.append("\n\n").append(item);
        }
        response.append("\n\nВсего записей: ").append(items.size());

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        if (page > 1) {
            InlineKeyboardButton prevBtn = new InlineKeyboardButton();
            prevBtn.setText("Назад");
            prevBtn.setCallbackData("page_" + type + "_" + playerName + "_" + (page - 1));
            row.add(prevBtn);
        }
        if (page < totalPages) {
            InlineKeyboardButton nextBtn = new InlineKeyboardButton();
            nextBtn.setText("Вперёд");
            nextBtn.setCallbackData("page_" + type + "_" + playerName + "_" + (page + 1));
            row.add(nextBtn);
        }
        if (!row.isEmpty()) {
            rows.add(row);
            markup.setKeyboard(rows);
        }

        deleteMessage(String.valueOf(chatId), messageId);

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(response.toString());
        if (!rows.isEmpty()) {
            msg.setReplyMarkup(markup);
        }
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // =========================================================
    // ==== EXECUTE SERVER COMMAND =====
    // =========================================================
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

    // =========================================================
    // ==== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ =====
    // =========================================================

    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        seconds %= 60;
        minutes %= 60;
        hours %= 24;
        
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("д ");
        if (hours > 0) sb.append(hours).append("ч ");
        if (minutes > 0 && hours == 0) sb.append(minutes).append("м ");
        if (seconds > 0 && minutes == 0 && hours == 0) sb.append(seconds).append("с");
        
        if (sb.length() == 0) return "только что";
        return sb.toString().trim();
    }

    private String getOfflineTime(String playerName) {
        try {
            File logFile = new File("logs/latest.log");
            if (logFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(logFile.toPath()));
                String[] lines = content.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (lines[i].contains(playerName) && lines[i].contains("left")) {
                        String timeStr = lines[i].substring(0, 19);
                        try {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                            Date date = sdf.parse(timeStr);
                            long diff = System.currentTimeMillis() - date.getTime();
                            return formatTime(diff);
                        } catch (Exception e) {}
                    }
                }
            }
        } catch (Exception e) {}
        return "неизвестно";
    }

    private UUID getPlayerUuid(String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        if (player != null) return player.getUniqueId();
        
        try {
            File usercache = new File("usercache.json");
            if (usercache.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(usercache.toPath()));
                int idx = content.indexOf("\"name\":\"" + playerName + "\"");
                if (idx != -1) {
                    int uuidIdx = content.lastIndexOf("\"uuid\":\"", idx);
                    if (uuidIdx != -1) {
                        String uuidStr = content.substring(uuidIdx + 9, content.indexOf("\"", uuidIdx + 10));
                        return UUID.fromString(uuidStr);
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private boolean isPlayerWhitelisted(String playerName) {
        try {
            File whitelist = new File("whitelist.json");
            if (whitelist.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(whitelist.toPath()));
                return content.contains("\"name\":\"" + playerName + "\"");
            }
        } catch (Exception e) {}
        return false;
    }

    public void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
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
