package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
        if (bot.player != null && bot.player.isOnline()) return;
        
        try {
            World world = Bukkit.getWorld(bot.world != null ? bot.world : "world");
            if (world == null) {
                world = Bukkit.getWorlds().get(0);
            }
            
            Location loc = new Location(world, bot.x, bot.y, bot.z, bot.yaw, bot.pitch);
            
            Player existing = Bukkit.getPlayerExact(bot.name);
            if (existing != null) {
                bot.player = existing;
                bot.uuid = existing.getUniqueId().toString();
                setupBotPlayer(existing);
                return;
            }
            
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:op " + bot.name);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist add " + bot.name);
            
            if (Bukkit.getPluginManager().getPlugin("Citizens") != null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                    "npc create " + bot.name + " --type player");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                    "npc tp " + bot.name + " " + loc.getX() + " " + loc.getY() + " " + loc.getZ());
            }
            
            bot.active = true;
            saveBots();
            
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при создании бота " + bot.name + ": " + e.getMessage());
        }
    }

    private void setupBotPlayer(Player player) {
        player.setInvisible(true);
        player.setInvulnerable(true);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setOp(true);
        player.setHealth(9999);
        player.setFoodLevel(9999);
        player.setSaturation(9999);
        player.setExp(1);
        player.setLevel(9999);
        player.setWalkSpeed(0.0f);
        player.setFlySpeed(0.0f);
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(player)) {
                p.hidePlayer(plugin, player);
            }
        }
        
        player.setPlayerListName("§7§o" + player.getName());
        player.setDisplayName("§7§o" + player.getName());
        player.sendMessage("§aВы вошли как бот!");
    }

    public boolean runCommand(String name, String command) {
        Bot bot = bots.get(name.toLowerCase());
        if (bot == null) return false;
        if (!bot.active) return false;
        
        String botName = name;
        String finalCommand = command;
        
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player botPlayer = Bukkit.getPlayerExact(botName);
            if (botPlayer != null && botPlayer.isOnline()) {
                Bukkit.dispatchCommand(botPlayer, finalCommand);
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
            }
            
            plugin.getLogger().info("🤖 Бот " + botName + " выполнил: " + finalCommand);
            
            long ownerId = plugin.getOwnerId();
            plugin.sendMessageAsBot(ownerId, 
                "[БОТ] 🤖 Бот " + botName + " выполнил команду:\n" + finalCommand);
        });
        
        return true;
    }

    public boolean stopBot(String name) {
        Bot bot = bots.get(name.toLowerCase());
        if (bot == null) return false;
        if (!bot.active) return false;
        
        bot.active = false;
        
        if (bot.player != null && bot.player.isOnline()) {
            bot.player.kickPlayer("§cБот остановлен!");
        }
        bot.player = null;
        
        saveBots();
        plugin.getLogger().info("🤖 Бот " + name + " остановлен!");
        return true;
    }

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
    // ==== НОВЫЙ МЕТОД ДЛЯ ПРОВЕРКИ СУЩЕСТВОВАНИЯ БОТА =====
    // =========================================================
    public boolean botExists(String name) {
        return bots.containsKey(name.toLowerCase());
    }

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

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        
        for (Bot bot : bots.values()) {
            if (bot.uuid != null && bot.uuid.equals(player.getUniqueId().toString())) {
                bot.player = player;
                bot.active = true;
                setupBotPlayer(player);
                event.setJoinMessage(null);
                break;
            }
        }
        
        for (Bot bot : bots.values()) {
            if (bot.player != null && bot.player.isOnline() && !bot.player.equals(player)) {
                player.hidePlayer(plugin, bot.player);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        for (Bot bot : bots.values()) {
            if (bot.uuid != null && bot.uuid.equals(player.getUniqueId().toString())) {
                bot.player = null;
                if (bot.active) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        spawnBot(bot);
                    }, 20L * 5);
                }
                break;
            }
        }
    }

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
