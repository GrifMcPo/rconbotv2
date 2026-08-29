package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.BusinessMessage;
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
    private final AuthManager authManager;

    private final List<Long> hiddenViewers = new ArrayList<>();
    private final Set<Long> knownUsers = new HashSet<>();
    private final Map<Long, String> pendingUnlinkRequests = new HashMap<>();

    public TelegramBotHandler(String token, TelegramConsoleBot plugin, PlayerManager playerManager,
                              CommandLogger commandLogger, LogsCommand logsCommand,
                              CommandExecutor commandExecutor, PunishmentManager punishmentManager,
                              BotBanManager botBanManager, GroupManager groupManager,
                              AuthManager authManager) {
        this.botToken = token;
        this.plugin = plugin;
        this.playerManager = playerManager;
        this.commandLogger = commandLogger;
        this.logsCommand = logsCommand;
        this.commandExecutor = commandExecutor;
        this.punishmentManager = punishmentManager;
        this.botBanManager = botBanManager;
        this.groupManager = groupManager;
        this.authManager = authManager;
        loadHiddenViewers();
        loadKnownUsers();
        
        plugin.getLogger().info("🤖 TelegramBotHandler инициализирован!");
        plugin.getLogger().info("📌 Версия библиотеки: 6.9.7.1 (поддерживает Business API)");
        plugin.getLogger().info("💡 Для бизнес-команд используйте: .rcon <команда>");
        plugin.getLogger().info("🧪 Для проверки бизнес-API используйте: .test");
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
        // Логируем ВСЁ, что приходит
        plugin.getLogger().info("🔍 === НОВОЕ ОБНОВЛЕНИЕ ===");
        plugin.getLogger().info("🔍 Тип: " + update.getClass().getSimpleName());
        
        // Проверяем, есть ли бизнес-сообщение
        if (update.hasBusinessMessage()) {
            plugin.getLogger().info("✅ update.hasBusinessMessage() = TRUE!");
            BusinessMessage businessMessage = update.getBusinessMessage();
            if (businessMessage != null) {
                plugin.getLogger().info("📩 БИЗНЕС-СООБЩЕНИЕ ПОЛУЧЕНО!");
                handleBusinessMessage(businessMessage);
                return;
            } else {
                plugin.getLogger().info("⚠️ businessMessage = null");
            }
        } else {
            plugin.getLogger().info("❌ update.hasBusinessMessage() = FALSE");
        }

        // Проверяем, есть ли callback-запрос
        if (update.hasCallbackQuery()) {
            plugin.getLogger().info("📌 Обработка callbackQuery");
            handleCallbackQuery(update);
            return;
        }

        // Проверяем, есть ли обычное сообщение
        if (!update.hasMessage()) {
            plugin.getLogger().info("⏭️ Нет сообщения (ни бизнес, ни обычного)");
            return;
        }

        if (!update.getMessage().hasText()) {
            plugin.getLogger().info("⏭️ Сообщение без текста");
            return;
        }

        String messageText = update.getMessage().getText().trim();
        long userId = update.getMessage().getFrom().getId();
        long chatId = update.getMessage().getChatId();

        saveKnownUser(userId);
        plugin.getLogger().info("📩 Обычное сообщение от " + userId + ": " + messageText);

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

        if (messageText.equalsIgnoreCase("/console")) {
            sendConsoleLogs(chatId);
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

        if (messageText.equalsIgnoreCase("/блокировка") || messageText.equalsIgnoreCase("!блокировка")) {
            handleBlock(chatId, userId);
            return;
        }

        if (messageText.equalsIgnoreCase("/кик") || messageText.equalsIgnoreCase("!кик")) {
            handleKick(chatId, userId);
            return;
        }

        if (messageText.equalsIgnoreCase("/отвязать") || messageText.equalsIgnoreCase("!отвязать")) {
            handleUnlinkRequest(chatId, userId);
            return;
        }

        if (messageText.startsWith("/привязать") || messageText.startsWith("!привязать")) {
            String[] args = messageText.split(" ");
            handleLink(chatId, args, userId);
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

    // ================================================================
    // ⭐ КОМАНДА /console — ПОКАЗЫВАЕТ СТАТУС BUSINESS API
    // ================================================================

    private void sendConsoleLogs(long chatId) {
        String msg = "[БОТ] 📋 === СТАТУС BUSINESS API ===\n\n" +
                "🔍 Версия библиотеки: 6.9.7.1\n" +
                "📌 Поддержка Business API: ДА\n" +
                "📩 Бизнес-сообщения получены: " + (plugin.businessMessagesReceived > 0 ? "✅ ДА (" + plugin.businessMessagesReceived + ")" : "❌ НЕТ") + "\n" +
                "🔗 business_connection_id: " + (plugin.lastConnectionId != null ? "✅ " + plugin.lastConnectionId : "❌ НЕТ") + "\n\n" +
                "💡 Для проверки:\n" +
                "1. Напишите .test в чате с собеседником\n" +
                "2. Посмотрите логи сервера\n" +
                "3. Если в логах нет 'БИЗНЕС-СООБЩЕНИЕ' — проблема в Telegram API";
        sendMessage(chatId, msg);
    }

    // ================================================================
    // ⭐ ОБРАБОТКА БИЗНЕС-СООБЩЕНИЙ
    // ================================================================

    private void handleBusinessMessage(BusinessMessage businessMessage) {
        try {
            plugin.businessMessagesReceived++;
            plugin.getLogger().info("✅✅✅ handleBusinessMessage() ВЫЗВАН! ✅✅✅");
            
            String text = businessMessage.getText();
            if (text == null || text.trim().isEmpty()) {
                plugin.getLogger().info("⏭️ Пустое сообщение");
                return;
            }

            long userId = businessMessage.getFrom().getId();
            long chatId = businessMessage.getChat().getId();
            int messageId = businessMessage.getMessageId();
            String connectionId = businessMessage.getBusinessConnectionId();
            
            if (connectionId != null && !connectionId.isEmpty()) {
                plugin.lastConnectionId = connectionId;
            }

            plugin.getLogger().info("📩 БИЗНЕС-СООБЩЕНИЕ:");
            plugin.getLogger().info("   👤 userId: " + userId);
            plugin.getLogger().info("   💬 chatId: " + chatId);
            plugin.getLogger().info("   📝 messageId: " + messageId);
            plugin.getLogger().info("   🔗 connectionId: " + connectionId);
            plugin.getLogger().info("   📄 text: " + text);

            // Проверка: только админы
            if (!plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
                plugin.getLogger().info("⛔ Не админ: " + userId);
                return;
            }

            // ⭐ ТЕСТОВАЯ КОМАНДА: .test
            if (text.equalsIgnoreCase(".test")) {
                plugin.getLogger().info("🧪 Тестовая команда .test получена!");
                String response = "✅ БОТ РАБОТАЕТ!\n\n" +
                        "📩 Ваше сообщение: " + text + "\n" +
                        "🔗 connectionId: " + (connectionId != null ? connectionId : "ОТСУТСТВУЕТ") + "\n" +
                        "👤 Ваш ID: " + userId + "\n" +
                        "💬 chatId: " + chatId + "\n" +
                        "🕐 Время: " + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date()) + "\n\n" +
                        "✅ Business API работает!";
                sendBusinessMessage(chatId, response, connectionId);
                return;
            }

            // Проверка: команда должна начинаться с "."
            if (!text.startsWith(".")) {
                plugin.getLogger().info("⏭️ Не команда (нет .): " + text);
                return;
            }

            if (connectionId == null || connectionId.isEmpty()) {
                plugin.getLogger().warning("❌ Нет business_connection_id");
                sendBusinessMessage(chatId, "[БОТ] ❌ Нет подключения к бизнес-аккаунту!", "");
                return;
            }

            // Удаляем команду
            deleteBusinessMessage(connectionId, messageId);

            String cmd = text.substring(1).trim();
            plugin.getLogger().info("⚙️ Команда: " + cmd);

            // Проверяем, что это rcon команда
            if (!cmd.startsWith("rcon ")) {
                sendBusinessMessage(chatId, "[БОТ] Использование: .rcon <команда>", connectionId);
                return;
            }

            String rconCmd = cmd.substring(5).trim();
            if (rconCmd.isEmpty()) {
                sendBusinessMessage(chatId, "[БОТ] Использование: .rcon <команда>", connectionId);
                return;
            }

            // Проверяем права
            String cmdName = rconCmd.split(" ")[0];
            String fullCommand = "!rcon global " + cmdName;
            if (!groupManager.hasPermission(userId, fullCommand) &&
                    !plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
                sendBusinessMessage(chatId, "[БОТ] У вас нет доступа к данной команде!", connectionId);
                return;
            }

            plugin.getLogger().info("✅ ВЫПОЛНЯЕМ RCON: " + rconCmd);

            // Выполняем команду
            final String finalRconCmd = rconCmd;
            final long finalChatId = chatId;
            final String finalConnectionId = connectionId;
            final long finalUserId = userId;

            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    String issuer = plugin.getCustomSender(finalUserId);
                    if (issuer == null) issuer = "RCON@" + finalUserId;
                    
                    commandExecutor.executeCommand(finalRconCmd, issuer);
                    sendBusinessMessage(finalChatId, "[БОТ] ✅ Команда выполнена: " + finalRconCmd, finalConnectionId);
                } catch (Exception e) {
                    plugin.getLogger().warning("⚠️ Ошибка: " + e.getMessage());
                    sendBusinessMessage(finalChatId, "[БОТ] ❌ Ошибка: " + e.getMessage(), finalConnectionId);
                }
            });

        } catch (Exception e) {
            plugin.getLogger().warning("⚠️ Ошибка обработки бизнес-сообщения: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ================================================================
    // ⭐ МЕТОДЫ BUSINESS API
    // ================================================================

    private void deleteBusinessMessage(String connectionId, int messageId) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/deleteBusinessMessages";
            String json = "{\"business_connection_id\":\"" + connectionId + "\",\"message_ids\":[" + messageId + "]}";
            
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build();
            
            java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            plugin.getLogger().info("🗑️ Удалено бизнес-сообщение: " + messageId);
        } catch (Exception e) {
            plugin.getLogger().warning("⚠️ Не удалось удалить сообщение: " + e.getMessage());
        }
    }

    private void sendBusinessMessage(long chatId, String text, String connectionId) {
        try {
            if (connectionId == null || connectionId.isEmpty()) {
                sendMessage(chatId, text);
                return;
            }

            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText(text);
            message.setBusinessConnectionId(connectionId);
            execute(message);
            plugin.getLogger().info("📤 Бизнес-ответ отправлен в чат " + chatId);
        } catch (Exception e) {
            plugin.getLogger().warning("⚠️ Ошибка отправки бизнес-сообщения: " + e.getMessage());
            sendMessage(chatId, text);
        }
    }

    // ================================================================
    // ВСЕ ОСТАЛЬНЫЕ МЕТОДЫ (БЕЗ ИЗМЕНЕНИЙ)
    // ================================================================

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

    private void sendStartMessage(long chatId) {
        String msg = "[БОТ] Приветствую! Это официальный бот GrifMc!\n\n" +
                "Команды:\n" +
                "/помощь - список всех команд\n" +
                "/console - статус Business API\n" +
                "/привязать <ник> <код> - привязать аккаунт к Telegram";
        sendMessage(chatId, msg);
    }

    private void sendHelp(long chatId) {
        String msg = "[БОТ] Вот полный список команд:\n\n" +
                "/блокировка - заблокировать/разблокировать аккаунт\n" +
                "/кик - кикнуть аккаунт с сервера\n" +
                "/отвязать - подать заявку на отвязку аккаунта\n" +
                "/привязать <ник> <код> - привязать аккаунт\n" +
                "/console - статус Business API\n" +
                "/помощь2 - помощь по RCON командам\n" +
                "/id - показать ваш Telegram ID\n\n" +
                "В чатах с собеседниками:\n" +
                ".test - проверка работы бизнес-API\n" +
                ".rcon <команда> - выполнить RCON команду";
        sendMessage(chatId, msg);
    }

    private void sendHelp2(long chatId) {
        String msg = "[БОТ] Помощь по RCON командам:\n\n" +
                "!rcon global ban <ник> <время> <причина> [-s] - забанить игрока\n" +
                "!rcon global mute <ник> <время> <причина> [-s] - замутить игрока\n" +
                "!rcon global kick <ник> <причина> [-s] - кикнуть игрока\n" +
                "!rcon global unban <ник> - разбанить игрока\n" +
                "!rcon global unmute <ник> - размутить игрока\n" +
                "!rcon global banip <ник> <время> <причина> [-s] - забанить IP игрока\n" +
                "!rcon global checkban <ник> - проверить бан\n" +
                "!rcon global checkmute <ник> - проверить мут\n" +
                "!rcon global banlist - список банов\n" +
                "!rcon global mutelist - список мутов\n" +
                "!rcon global logs <ник> [кол-во] - логи команд\n" +
                "!rcon global hist <ник> [кол-во] - история наказаний игрока\n" +
                "!rcon global shist <ник> [кол-во] - наказания выданные игроком\n" +
                "!rcon global dupeip <ник> - поиск по IP\n" +
                "!rcon global pex user <ник> - информация об игроке\n" +
                "!rcon global bc <текст> - объявление в чат\n\n" +
                "В чатах с собеседниками используйте:\n" +
                ".test - проверка бизнес-API\n" +
                ".rcon <команда> - выполнить RCON команду (команда удаляется автоматически)";
        sendMessage(chatId, msg);
    }

    private void handleBlock(long chatId, long userId) {
        String playerName = authManager.getPlayerNameByTelegram(String.valueOf(userId));
        if (playerName == null) {
            sendMessage(chatId, "[БОТ] Ваш Telegram ID не привязан ни к одному аккаунту!");
            return;
        }

        boolean isBlocked = authManager.toggleBlock(playerName);
        if (isBlocked) {
            sendMessage(chatId, "[БОТ] Аккаунт <<" + playerName + ">> был заблокирован!");
            Player player = Bukkit.getPlayer(playerName);
            if (player != null && player.isOnline()) {
                player.kickPlayer("§c&lАккаунт заблокирован через ТГ!");
            }
        } else {
            sendMessage(chatId, "[БОТ] Аккаунт <<" + playerName + ">> был разблокирован!");
        }
    }

    private void handleKick(long chatId, long userId) {
        String playerName = authManager.getPlayerNameByTelegram(String.valueOf(userId));
        if (playerName == null) {
            sendMessage(chatId, "[БОТ] Ваш Telegram ID не привязан ни к одному аккаунту!");
            return;
        }

        Player player = Bukkit.getPlayer(playerName);
        if (player != null && player.isOnline()) {
            player.kickPlayer("§c&lАккаунт был кикнут через ТГ!");
            sendMessage(chatId, "[БОТ] Аккаунт <<" + playerName + ">> был кикнут с сервера!");
        } else {
            sendMessage(chatId, "[БОТ] Аккаунт <<" + playerName + ">> не в сети на сервере!");
        }
    }

    private void handleUnlinkRequest(long chatId, long userId) {
        String playerName = authManager.getPlayerNameByTelegram(String.valueOf(userId));
        if (playerName == null) {
            sendMessage(chatId, "[БОТ] Ваш Telegram ID не привязан ни к одному аккаунту!");
            return;
        }

        pendingUnlinkRequests.put(userId, playerName);
        sendMessage(chatId, "[БОТ] Напишите в этот чат причину отвязки, после ваш аккаунт будет отвязан администратором.");
    }

    private void handleUnlinkReason(long chatId, long userId, String reason) {
        String playerName = pendingUnlinkRequests.remove(userId);
        if (playerName == null) return;

        long ownerId = plugin.getOwnerId();
        String msg = "[БОТ] Новая заявка на отвязку аккаунта!\n" +
                "ID: " + userId + "\n" +
                "Ник аккаунта: " + playerName + "\n" +
                "Причина отвязки: " + reason;
        sendMessage(ownerId, msg);
        sendMessage(chatId, "[БОТ] Заявка на отвязку отправлена администратору!");
    }

    public boolean unlinkByAdmin(String playerName, String adminName) {
        if (!authManager.isLinked(playerName)) {
            return false;
        }

        String telegramId = authManager.getTelegramId(playerName);
        authManager.unlinkAccount(playerName);

        if (telegramId != null) {
            try {
                long chatId = Long.parseLong(telegramId);
                sendMessage(chatId, "[БОТ] Ваш аккаунт был отвязан администратором " + adminName);
            } catch (NumberFormatException e) {}
        }

        return true;
    }

    private void handleLink(long chatId, String[] args, long userId) {
        if (args.length < 3) {
            sendMessage(chatId, "[БОТ] Использование: /привязать <ник> <код>");
            return;
        }

        String playerName = args[1];
        String code = args[2];

        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sendMessage(chatId, "[БОТ] Игрок " + playerName + " не найден на сервере!");
            return;
        }

        if (!authManager.verifyCode(playerName, code)) {
            sendMessage(chatId, "[БОТ] Неверный или просроченный код! Выполните /tg заново.");
            return;
        }

        String existingPlayer = authManager.getPlayerNameByTelegram(String.valueOf(userId));
        if (existingPlayer != null) {
            sendMessage(chatId, "[БОТ] Этот Telegram ID уже привязан к аккаунту " + existingPlayer + "!");
            return;
        }

        if (authManager.isLinked(playerName)) {
            sendMessage(chatId, "[БОТ] Аккаунт " + playerName + " уже привязан к другому Telegram!");
            return;
        }

        String ip = target.getAddress() != null ? target.getAddress().getHostString() : "—";
        boolean success = authManager.linkAccount(playerName, String.valueOf(userId), ip);

        if (success) {
            sendMessage(chatId, "[БОТ] Аккаунт " + playerName + " успешно привязан к вашему Telegram!\n" +
                    "Ваш ID: " + userId + "\n" +
                    "Привязанный IP: " + ip + "\n" +
                    "Сессия действует 5 часов.");
            target.sendMessage("§aВаш аккаунт успешно привязан к Telegram!");
        } else {
            sendMessage(chatId, "[БОТ] Не удалось привязать аккаунт. Попробуйте позже.");
        }
    }

    public void sendAuthRequest(String playerName, String ip) {
        AuthManager.AuthData data = authManager.getAuthData(playerName);
        if (data == null || data.telegramId == null) return;

        long chatId = Long.parseLong(data.telegramId);
        if (data.blocked) {
            Player player = Bukkit.getPlayer(playerName);
            if (player != null && player.isOnline()) {
                player.kickPlayer("§c&lАккаунт заблокирован через ТГ!");
            }
            return;
        }

        String message = "Вход на аккаунт <<" + playerName + ">> с нового IP адреса!\n" +
                "IP Адрес: " + ip + "\n" +
                "Время: " + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        InlineKeyboardButton allowBtn = new InlineKeyboardButton();
        allowBtn.setText("Разрешить");
        allowBtn.setCallbackData("auth_allow_" + playerName + "_" + ip);

        InlineKeyboardButton denyBtn = new InlineKeyboardButton();
        denyBtn.setText("Запретить");
        denyBtn.setCallbackData("auth_deny_" + playerName + "_" + ip);

        row.add(allowBtn);
        row.add(denyBtn);
        rows.add(row);
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(message);
        msg.setReplyMarkup(markup);

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void sendSessionExpiredRequest(String playerName) {
        AuthManager.AuthData data = authManager.getAuthData(playerName);
        if (data == null || data.telegramId == null) return;

        long chatId = Long.parseLong(data.telegramId);
        if (data.blocked) {
            Player player = Bukkit.getPlayer(playerName);
            if (player != null && player.isOnline()) {
                player.kickPlayer("§c&lАккаунт заблокирован через ТГ!");
            }
            return;
        }

        String ip = data.ip;
        String message = "Сессия аккаунта <<" + playerName + ">> истекла!\n" +
                "Текущий IP: " + ip + "\n" +
                "Время: " + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date()) + "\n\n" +
                "Для продолжения игры подтвердите вход.";

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        InlineKeyboardButton allowBtn = new InlineKeyboardButton();
        allowBtn.setText("Разрешить");
        allowBtn.setCallbackData("auth_allow_" + playerName + "_" + ip);

        InlineKeyboardButton denyBtn = new InlineKeyboardButton();
        denyBtn.setText("Запретить");
        denyBtn.setCallbackData("auth_deny_" + playerName + "_" + ip);

        row.add(allowBtn);
        row.add(denyBtn);
        rows.add(row);
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(message);
        msg.setReplyMarkup(markup);

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleAuthAllow(long chatId, String data, int messageId) {
        String[] parts = data.split("_", 4);
        if (parts.length < 4) return;
        String playerName = parts[2];
        String ip = parts[3];

        authManager.updateIp(playerName, ip);
        authManager.refreshSession(playerName);
        authManager.unbanIp(playerName, ip);

        deleteMessage(String.valueOf(chatId), messageId);
        sendMessage(chatId, "[БОТ] Вход на аккаунт <<" + playerName + ">> успешно разрешен!\n" +
                "Новый IP: " + ip + "\n" +
                "Сессия действует 5 часов.");

        Player player = Bukkit.getPlayer(playerName);
        if (player != null && player.isOnline()) {
            player.sendMessage("§aВход разрешен!");
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

        authManager.banIp(playerName, ip);

        deleteMessage(String.valueOf(chatId), messageId);
        sendMessage(chatId, "[БОТ] Вход на аккаунт <<" + playerName + ">> запрещен!\n" +
                "IP: " + ip + " добавлен в черный список на 10 часов.");

        Player player = Bukkit.getPlayer(playerName);
        if (player != null && player.isOnline()) {
            long timeLeft = authManager.getBanTimeLeft(playerName);
            player.kickPlayer("§4&lВаш IP адрес был заблокирован!\n" +
                    "§fПричина: §cЗапрет входа на аккаунт. §7(с бота)\n" +
                    "§fСрок: §c&n" + authManager.formatTimeLeft(timeLeft));
        }
    }

    private void handleRconCommand(long chatId, String cmd, long userId) {
        if (cmd.isEmpty()) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global <команда>");
            return;
        }

        String cmdName = cmd.split(" ")[0];
        String fullCommand = "!rcon global " + cmdName;

        if (!groupManager.hasPermission(userId, fullCommand) &&
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
        if (cmd.startsWith("checkban ")) {
            handleCheckBan(chatId, cmd);
            return;
        }
        if (cmd.startsWith("checkmute ")) {
            handleCheckMute(chatId, cmd);
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
        if (cmd.startsWith("banip ")) {
            handleBanIp(chatId, cmd, userId);
            return;
        }
        if (cmd.startsWith("unbanip ")) {
            handleUnbanIp(chatId, cmd, userId);
            return;
        }
        if (cmd.startsWith("ban ") || cmd.startsWith("mute ") ||
                cmd.startsWith("kick ") || cmd.startsWith("unban ") || cmd.startsWith("unmute ")) {
            handlePunishment(chatId, cmd, userId);
            return;
        }

        executeServerCommand(chatId, cmd, userId);
    }

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
        if (lastArg.equalsIgnoreCase("-s")) {
            hidden = true;
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

        String coloredReason = reason.replace('&', '§');

        boolean success = false;
        String result = "";
        boolean broadcast = !hidden;

        switch (action) {
            case "ban":
                success = punishmentManager.banPlayer(playerName, issuer, reason, duration, hidden, broadcast);
                if (success) {
                    result = "[БОТ] Ответ сервера:\n";
                    if (hidden) {
                        result += "[Скрыто] ❨！❩ Игрок " + issuer + " забанил " + playerName +
                                " на " + duration + " по причине: " + coloredReason + " (глобальный)";
                    } else {
                        result += "❨！❩ Игрок " + issuer + " забанил " + playerName +
                                " на " + duration + " по причине: " + coloredReason + " (глобальный)";
                    }
                } else {
                    result = "[БОТ] " + playerName + " уже забанен!";
                }
                break;

            case "mute":
                success = punishmentManager.mutePlayer(playerName, issuer, reason, duration, hidden, broadcast);
                if (success) {
                    result = "[БОТ] Ответ сервера:\n";
                    if (hidden) {
                        result += "[Скрыто] ❨！❩ Игрок " + issuer + " замутил " + playerName +
                                " на " + duration + " по причине: " + coloredReason + " (глобальный)";
                    } else {
                        result += "❨！❩ Игрок " + issuer + " замутил " + playerName +
                                " на " + duration + " по причине: " + coloredReason + " (глобальный)";
                    }
                } else {
                    result = "[БОТ] " + playerName + " уже замучен!";
                }
                break;

            case "kick":
                success = punishmentManager.kickPlayer(playerName, issuer, reason, hidden, broadcast);
                if (success) {
                    result = "[БОТ] Ответ сервера:\n";
                    if (hidden) {
                        result += "[Скрыто] ❨！❩ Игрок " + issuer + " кикнул " + playerName +
                                " по причине: " + coloredReason + " (глобальный)";
                    } else {
                        result += "❨！❩ Игрок " + issuer + " кикнул " + playerName +
                                " по причине: " + coloredReason + " (глобальный)";
                    }
                } else {
                    result = "[БОТ] " + playerName + " не найден!";
                }
                break;

            case "unban":
                success = punishmentManager.unbanPlayer(playerName, issuer, reason, broadcast);
                if (success) {
                    result = "[БОТ] Ответ сервера:\n❨！❩ Игрок " + issuer + " разбанил " + playerName +
                            " по причине: " + coloredReason + " (глобальный)";
                } else {
                    result = "[БОТ] " + playerName + " не забанен!";
                }
                break;

            case "unmute":
                success = punishmentManager.unmutePlayer(playerName, issuer, reason, broadcast);
                if (success) {
                    result = "[БОТ] Ответ сервера:\n❨！❩ Игрок " + issuer + " размутил " + playerName +
                            " по причине: " + coloredReason + " (глобальный)";
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

        String isHidden = entry.hidden ? "да" : "нет";
        String isPermanent = entry.duration.equals("навсегда") ? "да" : "нет";
        String ipInfo = "нет";

        String response = "[БОТ] Ответ сервера:\n";
        response += "----- " + playerName + " -----\n";
        response += " Причина: " + reason + "\n";
        response += " Время: " + punishmentManager.getFormattedDateTime(entry.timestamp) + "\n";
        response += " Истекает: " + expiry + "\n";
        response += " Сервер: выживание\n";
        response += " Выдал: " + issuer + "\n";
        response += " IP: " + ipInfo + ", скрыто: " + isHidden + ", навсегда: " + isPermanent;

        sendMessage(chatId, response);
    }

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

        String isHidden = entry.hidden ? "да" : "нет";
        String isPermanent = entry.duration.equals("навсегда") ? "да" : "нет";

        String response = "[БОТ] Ответ сервера:\n";
        response += "----- " + playerName + " -----\n";
        response += " Причина: " + reason + "\n";
        response += " Время: " + punishmentManager.getFormattedDateTime(entry.timestamp) + "\n";
        response += " Истекает: " + expiry + "\n";
        response += " Сервер: выживание\n";
        response += " Выдал: " + issuer + "\n";
        response += " IP: нет, скрыто: " + isHidden + ", навсегда: " + isPermanent;

        sendMessage(chatId, response);
    }

    private void handleBanIp(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 4) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global banip <ник> <время> <причина> [-s]");
            return;
        }

        boolean hidden = false;
        String lastArg = parts[parts.length - 1];
        if (lastArg.equalsIgnoreCase("-s")) {
            hidden = true;
            parts = Arrays.copyOf(parts, parts.length - 1);
        }

        String playerName = parts[1];
        String duration = parts[2];
        String reason = String.join(" ", Arrays.copyOfRange(parts, 3, parts.length));
        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON@" + userId;

        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sendMessage(chatId, "[БОТ] Игрок " + playerName + " не найден на сервере!");
            return;
        }

        boolean success = punishmentManager.banIp(playerName, issuer, reason, duration, hidden);
        if (success) {
            String coloredReason = reason.replace('&', '§');
            sendMessage(chatId, "[БОТ] IP игрока " + playerName + " забанен на " + duration + " по причине: " + coloredReason);
        } else {
            sendMessage(chatId, "[БОТ] Не удалось забанить IP " + playerName);
        }
    }

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
            String coloredReason = reason.replace('&', '§');
            sendMessage(chatId, "[БОТ] IP игрока " + playerName + " разбанен по причине: " + coloredReason);
        } else {
            sendMessage(chatId, "[БОТ] IP игрока " + playerName + " не забанен!");
        }
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
            response.append("[БОТ] Список мутов (Страница ").append(page).append("/").append(totalPages).append(")\n");
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
                    result = "[БОТ] " + issuer + " забанил " + playerName + " на " + duration + " по причине: " + reason;
                    if (hidden) result += " (СКРЫТО)";
                } else {
                    result = "[БОТ] " + playerName + " уже забанен!";
                }
                break;

            case "mute":
                success = punishmentManager.mutePlayer(playerName, issuer, reason, duration, hidden, !hidden);
                if (success) {
                    result = "[БОТ] " + issuer + " замутил " + playerName + " на " + duration + " по причине: " + reason;
                    if (hidden) result += " (СКРЫТО)";
                } else {
                    result = "[БОТ] " + playerName + " уже замучен!";
                }
                break;

            case "kick":
                success = punishmentManager.kickPlayer(playerName, issuer, reason, hidden, !hidden);
                if (success) {
                    result = "[БОТ] " + issuer + " кикнул " + playerName + " по причине: " + reason;
                    if (hidden) result += " (СКРЫТО)";
                } else {
                    result = "[БОТ] " + playerName + " не найден!";
                }
                break;

            case "unban":
                success = punishmentManager.unbanPlayer(playerName, issuer, reason, true);
                if (success) {
                    result = "[БОТ] " + issuer + " разбанил " + playerName + " по причине: " + reason;
                } else {
                    result = "[БОТ] " + playerName + " не забанен!";
                }
                break;

            case "unmute":
                success = punishmentManager.unmutePlayer(playerName, issuer, reason, true);
                if (success) {
                    result = "[БОТ] " + issuer + " размутил " + playerName + " по причине: " + reason;
                } else {
                    result = "[БОТ] " + playerName + " не замучен!";
                }
                break;

            default:
                result = "Неизвестное действие!";
                break;
        }

        deleteMessage(String.valueOf(chatId), messageId);
        sendMessage(chatId, result);
    }

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
