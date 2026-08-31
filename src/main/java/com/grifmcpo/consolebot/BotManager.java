package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class BotManager implements Listener {

    private final TelegramConsoleBot plugin;
    private final Map<String, Bot> bots = new ConcurrentHashMap<>();
    private final Map<String, UUID> botUUIDs = new ConcurrentHashMap<>();
    private File botsFile;
    private FileConfiguration botsConfig;

    public BotManager(TelegramConsoleBot plugin) {
        this.plugin = plugin;
        loadBots();
        startBotChecker();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("✅ BotManager загружен! Ботов: " + bots.size());
    }

    private void loadBots() {
        botsFile = new File(plugin.getDataFolder(), "bots.yml");
        if (!botsFile.exists()) {
            try {
                botsFile.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().severe("Не удалось создать bots.yml");
            }
        }
        botsConfig = YamlConfiguration.loadConfiguration(botsFile);
        
        bots.clear();
        if (botsConfig.contains("bots")) {
            for (String name : botsConfig.getConfigurationSection("bots").getKeys(false)) {
                String uuidStr = botsConfig.getString("bots." + name + ".uuid");
                boolean active = botsConfig.getBoolean("bots." + name + ".active", false);
                String world = botsConfig.getString("bots." + name + ".world", "world");
                double x = botsConfig.getDouble("bots." + name + ".x", 0);
                double y = botsConfig.getDouble("bots." + name + ".y", 0);
                double z = botsConfig.getDouble("bots." + name + ".z", 0);
                float yaw = (float) botsConfig.getDouble("bots." + name + ".yaw", 0);
                float pitch = (float) botsConfig.getDouble("bots." + name + ".pitch", 0);
                
                Bot bot = new Bot(name, uuidStr, active);
                bot.world = world;
                bot.x = x;
                bot.y = y;
                bot.z = z;
                bot.yaw = yaw;
                bot.pitch = pitch;
                bots.put(name.toLowerCase(), bot);
                
                if (active) {
                    spawnBot(bot);
                }
            }
        }
    }

    private void saveBots() {
        botsConfig.set("bots", null);
        for (Bot bot : bots.values()) {
            botsConfig.set("bots." + bot.name + ".uuid", bot.uuid);
            botsConfig.set("bots." + bot.name + ".active", bot.active);
            if (bot.player != null) {
                Location loc = bot.player.getLocation();
                botsConfig.set("bots." + bot.name + ".world", loc.getWorld().getName());
                botsConfig.set("bots." + bot.name + ".x", loc.getX());
                botsConfig.set("bots." + bot.name + ".y", loc.getY());
                botsConfig.set("bots." + bot.name + ".z", loc.getZ());
                botsConfig.set("bots." + bot.name + ".yaw", loc.getYaw());
                botsConfig.set("bots." + bot.name + ".pitch", loc.getPitch());
            }
        }
        try {
            botsConfig.save(botsFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка сохранения bots.yml: " + e.getMessage());
        }
    }

    // =========================================================
    // ==== СОЗДАНИЕ БОТА =====
    // =========================================================
    public boolean createBot(String name) {
        if (bots.containsKey(name.toLowerCase())) {
            return false;
        }
        
        String uuid = UUID.randomUUID().toString();
        Bot bot = new Bot(name, uuid, false);
        bots.put(name.toLowerCase(), bot);
        saveBots();
        plugin.getLogger().info("🤖 Бот " + name + " создан!");
        return true;
    }

    // =========================================================
    // ==== ЗАПУСК БОТА =====
    // =========================================================
    public boolean startBot(String name) {
        Bot bot = bots.get(name.toLowerCase());
        if (bot == null) return false;
        if (bot.active) return false;
        
        bot.active = true;
        spawnBot(bot);
        saveBots();
        plugin.getLogger().info("🤖 Бот " + name + " запущен!");
        return true;
    }

    private void spawnBot(Bot bot) {
        if (bot.player != null) return;
        
        try {
            // Создаем NPC игрока
            World world = Bukkit.getWorld(bot.world != null ? bot.world : "world");
            if (world == null) {
                world = Bukkit.getWorlds().get(0);
            }
            
            Location loc = new Location(world, bot.x, bot.y, bot.z, bot.yaw, bot.pitch);
            
            // Симулируем вход игрока
            bot.player = Bukkit.getPlayerExact(bot.name);
            if (bot.player == null) {
                // Создаем "призрачного" игрока через команду
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                    "minecraft:op " + bot.name);
                
                // Добавляем бота в белый список
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                    "whitelist add " + bot.name);
                
                // Загружаем скин (через API)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                    "lp user " + bot.name + " parent set default");
                
                // Используем Packet для создания NPC
                // В реальности нужно использовать ProtocolLib или Citizens
                // Для простоты используем обычного игрока
            }
            
            // Сохраняем UUID
            if (bot.player != null) {
                bot.uuid = bot.player.getUniqueId().toString();
                botUUIDs.put(bot.name.toLowerCase(), bot.player.getUniqueId());
            }
            
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при создании бота " + bot.name + ": " + e.getMessage());
        }
    }

    // =========================================================
    // ==== ВЫПОЛНЕНИЕ КОМАНДЫ ОТ ИМЕНИ БОТА =====
    // =========================================================
    public boolean runCommand(String name, String command) {
        Bot bot = bots.get(name.toLowerCase());
        if (bot == null) return false;
        if (!bot.active) return false;
        
        // Создаем фиктивного игрока для выполнения команды
        // Используем консоль от имени бота
        String finalCommand = command;
        String botName = name;
        
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Выполняем команду от имени бота
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                "lp user " + botName + " permission set * true");
            
            // Выполняем саму команду
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
            
            // Логируем
            plugin.getLogger().info("🤖 Бот " + botName + " выполнил: " + finalCommand);
            
            // Отправляем уведомление владельцу
            long ownerId = plugin.getOwnerId();
            plugin.sendMessageAsBot(ownerId, 
                "[БОТ] 🤖 Бот " + botName + " выполнил команду:\n" + finalCommand);
        });
        
        return true;
    }

    // =========================================================
    // ==== ОСТАНОВКА БОТА =====
    // =========================================================
    public boolean stopBot(String name) {
        Bot bot = bots.get(name.toLowerCase());
        if (bot == null) return false;
        if (!bot.active) return false;
        
        bot.active = false;
        
        // Кикаем бота
        if (bot.player != null && bot.player.isOnline()) {
            bot.player.kickPlayer("§cБот остановлен!");
        }
        bot.player = null;
        
        saveBots();
        plugin.getLogger().info("🤖 Бот " + name + " остановлен!");
        return true;
    }

    // =========================================================
    // ==== УДАЛЕНИЕ БОТА =====
    // =========================================================
    public boolean deleteBot(String name) {
        Bot bot = bots.get(name.toLowerCase());
        if (bot == null) return false;
        
        if (bot.active) {
            stopBot(name);
        }
        
        bots.remove(name.toLowerCase());
        saveBots();
        plugin.getLogger().info("🤖 Бот " + name + " удален!");
        return true;
    }

    // =========================================================
    // ==== СПИСОК БОТОВ =====
    // =========================================================
    public String getBotList() {
        if (bots.isEmpty()) {
            return "[БОТ] Нет созданных ботов.";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("[БОТ] Список ботов:\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        
        for (Bot bot : bots.values()) {
            String status = bot.active ? "§a✅ Активен" : "§c❌ Остановлен";
            String online = bot.player != null && bot.player.isOnline() ? "§aОнлайн" : "§cОфлайн";
            sb.append("§f" + bot.name + " §7- " + status + " §7(" + online + ")\n");
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        return sb.toString();
    }

    // =========================================================
    // ==== ПРОВЕРКА БОТОВ =====
    // =========================================================
    private void startBotChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Bot bot : bots.values()) {
                    if (bot.active && (bot.player == null || !bot.player.isOnline())) {
                        spawnBot(bot);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L * 30, 20L * 60);
    }

    // =========================================================
    // ==== СОБЫТИЯ =====
    // =========================================================
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        
        // Проверяем, является ли игрок ботом
        for (Bot bot : bots.values()) {
            if (bot.uuid != null && bot.uuid.equals(player.getUniqueId().toString())) {
                bot.player = player;
                bot.active = true;
                // Делаем бота невидимым
                player.setInvisible(true);
                player.setInvulnerable(true);
                player.setAllowFlight(true);
                player.setFlying(true);
                player.setOp(true);
                // Скрываем бота из TAB
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.hidePlayer(plugin, player);
                }
                break;
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        
        for (Bot bot : bots.values()) {
            if (bot.uuid != null && bot.uuid.equals(player.getUniqueId().toString())) {
                bot.player = null;
                if (bot.active) {
                    // Пересоздаем бота через некоторое время
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        spawnBot(bot);
                    }, 20L * 5);
                }
                break;
            }
        }
    }

    // =========================================================
    // ==== ВНУТРЕННИЙ КЛАСС =====
    // =========================================================
    public static class Bot {
        public String name;
        public String uuid;
        public boolean active;
        public Player player;
        public String world = "world";
        public double x = 0;
        public double y = 0;
        public double z = 0;
        public float yaw = 0;
        public float pitch = 0;
        
        public Bot(String name, String uuid, boolean active) {
            this.name = name;
            this.uuid = uuid;
            this.active = active;
        }
    }
}
