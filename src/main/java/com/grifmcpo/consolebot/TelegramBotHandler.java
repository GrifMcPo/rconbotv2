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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

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
    }

    private void loadHiddenViewers() {
        hiddenViewers.clear();
        hiddenViewers.add(plugin.getOwnerId());
        for (String id : plugin.getAdmins().keySet()) {
            try { hiddenViewers.add(Long.parseLong(id)); } catch (NumberFormatException e) {}
        }
        plugin.getLogger().info("✅ Загружено зрителей скрытых наказаний: " + hiddenViewers.size());
    }

    public boolean canSeeHidden(long userId) {
        return hiddenViewers.contains(userId);
    }

    private void notifyStaffOnly(String message) {
        int count = 0;
        for (long id : hiddenViewers) {
            try { sendMessage(id, "[СТАФФ] " + message); count++; } catch (Exception e) {}
        }
        plugin.getLogger().info("🔒 Скрытое уведомление отправлено " + count + " пользователям");
    }

    @Override
    public String getBotUsername() { return "TelegramConsoleBot"; }

    @Override
    public String getBotToken() { return botToken; }

    @Override
    public void onUpdateReceived(Update update) {
        // ===== КНОПКИ =====
        if (update.hasCallbackQuery()) {
            String data = update.getCallbackQuery().getData();
            String chatIdStr = update.getCallbackQuery().getMessage().getChatId().toString();
            int messageId = update.getCallbackQuery().getMessage().getMessageId();
            long chatId = Long.parseLong(chatIdStr);

            if (data.startsWith("reply_")) {
                String[] parts = data.split("_");
                String playerName = parts[1];
                long playerId = Long.parseLong(parts[2]);
                plugin.getLogger().info("📩 Ответ на репорт от " + playerName + " (ID: " + playerId + ")");
                sendMessage(chatId, "✉️ Введите сообщение для ответа игроку " + playerName + ":");
                deleteMessage(chatIdStr, messageId);
                return;
            }

            if (data.startsWith("confirm_")) {
                String[] parts = data.split("_");
                String action = parts[1];
                String playerName = parts[2];
                String duration = parts.length > 3 ? parts[3] : "навсегда";
                String reason = parts.length > 4 ? parts[4] : "Без причины";
                String issuer = update.getCallbackQuery().getFrom().getId().toString();
                String issuerName = plugin.getCustomSender(Long.parseLong(issuer));
                if (issuerName == null) issuerName = "RCON";

                boolean hidden = data.contains("_hidden_");

                boolean success = false;
                String result = "";

                switch (action) {
                    case "ban":
                        success = punishmentManager.banPlayer(playerName, issuerName, reason, duration, hidden);
                        if (success) {
                            result = "✅ " + playerName + " забанен на " + duration;
                            if (hidden) {
                                result += "\n🔒 СКРЫТОЕ НАКАЗАНИЕ (видят только staff)";
                                notifyStaffOnly("🔒 СКРЫТЫЙ БАН\n" +
                                        "👤 Игрок: " + playerName + "\n" +
                                        "📝 Причина: " + reason + "\n" +
                                        "⏱ Срок: " + duration + "\n" +
                                        "👤 Выдал: " + issuerName);
                            }
                        } else {
                            result = "❌ " + playerName + " уже забанен!";
                        }
                        break;

                    case "mute":
                        success = punishmentManager.mutePlayer(playerName, issuerName, reason, duration, hidden);
                        if (success) {
                            result = "✅ " + playerName + " замучен на " + duration;
                            if (hidden) {
                                result += "\n🔒 СКРЫТОЕ НАКАЗАНИЕ (видят только staff)";
                                notifyStaffOnly("🔒 СКРЫТЫЙ МУТ\n" +
                                        "👤 Игрок: " + playerName + "\n" +
                                        "📝 Причина: " + reason + "\n" +
                                        "⏱ Срок: " + duration + "\n" +
                                        "👤 Выдал: " + issuerName);
                            }
                        } else {
                            result = "❌ " + playerName + " уже замучен!";
                        }
                        break;

                    case "kick":
                        success = punishmentManager.kickPlayer(playerName, issuerName, reason, hidden);
                        if (success) {
                            result = "✅ " + playerName + " кикнут!";
                            if (hidden) {
                                result += "\n🔒 СКРЫТОЕ НАКАЗАНИЕ (видят только staff)";
                                notifyStaffOnly("🔒 СКРЫТЫЙ КИК\n" +
                                        "👤 Игрок: " + playerName + "\n" +
                                        "📝 Причина: " + reason + "\n" +
                                        "👤 Выдал: " + issuerName);
                            }
                        } else {
                            result = "❌ " + playerName + " не найден!";
                        }
                        break;

                    case "unban":
                        success = punishmentManager.unbanPlayer(playerName, issuerName, reason);
                        result = success ? "✅ " + playerName + " разбанен!" : "❌ " + playerName + " не забанен!";
                        break;

                    case "unmute":
                        success = punishmentManager.unmutePlayer(playerName, issuerName, reason);
                        result = success ? "✅ " + playerName + " размучен!" : "❌ " + playerName + " не замучен!";
                        break;
                }

                deleteMessage(chatIdStr, messageId);
                sendMessage(chatId, result);
                return;
            }

            if (data.startsWith("cancel_")) {
                deleteMessage(chatIdStr, messageId);
                sendMessage(chatId, "❌ Операция отменена.");
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
            return;
        }

        // ===== СООБЩЕНИЯ =====
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        String messageText = update.getMessage().getText();
        long userId = update.getMessage().getFrom().getId();
        long chatId = update.getMessage().getChatId();

        plugin.getLogger().info("📩 Получено: " + messageText + " от " + userId);

        if (botBanManager.isBanned(userId)) {
            if (userId != plugin.getOwnerId() && !plugin.isAdmin(userId)) {
                sendMessage(chatId, botBanManager.getBanMessage(userId));
                return;
            }
        }

        // ============================================
        // ==== ПРОВЕРКА ДОСТУПА К БОТУ =====
        // ============================================
        String userGroup = groupManager.getUserGroup(userId);
        if (userGroup == null && !plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
            sendMessage(chatId, "[БОТ] У Вас нет доступа к боту!");
            return;
        }

        if (messageText.equalsIgnoreCase("/start")) { sendWelcome(chatId); return; }
        if (messageText.equalsIgnoreCase("!me")) { sendMe(chatId, userId); return; }
        if (messageText.startsWith("!online") || messageText.startsWith("!онлайн")) { sendOnline(chatId); return; }
        if (messageText.startsWith("!tps") || messageText.startsWith("!тпс")) { sendTps(chatId); return; }
        if (messageText.startsWith("!help") || messageText.startsWith("!помощь")) { sendHelp(chatId, userId); return; }
        if (messageText.startsWith("!info") || messageText.startsWith("!инфо")) { sendInfo(chatId); return; }

        if (!messageText.startsWith("!rcon")) return;

        String command;
        if (messageText.length() > 6) {
            command = messageText.substring(6).trim();
        } else {
            sendHelp(chatId, userId);
            return;
        }

        if (command.isEmpty()) {
            sendHelp(chatId, userId);
            return;
        }

        handleRconCommand(chatId, command, userId);
    }

    // ============================================
    // ==== ОБРАБОТКА RCON КОМАНД =====
    // ============================================
    private void handleRconCommand(long chatId, String command, long userId) {

        if (command.equalsIgnoreCase("rcon") || command.trim().isEmpty()) {
            sendHelp(chatId, userId);
            return;
        }

        // ============================================
        // ==== !rcon global <cmd> =====
        // ============================================
        if (command.startsWith("global ")) {
            String cmd = command.substring(7).trim();
            if (cmd.isEmpty()) {
                sendMessage(chatId, "[БОТ] Использование: !rcon global <команда>");
                return;
            }

            String cmdName = cmd.split(" ")[0];

            // Проверяем доступ к команде
            if (!groupManager.hasPermission(userId, "!rcon global " + cmdName)) {
                sendMessage(chatId, "[БОТ] У вас нет доступа к данной команде!");
                plugin.getLogger().info("❌ Доступ запрещён: " + userId + " -> " + cmdName);
                return;
            }

            // ============================================
            // ==== КАСТОМНЫЕ КОМАНДЫ (ОБРАБОТКА В БОТЕ) =====
            // ============================================
            if (cmd.startsWith("checkban ")) {
                handleCheckBan(chatId, cmd);
                return;
            }
            if (cmd.startsWith("checkmute ")) {
                handleCheckMute(chatId, cmd);
                return;
            }
            if (cmd.startsWith("messageall ")) {
                handleMessageAll(chatId, cmd, userId);
                return;
            }
            if (cmd.startsWith("bc ") || cmd.startsWith("bcast ")) {
                handleBroadcast(chatId, cmd, userId);
                return;
            }
            if (cmd.startsWith("banlist")) {
                handleBanList(chatId, cmd);
                return;
            }
            if (cmd.startsWith("mutelist")) {
                handleMuteList(chatId, cmd);
                return;
            }
            if (cmd.startsWith("shist ") || cmd.startsWith("hist ")) {
                handleShist(chatId, cmd);
                return;
            }
            if (cmd.startsWith("logs ")) {
                handleLogs(chatId, cmd);
                return;
            }
            if (cmd.startsWith("tex ")) {
                handleTex(chatId, cmd, userId);
                return;
            }
            // ===== НОВАЯ КОМАНДА PEX USER =====
            if (cmd.startsWith("pex user ")) {
                handlePexUser(chatId, cmd);
                return;
            }

            // ============================================
            // ==== НАКАЗАНИЯ (ban, mute, kick, unban, unmute) =====
            // ============================================
            if (cmd.startsWith("ban ") || cmd.startsWith("mute ") || cmd.startsWith("kick ") ||
                cmd.startsWith("unban ") || cmd.startsWith("unmute ")) {
                handlePunishment(chatId, cmd, userId);
                return;
            }

            // Остальные команды на сервер
            executeServerCommand(chatId, cmd, userId);
            return;
        }

        sendHelp(chatId, userId);
    }

    // ============================================
    // ==== ОБРАБОТКА НАКАЗАНИЙ =====
    // ============================================
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
        if (issuer == null) issuer = "RCON";

        boolean success = false;
        String result = "";

        switch (action) {
            case "ban":
                success = punishmentManager.banPlayer(playerName, issuer, reason, duration, hidden);
                result = success ? "✅ Игрок " + playerName + " забанен на " + duration : "❌ " + playerName + " уже забанен!";
                if (success && hidden) {
                    result += "\n🔒 СКРЫТОЕ НАКАЗАНИЕ";
                    notifyStaffOnly("🔒 СКРЫТЫЙ БАН\n" +
                            "👤 Игрок: " + playerName + "\n" +
                            "📝 Причина: " + reason + "\n" +
                            "⏱ Срок: " + duration + "\n" +
                            "👤 Выдал: " + issuer);
                }
                break;

            case "mute":
                success = punishmentManager.mutePlayer(playerName, issuer, reason, duration, hidden);
                result = success ? "✅ Игрок " + playerName + " замучен на " + duration : "❌ " + playerName + " уже замучен!";
                if (success && hidden) {
                    result += "\n🔒 СКРЫТОЕ НАКАЗАНИЕ";
                    notifyStaffOnly("🔒 СКРЫТЫЙ МУТ\n" +
                            "👤 Игрок: " + playerName + "\n" +
                            "📝 Причина: " + reason + "\n" +
                            "⏱ Срок: " + duration + "\n" +
                            "👤 Выдал: " + issuer);
                }
                break;

            case "kick":
                success = punishmentManager.kickPlayer(playerName, issuer, reason, hidden);
                result = success ? "✅ Игрок " + playerName + " кикнут!" : "❌ " + playerName + " не найден!";
                if (success && hidden) {
                    result += "\n🔒 СКРЫТОЕ НАКАЗАНИЕ";
                    notifyStaffOnly("🔒 СКРЫТЫЙ КИК\n" +
                            "👤 Игрок: " + playerName + "\n" +
                            "📝 Причина: " + reason + "\n" +
                            "👤 Выдал: " + issuer);
                }
                break;

            case "unban":
                success = punishmentManager.unbanPlayer(playerName, issuer, reason);
                result = success ? "✅ Игрок " + playerName + " разбанен!" : "❌ " + playerName + " не забанен!";
                break;

            case "unmute":
                success = punishmentManager.unmutePlayer(playerName, issuer, reason);
                result = success ? "✅ Игрок " + playerName + " размучен!" : "❌ " + playerName + " не замучен!";
                break;
        }

        sendMessage(chatId, "[БОТ] " + result);
    }

    // ============================================
    // ==== checkban (СТАРЫЙ ФОРМАТ) =====
    // ============================================
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
        response += " Сервер: выживание\n";
        response += " Выдал: " + issuer + "\n";
        response += " IP: нет, скрыто: нет, навсегда: " + (entry.duration.equals("навсегда") ? "да" : "нет");

        sendMessage(chatId, response);
    }

    // ============================================
    // ==== checkmute (СТАРЫЙ ФОРМАТ) =====
    // ============================================
    private void handleCheckMute(long chatId, String cmd) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global checkmute <ник>");
            return;
        }
        String playerName = parts[1];

        boolean isMuted = punishmentManager.isMuted(playerName);
        if (!isMuted) {
            sendMessage(chatId, "[БОТ] Игрок " + playerName + " не замучен.");
            return;
        }

        String issuer = punishmentManager.getMuteIssuer(playerName);
        String reason = punishmentManager.getMuteReason(playerName);
        String expiry = punishmentManager.getMuteExpiry(playerName);
        PunishmentManager.HistoryEntry entry = punishmentManager.getLastMute(playerName);

        String response = "[БОТ] Ответ сервера:\n";
        response += "----- " + playerName + " -----\n";
        response += " Причина: " + reason + "\n";
        response += " Время: " + punishmentManager.getFormattedDateTime(entry.timestamp) + "\n";
        response += " Истекает: " + expiry + "\n";
        response += " Сервер: выживание\n";
        response += " Выдал: " + issuer + "\n";
        response += " IP: нет, скрыто: нет, навсегда: " + (entry.duration.equals("навсегда") ? "да" : "нет");

        sendMessage(chatId, response);
    }

    // ============================================
    // ==== pex user (НОВАЯ КОМАНДА) =====
    // ============================================
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
                    if (isOnline && target != null) {
                        group = permission.getPrimaryGroup(target);
                    } else {
                        group = "офлайн";
                    }
                }
            } catch (Exception e) {
                group = "неизвестно (Vault)";
            }

        } catch (Exception e) {
            sendMessage(chatId, "[БОТ] ❌ Ошибка: " + e.getMessage());
            return;
        }

        String response = "[БОТ] Ответ сервера:\n";
        response += "----- " + playerName + " -----\n";
        response += " Группа: " + group + "\n";
        response += " OP: " + (isOp ? "да" : "нет") + "\n";
        response += " Белый список: " + (isWhitelisted ? "да" : "нет") + "\n";
        response += " UUID: " + uuid;

        sendMessage(chatId, response);
    }

    // ============================================
    // ==== messageall =====
    // ============================================
    private void handleMessageAll(long chatId, String cmd, long userId) {
        String message = cmd.substring(11);
        if (message.trim().isEmpty()) {
            sendMessage(chatId, "[БОТ] Введите текст сообщения!");
            return;
        }
        String senderName = plugin.getCustomSender(userId);
        if (senderName == null) senderName = "RCON";

        int count = 0;
        for (long id : hiddenViewers) {
            try { sendMessage(id, "📢 " + message); count++; } catch (Exception e) {}
        }
        sendMessage(chatId, "[БОТ] Сообщение отправлено " + count + " пользователям!");
    }

    // ============================================
    // ==== bc / bcast =====
    // ============================================
    private void handleBroadcast(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global bc <сообщение>");
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        String sender = plugin.getCustomSender(userId);
        if (sender == null) sender = "RCON";

        String format = "§6[Объявление] §f" + message + " §7(Пишет: " + sender + "§7)";
        Bukkit.broadcastMessage(format);
        sendMessage(chatId, "[БОТ] " + format.replaceAll("§[0-9a-fk-or]", ""));
    }

    // ============================================
    // ==== banlist =====
    // ============================================
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
            response.append("[БОТ] Список банов (Страница ").append(page).append("/").append(totalPages).append(")");
            for (String ban : bans) {
                response.append("\n\n").append(ban);
            }
            if (totalPages > 1) {
                response.append("\n\n📌 Используй: !rcon global banlist ").append(page + 1);
            }
            sendMessage(chatId, response.toString());
        }
    }

    // ============================================
    // ==== mutelist =====
    // ============================================
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
            sendMessage(chatId, "[БОТ] Список мутов пуст.");
        } else {
            StringBuilder response = new StringBuilder();
            response.append("[БОТ] Список мутов (Страница ").append(page).append("/").append(totalPages).append(")");
            for (String mute : mutes) {
                response.append("\n\n").append(mute);
            }
            if (totalPages > 1) {
                response.append("\n\n📌 Используй: !rcon global mutelist ").append(page + 1);
            }
            sendMessage(chatId, response.toString());
        }
    }

    // ============================================
    // ==== shist / hist =====
    // ============================================
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
        response.append("[БОТ] История наказаний игрока ").append(target).append(" (Записей: ").append(history.size()).append(")");

        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        int count = 0;
        for (PunishmentManager.HistoryEntry entry : history) {
            if (count >= 20) {
                response.append("\n\n... и ещё ").append(history.size() - 20).append(" записей");
                break;
            }
            String timeAgo = punishmentManager.getTimeAgo(entry.timestamp);
            String status = entry.type.equals("ban") ?
                (punishmentManager.isBanned(target) ? "[Активен]" : "[Истек]") :
                (punishmentManager.isMuted(target) ? "[Активен]" : "[Истек]");
            String actionName = entry.getActionName();

            response.append("\n\n - ").append(timeAgo).append(" -");
            response.append("\n   ").append(target).append(" был ").append(actionName)
                    .append(" на ").append(entry.duration).append(" ")
                    .append(entry.issuer).append(": ").append(entry.reason).append(" ").append(status);
            count++;
        }
        sendMessage(chatId, response.toString());
    }

    // ============================================
    // ==== logs =====
    // ============================================
    private void handleLogs(long chatId, String cmd) {
        String[] args = cmd.split(" ");
        SendMessage response = logsCommand.handleLogs(chatId, args);
        try { execute(response); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    // ============================================
    // ==== tex =====
    // ============================================
    private void handleTex(long chatId, String cmd, long userId) {
        if (!groupManager.isAdmin(userId) && !groupManager.isOwner(userId)) {
            sendMessage(chatId, "[БОТ] У вас нет доступа к данной команде!");
            return;
        }
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global tex on/off");
            return;
        }
        boolean state = parts[1].equalsIgnoreCase("on");
        plugin.getConfig().set("maintenance", state);
        plugin.saveConfig();
        sendMessage(chatId, "[БОТ] Техработы " + (state ? "ВКЛЮЧЕНЫ" : "ВЫКЛЮЧЕНЫ"));
    }

    // ============================================
    // ==== ОТПРАВКА КОМАНДЫ НА СЕРВЕР =====
    // ============================================
    private void executeServerCommand(long chatId, String command, long userId) {
        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON";

        final long finalChatId = chatId;
        final String finalCommand = command;
        final String finalIssuer = issuer;

        final int[] tempMsgId = {0};
        try {
            SendMessage temp = new SendMessage();
            temp.setChatId(String.valueOf(chatId));
            temp.setText("[БОТ] ⏳ Выполняю команду на сервере...");
            var sent = execute(temp);
            tempMsgId[0] = sent.getMessageId();
        } catch (Exception e) {}

        final int finalTempMsgId = tempMsgId[0];

        Bukkit.getScheduler().runTask(plugin, () -> {
            String response = commandExecutor.executeCommand(finalCommand, finalIssuer);
            if (finalTempMsgId != 0) {
                deleteMessage(String.valueOf(finalChatId), finalTempMsgId);
            }
            sendMessage(finalChatId, "[БОТ] " + response);
        });
    }

    // ============================================
    // ==== ПОДТВЕРЖДЕНИЕ НАКАЗАНИЯ =====
    // ============================================
    private void sendConfirmationRequest(long chatId, String action, String playerName,
                                         String duration, String reason, long userId, boolean hidden) {
        String actionName = "";
        switch (action) {
            case "ban": actionName = "забанить"; break;
            case "mute": actionName = "замутить"; break;
            case "kick": actionName = "кикнуть"; break;
            case "unban": actionName = "разбанить"; break;
            case "unmute": actionName = "размутить"; break;
        }

        String message = "⚠️ Подтвердить " + actionName + " игрока " + playerName + "?\n" +
                "📝 Причина: " + reason + "\n" +
                "⏱ Срок: " + duration;
        if (hidden) {
            message += "\n🔒 СКРЫТОЕ НАКАЗАНИЕ (видят только staff)";
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        InlineKeyboardButton confirmBtn = new InlineKeyboardButton();
        confirmBtn.setText("✅ Да");
        String hiddenFlag = hidden ? "_hidden_" : "";
        confirmBtn.setCallbackData("confirm_" + action + "_" + playerName + "_" + duration + "_" + reason + hiddenFlag);

        InlineKeyboardButton cancelBtn = new InlineKeyboardButton();
        cancelBtn.setText("❌ Отмена");
        cancelBtn.setCallbackData("cancel_" + System.currentTimeMillis());

        row.add(confirmBtn);
        row.add(cancelBtn);
        rows.add(row);
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("[БОТ] ⚠️ " + message);
        msg.setReplyMarkup(markup);

        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    // ============================================
    // ==== ПОМОЩЬ ПО КОМАНДАМ =====
    // ============================================
    private void sendHelp(long chatId, long userId) {
        String group = groupManager.getUserGroup(userId);
        List<String> availableCmds = groupManager.getAvailableCommands(userId);

        StringBuilder response = new StringBuilder();
        response.append("[БОТ] Использование команды - !rcon [сервер] [команда]\n\n");
        response.append("Доступные сервера и команды:\n");
        response.append(" -global (");

        if (availableCmds.isEmpty()) {
            response.append("нет доступа");
        } else {
            response.append(String.join(", ", availableCmds));
        }
        response.append(")\n");

        if (group != null && (group.equals("admin") || group.equals("owner") || group.equals("leader"))) {
            response.append(" - core (нет доступа)\n");
            response.append(" - bw (history, dupeip, unmute, shist, unban, checkban, mute, shistory, checkmute, warn, checkwarn, banip, banlist, unwarn, hist, staffhist, staffhistory, ban, alts, kick, checkalts, iphist)\n");
            response.append(" - grief (нет доступа)");
        }

        sendMessage(chatId, response.toString());
    }

    // ============================================
    // ==== ВСПОМОГАТЕЛЬНЫЙ МЕТОД =====
    // ============================================

    private long parseTimeToMillis(String time) {
        if (time == null) return Long.MAX_VALUE;
        char unit = time.charAt(time.length() - 1);
        long value = Long.parseLong(time.substring(0, time.length() - 1));
        switch (unit) {
            case 's': return value * 1000;
            case 'm': return value * 60 * 1000;
            case 'h': return value * 60 * 60 * 1000;
            case 'd': return value * 24 * 60 * 60 * 1000;
            case 'w': return value * 7 * 24 * 60 * 60 * 1000;
            case 'M': return value * 30L * 24 * 60 * 60 * 1000;
            case 'y': return value * 365L * 24 * 60 * 60 * 1000;
            default: return Long.MAX_VALUE;
        }
    }

    // ============================================
    // ==== ПАГИНАЦИЯ =====
    // ============================================
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
        response.append("[БОТ] ").append(title).append(" (Страница ").append(page).append("/").append(totalPages).append(")");

        for (String item : pageItems) {
            response.append("\n\n").append(item);
        }
        response.append("\n\n📊 Всего записей: ").append(items.size());

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        if (page > 1) {
            InlineKeyboardButton prevBtn = new InlineKeyboardButton();
            prevBtn.setText("⬅️ Назад");
            prevBtn.setCallbackData("page_" + type + "_" + playerName + "_" + (page - 1));
            row.add(prevBtn);
        }
        if (page < totalPages) {
            InlineKeyboardButton nextBtn = new InlineKeyboardButton();
            nextBtn.setText("Вперёд ➡️");
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
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    // ============================================
    // ==== ОТПРАВКА СООБЩЕНИЙ =====
    // ============================================
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

    // ============================================
    // ==== ИГРОВЫЕ КОМАНДЫ =====
    // ============================================
    private void sendOnline(long chatId) {
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        StringBuilder players = new StringBuilder();
        for (Player p : Bukkit.getOnlinePlayers()) {
            players.append("• ").append(p.getName()).append("\n");
        }
        sendMessage(chatId, "[БОТ] Онлайн: " + online + "/" + max + "\n\n" + players);
    }

    private void sendTps(long chatId) {
        double tps = Bukkit.getTPS()[0];
        String status = tps >= 20 ? "Отлично" : tps >= 18 ? "Хорошо" : tps >= 15 ? "Средне" : "Плохо";
        sendMessage(chatId, "[БОТ] TPS: " + String.format("%.2f", tps) + "\nСтатус: " + status);
    }

    private void sendInfo(long chatId) {
        sendMessage(chatId, "[БОТ] Информация о сервере\nВерсия: " + Bukkit.getVersion() +
                "\nИгроки: " + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
    }

    private void sendWelcome(long chatId) {
        sendMessage(chatId, "[БОТ] Добро пожаловать!\nИспользуй !help для списка команд");
    }

    private void sendMe(long chatId, long userId) {
        String group = groupManager.getUserGroup(userId);
        String groupName = group != null ? group : "Нет группы";
        String isAdmin = plugin.isAdmin(userId) ? "Да" : "Нет";
        boolean isBanned = botBanManager.isBanned(userId);

        String response = "[БОТ] Информация о вас\n" +
                "ID: " + userId + "\n" +
                "Группа: " + groupName + "\n" +
                "Админ: " + isAdmin + "\n" +
                "Бан в боте: " + (isBanned ? "Да" : "Нет");

        if (isBanned) {
            BotBanManager.BanData ban = botBanManager.getBanData(userId);
            response += "\nПричина: " + ban.reason;
        }
        sendMessage(chatId, response);
    }
}
