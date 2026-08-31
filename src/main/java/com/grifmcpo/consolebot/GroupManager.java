package com.grifmcpo.consolebot; 

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

public class GroupManager {

    private final JavaPlugin plugin;
    private File groupsFile;
    private FileConfiguration groupsConfig;
    private final Map<String, List<String>> groupUsers = new HashMap<>();
    private final Map<String, List<String>> groupPermissions = new HashMap<>();
    private final Map<String, List<String>> userGroups = new HashMap<>();

    public GroupManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadGroups();
    }

    private void loadGroups() {
        groupsFile = new File(plugin.getDataFolder(), "groups.yml");
        if (!groupsFile.exists()) {
            plugin.getLogger().info("📁 groups.yml не найден, создаю стандартный...");
            createDefaultGroups();
        }
        groupsConfig = YamlConfiguration.loadConfiguration(groupsFile);
        parseGroups();
        plugin.getLogger().info("✅ Загружено групп: " + groupUsers.size());
    }

    private void createDefaultGroups() {
        groupsConfig = new YamlConfiguration();

        List<String> staffUsers = Arrays.asList("8308522569", "987654321");
        List<String> staffPerms = Arrays.asList(
            "!rcon global ban",
            "!rcon global kick",
            "!rcon global unban",
            "!rcon global banlist",
            "!rcon global shist",
            "!rcon global checkban",
            "!rcon global warn",
            "!rcon global unwarn",
            "!rcon global whois",
            "!rcon global seen"
        );
        groupsConfig.set("staff.users", staffUsers);
        groupsConfig.set("staff.permissions", staffPerms);

        groupsConfig.set("leader.users", Arrays.asList("555555555"));
        groupsConfig.set("leader.permissions", Arrays.asList("ALL"));

        groupsConfig.set("admin.users", Arrays.asList("8308522569"));
        groupsConfig.set("admin.permissions", Arrays.asList("ALL"));

        groupsConfig.set("owner.users", Arrays.asList("8308522569"));
        groupsConfig.set("owner.permissions", Arrays.asList("ALL"));

        try {
            groupsConfig.save(groupsFile);
            plugin.getLogger().info("✅ Создан стандартный groups.yml!");
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Ошибка сохранения groups.yml: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void parseGroups() {
        groupUsers.clear();
        groupPermissions.clear();
        userGroups.clear();

        for (String groupName : groupsConfig.getKeys(false)) {
            List<String> users = (List<String>) groupsConfig.getList(groupName + ".users", new ArrayList<>());
            List<String> permissions = (List<String>) groupsConfig.getList(groupName + ".permissions", new ArrayList<>());

            groupUsers.put(groupName, users);
            groupPermissions.put(groupName, permissions);

            for (String userId : users) {
                userGroups.computeIfAbsent(userId, k -> new ArrayList<>()).add(groupName);
            }
        }
    }

    public String getUserGroup(long userId) {
        String key = String.valueOf(userId);
        List<String> groups = userGroups.get(key);
        if (groups == null || groups.isEmpty()) return null;
        return groups.get(0);
    }

    public List<String> getUserGroups(long userId) {
        return userGroups.getOrDefault(String.valueOf(userId), new ArrayList<>());
    }

    public boolean hasPermission(long userId, String command) {
        String key = String.valueOf(userId);
        List<String> groups = userGroups.get(key);
        if (groups == null || groups.isEmpty()) return false;

        for (String groupName : groups) {
            List<String> perms = groupPermissions.getOrDefault(groupName, new ArrayList<>());
            if (perms.contains("ALL")) return true;
            if (perms.contains(command)) return true;
        }
        return false;
    }

    public boolean isAdmin(long userId) {
        String key = String.valueOf(userId);
        List<String> groups = userGroups.get(key);
        if (groups == null) return false;
        return groups.contains("admin") || groups.contains("owner") || groups.contains("leader");
    }

    // =========================================================
    // ==== ПРОВЕРКА ВЛАДЕЛЬЦА (ДОБАВЛЕН ID 8308522569) =====
    // =========================================================
    public boolean isOwner(long userId) {
        // Проверяем по ID владельца из плагина
        if (userId == plugin.getOwnerId()) {
            return true;
        }
        
        String key = String.valueOf(userId);
        List<String> groups = userGroups.get(key);
        if (groups == null) return false;
        return groups.contains("owner");
    }

    public List<String> getAvailableCommands(long userId) {
        String key = String.valueOf(userId);
        List<String> groups = userGroups.get(key);
        if (groups == null || groups.isEmpty()) return new ArrayList<>();

        Set<String> commands = new HashSet<>();
        for (String groupName : groups) {
            List<String> perms = groupPermissions.getOrDefault(groupName, new ArrayList<>());
            if (perms.contains("ALL")) {
                commands.add("все команды");
                return new ArrayList<>(commands);
            }
            commands.addAll(perms);
        }
        return new ArrayList<>(commands);
    }

    public void reload() {
        loadGroups();
    }
}
