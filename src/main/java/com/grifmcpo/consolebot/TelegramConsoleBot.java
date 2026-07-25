package com.grifmcpo.consolebot;  

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class TelegramConsoleBot extends JavaPlugin {

    private Map<String, String> admins = new HashMap<>();
    private long ownerId = 8889631346L;
    private File adminsFile;
    private PlayerManager playerManager;
    private CommandLogger commandLogger;
    private LogsCommand logsCommand;
    private CommandExecutor commandExecutor;
    private PunishmentManager punishmentManager;
    private AdminLogger adminLogger;
    private BotBanManager botBanManager;
    private GroupManager groupManager;
    private TelegramBotHandler botHandler;
    private ChatManager chatManager;  // Чат-менеджер

    @Override
    public void onEnable() {
        getLogger().info("✅ ConsoleBot включен!");

        saveDefaultConfig();
        String token = getConfig().getString("telegram-token");
        if (token == null || token.isEmpty()) {
            getLogger().severe("❌ Токен не найден в config.yml!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadAdmins();
        playerManager = new PlayerManager(this);
        commandLogger = new CommandLogger(this);
        logsCommand = new LogsCommand(this);
        commandExecutor = new CommandExecutor(this);
        adminLogger = new AdminLogger(this);
        punishmentManager = new PunishmentManager(this, adminLogger);
        botBanManager = new BotBanManager(this);
        groupManager = new GroupManager(this);

        // ===== РЕГИСТРАЦИЯ КОМАНДЫ /pex =====
        PluginCommand pexCommand = getCommand("pex");
        if (pexCommand != null) {
            pexCommand.setExecutor(new PexCommand(this));
        }

        // ===== РЕГИСТРАЦИЯ ЧАТ-МОДУЛЯ =====
        chatManager = new ChatManager(this, punishmentManager);
        Bukkit.getPluginManager().registerEvents(chatManager, this);
        Bukkit.getPluginManager().registerEvents(new ChatListener(punishmentManager), this);
        getLogger().info("✅ Собственный чат-модуль загружен!");

        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botHandler = new TelegramBotHandler(token, this, playerManager, commandLogger, logsCommand,
                    commandExecutor, punishmentManager, botBanManager, groupManager);
            botsApi.registerBot(botHandler);
            getLogger().info("✅ Telegram-бот успешно зарегистрирован!");
        } catch (TelegramApiException e) {
            getLogger().severe("❌ Ошибка при регистрации бота: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("❌ ConsoleBot выключен.");
        if (commandLogger != null) commandLogger.saveLogs();
        if (commandExecutor != null) commandExecutor.close();
    }

    private void loadAdmins() {
        adminsFile = new File(getDataFolder(), "admins.yml");
        if (!adminsFile.exists()) saveResource("admins.yml", false);
        reloadAdmins();
    }

    public void reloadAdmins() {
        admins.clear();
        if (adminsFile.exists()) {
            try {
                org.bukkit.configuration.file.YamlConfiguration config =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(adminsFile);
                for (String key : config.getKeys(false)) {
                    admins.put(key, config.getString(key));
                }
            } catch (Exception e) {
                getLogger().warning("❌ Ошибка загрузки admins.yml: " + e.getMessage());
            }
        }
        getLogger().info("✅ Загружено администраторов: " + admins.size());
    }

    public void saveAdmins() {
        try {
            org.bukkit.configuration.file.YamlConfiguration config =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(adminsFile);
            for (Map.Entry<String, String> entry : admins.entrySet()) {
                config.set(entry.getKey(), entry.getValue());
            }
            config.save(adminsFile);
        } catch (Exception e) {
            getLogger().severe("❌ Ошибка сохранения admins.yml: " + e.getMessage());
        }
    }

    public void sendMessageAsBot(long chatId, String text) {
        if (botHandler != null) {
            botHandler.sendMessage(chatId, text);
        }
    }

    public Map<String, String> getAdmins() { return admins; }
    public long getOwnerId() { return ownerId; }
    public void addAdmin(String telegramId, String playerName) { admins.put(telegramId, playerName); saveAdmins(); }
    public void removeAdmin(String telegramId) { admins.remove(telegramId); saveAdmins(); }
    public boolean isAdmin(long telegramId) { return admins.containsKey(String.valueOf(telegramId)); }
    public String getCustomSender(long telegramId) { return admins.get(String.valueOf(telegramId)); }
    public PlayerManager getPlayerManager() { return playerManager; }
    public CommandLogger getCommandLogger() { return commandLogger; }
    public CommandExecutor getCommandExecutor() { return commandExecutor; }
    public PunishmentManager getPunishmentManager() { return punishmentManager; }
    public AdminLogger getAdminLogger() { return adminLogger; }
    public BotBanManager getBotBanManager() { return botBanManager; }
    public GroupManager getGroupManager() { return groupManager; }
    public TelegramBotHandler getBotHandler() { return botHandler; }
    public ChatManager getChatManager() { return chatManager; }
}
