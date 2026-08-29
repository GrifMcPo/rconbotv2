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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        plugin.getLogger().info("📌 Бот готов к приёму команд!");
        plugin.getLogger().info("💡 Для бизнес-команд (в чатах с собеседниками) используйте: .rcon <команда>");
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

        // ⭐ НОВОЕ: Проверка на бизнес-сообщение (чат с собеседником)
        try {
            String json = update.toString();
            if (json.contains("\"business_message\"") || json.contains("\"business_connection_id\"")) {
                plugin.getLogger().info("📩 Обнаружено бизнес-сообщение!");
                handleBusinessMessage(update);
                return;
            }
        } catch (Exception e) {
            // Игнорируем ошибки парсинга
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
    // ⭐ НОВЫЙ БЛОК: ОБРАБОТКА БИЗНЕС-СООБЩЕНИЙ (ЧАТЫ С СОБЕСЕДНИКАМИ)
    // ================================================================

    private void handleBusinessMessage(Update update) {
        try {
            String json = update.toString();
            JsonNode root = objectMapper.readTree(json);
            
            JsonNode businessMessage = root.path("business_message");
            if (businessMessage.isMissingNode()) {
                JsonNode message = root.path("message");
                if (message.has("business_connection_id")) {
                    businessMessage = message;
                    plugin.getLogger().info("📩 Найдено business_connection_id в message");
                } else {
                    plugin.getLogger().info("⏭️ Пропускаем: не бизнес-сообщение");
                    return;
                }
            }

            String text = businessMessage.path("text").asText();
            if (text == null || text.trim().isEmpty()) {
                plugin.getLogger().info("⏭️ Пустое сообщение");
                return;
            }

            long userId = businessMessage.path("from").path("id").asLong();
            long chatId = businessMessage.path("chat").path("id").asLong();
            int messageId = businessMessage.path("message_id").asInt();
            String connectionId = businessMessage.path("business_connection_id").asText();

            if (connectionId == null || connectionId.isEmpty()) {
                connectionId = root.path("business_connection_id").asText();
            }

            plugin.getLogger().info("📩 Бизнес-сообщение от " + userId + ": " + text);
            plugin.getLogger().info("   🔗 connectionId: " + connectionId);

            if (!plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
                plugin.getLogger().info("⛔ Не админ: " + userId);
                return;
            }

            if (!text.startsWith(".")) {
                plugin.getLogger().info("⏭️ Не команда (нет .): " + text);
                return;
            }

            if (connectionId == null || connectionId.isEmpty()) {
                plugin.getLogger().warning("❌ Нет business_connection_id для " + userId);
                sendBusinessMessage(chatId, "[БОТ] ❌ Ошибка: нет подключения к бизнес-аккаунту!", "");
                return;
            }

            deleteBusinessMessage(connectionId, messageId);
            plugin.getLogger().info("🗑️ Удалено сообщение с командой (ID: " + messageId + ")");

            String cmd = text.substring(1).trim();
            plugin.getLogger().info("⚙️ Команда без точки: " + cmd);

            handleBusinessCommand(chatId, cmd, userId, connectionId);

        } catch (Exception e) {
            plugin.getLogger().warning("⚠️ Ошибка обработки бизнес-сообщения: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleBusinessCommand(long chatId, String cmd, long userId, String connectionId) {
        plugin.getLogger().info("⚙️ Бизнес-команда: " + cmd + " от " + userId);

        if (!plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
            plugin.getLogger().info("⛔ Нет прав у " + userId);
            sendBusinessMessage(chatId, "[БОТ] ❌ У вас нет доступа!", connectionId);
            return;
        }

        if (!cmd.startsWith("rcon ")) {
            plugin.getLogger().info("⏭️ Не rcon команда: " + cmd);
            sendBusinessMessage(chatId, 
                "[БОТ] Использование: .rcon <команда>\n" +
                "Пример: .rcon ban Steve 1d Спам", 
                connectionId);
            return;
        }

        String rconCmd = cmd.substring(5).trim();
        if (rconCmd.isEmpty()) {
            plugin.getLogger().info("⏭️ Пустая rcon команда");
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon <команда>", connectionId);
            return;
        }

        plugin.getLogger().info("✅ Выполняем RCON: " + rconCmd);

        String cmdName = rconCmd.split(" ")[0];
        String fullCommand = "!rcon global " + cmdName;
        if (!groupManager.hasPermission(userId, fullCommand) &&
                !plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
            sendBusinessMessage(chatId, "[БОТ] У вас нет доступа к данной команде!", connectionId);
            plugin.getLogger().info("Доступ запрещён: " + userId + " -> " + cmdName);
            return;
        }

        // ⭐ СОЗДАЁМ ФИНАЛЬНУЮ КОПИЮ ДЛЯ ЛЯМБДЫ
        final String finalRconCmd = rconCmd;
        final String finalConnectionId = connectionId;
        final long finalChatId = chatId;
        final long finalUserId = userId;

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                processRconCommandForBusiness(finalChatId, finalRconCmd, finalUserId, finalConnectionId);
            } catch (Exception e) {
                plugin.getLogger().warning("⚠️ Ошибка выполнения RCON: " + e.getMessage());
                e.printStackTrace();
                sendBusinessMessage(finalChatId, "[БОТ] ❌ Ошибка: " + e.getMessage(), finalConnectionId);
            }
        });
    }

    // ⭐ НОВЫЙ МЕТОД: обрабатывает RCON команды для бизнес-чатов
    private void processRconCommandForBusiness(long chatId, String cmd, long userId, String connectionId) {
        if (cmd.isEmpty()) {
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon <команда>", connectionId);
            return;
        }

        String cmdName = cmd.split(" ")[0];
        String fullCommand = "!rcon global " + cmdName;

        if (!groupManager.hasPermission(userId, fullCommand) &&
                !plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
            sendBusinessMessage(chatId, "[БОТ] У вас нет доступа к данной команде!", connectionId);
            plugin.getLogger().info("Доступ запрещён: " + userId + " -> " + cmdName);
            return;
        }

        if (cmd.startsWith("logs ")) {
            handleLogsBusiness(chatId, cmd, userId, connectionId);
            return;
        }
        if (cmd.startsWith("shist ")) {
            handleShistBusiness(chatId, cmd, userId, connectionId);
            return;
        }
        if (cmd.startsWith("hist ")) {
            handleHistBusiness(chatId, cmd, userId, connectionId);
            return;
        }
        if (cmd.startsWith("dupeip ")) {
            handleDupeipBusiness(chatId, cmd, userId, connectionId);
            return;
        }
        if (cmd.startsWith("pex user ")) {
            handlePexUserBusiness(chatId, cmd, userId, connectionId);
            return;
        }
        if (cmd.startsWith("bc ") || cmd.startsWith("bcast ")) {
            handleBroadcastBusiness(chatId, cmd, userId, connectionId);
            return;
        }
        if (cmd.startsWith("checkban ")) {
            handleCheckBanBusiness(chatId, cmd, connectionId);
            return;
        }
        if (cmd.startsWith("checkmute ")) {
            handleCheckMuteBusiness(chatId, cmd, connectionId);
            return;
        }
        if (cmd.startsWith("banlist")) {
            handleBanListBusiness(chatId, cmd, connectionId);
            return;
        }
        if (cmd.startsWith("mutelist")) {
            handleMuteListBusiness(chatId, cmd, connectionId);
            return;
        }
        if (cmd.startsWith("banip ")) {
            handleBanIpBusiness(chatId, cmd, userId, connectionId);
            return;
        }
        if (cmd.startsWith("unbanip ")) {
            handleUnbanIpBusiness(chatId, cmd, userId, connectionId);
            return;
        }
        if (cmd.startsWith("ban ") || cmd.startsWith("mute ") ||
                cmd.startsWith("kick ") || cmd.startsWith("unban ") || cmd.startsWith("unmute ")) {
            handlePunishmentBusiness(chatId, cmd, userId, connectionId);
            return;
        }

        executeServerCommandBusiness(chatId, cmd, userId, connectionId);
    }

    // ================================================================
    // ⭐ БИЗНЕС-ВЕРСИИ ВСЕХ МЕТОДОВ
    // ================================================================

    private void handleLogsBusiness(long chatId, String cmd, long userId, String connectionId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon logs <ник> [кол-во]", connectionId);
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
        sendBusinessMessage(chatId, response, connectionId);
    }

    private void handleShistBusiness(long chatId, String cmd, long userId, String connectionId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon shist <ник> [кол-во]", connectionId);
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
        sendBusinessMessage(chatId, response, connectionId);
    }

    private void handleHistBusiness(long chatId, String cmd, long userId, String connectionId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon hist <ник> [кол-во]", connectionId);
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
        sendBusinessMessage(chatId, response, connectionId);
    }

    private void handleDupeipBusiness(long chatId, String cmd, long userId, String connectionId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon dupeip <ник>", connectionId);
            return;
        }
        String playerName = parts[1];
        String targetIp = playerManager.getPlayerIp(playerName);
        if (targetIp == null || targetIp.equals("—") || targetIp.equals("0.0.0.0")) {
            sendBusinessMessage(chatId, "[БОТ] Не удалось определить IP игрока " + playerName, connectionId);
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
        sendBusinessMessage(chatId, response.toString(), connectionId);
    }

    private void handlePexUserBusiness(long chatId, String cmd, long userId, String connectionId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 3) {
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon pex user <ник>", connectionId);
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
            sendBusinessMessage(chatId, "[БОТ] Ошибка: " + e.getMessage(), connectionId);
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
        sendBusinessMessage(chatId, response, connectionId);
    }

    private void handleBroadcastBusiness(long chatId, String cmd, long userId, String connectionId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon bc <сообщение>", connectionId);
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        String sender = plugin.getCustomSender(userId);
        if (sender == null) sender = "RCON@" + userId;
        String chatMessage = "§e[Объявление] §f" + message + " §7(пишет: " + sender + "§7)";
        Bukkit.broadcastMessage(chatMessage);
        sendBusinessMessage(chatId, "[БОТ] Ответ от сервера:\n[Объявление] " + message + " (пишет: " + sender + ")", connectionId);
    }

    private void handleCheckBanBusiness(long chatId, String cmd, String connectionId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon checkban <ник>", connectionId);
            return;
        }
        String playerName = parts[1];
        boolean isBanned = punishmentManager.isBanned(playerName);
        if (!isBanned) {
            sendBusinessMessage(chatId, "[БОТ] Игрок " + playerName + " не забанен.", connectionId);
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
        sendBusinessMessage(chatId, response, connectionId);
    }

    private void handleCheckMuteBusiness(long chatId, String cmd, String connectionId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon checkmute <ник>", connectionId);
            return;
        }
        String playerName = parts[1];
        boolean isMuted = punishmentManager.isMuted(playerName);
        if (!isMuted) {
            sendBusinessMessage(chatId, "[БОТ] Игрок " + playerName + " не замучен.", connectionId);
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
        sendBusinessMessage(chatId, response, connectionId);
    }

    private void handleBanListBusiness(long chatId, String cmd, String connectionId) {
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
            sendBusinessMessage(chatId, "[БОТ] Список банов пуст.", connectionId);
        } else {
            StringBuilder response = new StringBuilder();
            response.append("[БОТ] Список банов (Страница ").append(page).append("/").append(totalPages).append(")\n");
            response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            for (String ban : bans) {
                response.append(ban).append("\n");
            }
            response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            if (totalPages > 1) {
                response.append("Используй: .rcon banlist ").append(page + 1);
            }
            sendBusinessMessage(chatId, response.toString(), connectionId);
        }
    }

    private void handleMuteListBusiness(long chatId, String cmd, String connectionId) {
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
            sendBusinessMessage(chatId, "[БОТ] Список мутов пуст.", connectionId);
        } else {
            StringBuilder response = new StringBuilder();
            response.append("[БОТ] Список мутов (Страница ").append(page).append("/").append(totalPages).append(")\n");
            response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            for (String mute : mutes) {
                response.append(mute).append("\n");
            }
            response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            if (totalPages > 1) {
                response.append("Используй: .rcon mutelist ").append(page + 1);
            }
            sendBusinessMessage(chatId, response.toString(), connectionId);
        }
    }

    private void handleBanIpBusiness(long chatId, String cmd, long userId, String connectionId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 4) {
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon banip <ник> <время> <причина> [-s]", connectionId);
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
            sendBusinessMessage(chatId, "[БОТ] Игрок " + playerName + " не найден на сервере!", connectionId);
            return;
        }
        boolean success = punishmentManager.banIp(playerName, issuer, reason, duration, hidden);
        if (success) {
            String coloredReason = reason.replace('&', '§');
            sendBusinessMessage(chatId, "[БОТ] IP игрока " + playerName + " забанен на " + duration + " по причине: " + coloredReason, connectionId);
        } else {
            sendBusinessMessage(chatId, "[БОТ] Не удалось забанить IP " + playerName, connectionId);
        }
    }

    private void handleUnbanIpBusiness(long chatId, String cmd, long userId, String connectionId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 3) {
            sendBusinessMessage(chatId, "[БОТ] Использование: .rcon unbanip <ник> <причина>", connectionId);
            return;
        }
        String playerName = parts[1];
        String reason = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON@" + userId;
        boolean success = punishmentManager.unbanIp(playerName, issuer, reason);
        if (success) {
            String coloredReason = reason.replace('&', '§');
            sendBusinessMessage(chatId, "[БОТ] IP игрока " + playerName + " разбанен по причине: " + coloredReason, connectionId);
        } else {
            sendBusinessMessage(chatId, "[БОТ] IP игрока " + playerName + " не забанен!", connectionId);
        }
    }

    private void handlePunishmentBusiness(long chatId, String cmd, long userId, String connectionId) {
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

        sendBusinessMessage(chatId, result, connectionId);
    }

    private void executeServerCommandBusiness(long chatId, String command, long userId, String connectionId) {
        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON@" + userId;

        Bukkit.getScheduler().runTask(plugin, () -> {
            commandExecutor.executeCommand(command, issuer);
            sendBusinessMessage(chatId, "[БОТ] Ответ от сервера:\nКоманда выполнена: " + command, connectionId);
        });
    }

    // ================================================================
    // ⭐ МЕТОДЫ ДЛЯ РАБОТЫ С BUSINESS API
    // ================================================================

    private void deleteBusinessMessage(String connectionId, int messageId) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/deleteBusinessMessages";
            String json = "{\"business_connection_id\":\"" + connectionId + "\",\"message_ids\":[" + messageId + "]}";
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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

            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            String escapedText = text.replace("\\", "\\\\")
                                     .replace("\"", "\\\"")
                                     .replace("\n", "\\n");
            
            String json = "{\"chat_id\":\"" + chatId + "\",\"text\":\"" + escapedText + "\",\"business_connection_id\":\"" + connectionId + "\"}";
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            plugin.getLogger().warning("⚠️ Ошибка отправки бизнес-сообщения: " + e.getMessage());
            sendMessage(chatId, text);
        }
    }

    // ================================================================
    // КОНЕЦ БЛОКА БИЗНЕС-СООБЩЕНИЙ
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
                "/привязать <ник> <код> - привязать аккаунт к Telegram";
        sendMessage(chatId, msg);
    }

    private void sendHelp(long chatId) {
        String msg = "[БОТ] Вот полный список команд:\n\n" +
                "/блокировка - заблокировать/разблокировать аккаунт\n" +
                "/кик - кикнуть аккаунт с сервера\n" +
                "/отвязать - подать заявку на отвязку аккаунта\n" +
                "/привязать <ник> <код> - привязать аккаунт\n" +
                "/помощь2 - помощь по RCON командам\n" +
                "/id - показать ваш Telegram ID";
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

    // ================================================================
    // ОСТАВШИЕСЯ МЕТОДЫ (БЕЗ ИЗМЕНЕНИЙ)
    // ================================================================

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

    // ===== BANIP =====
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
