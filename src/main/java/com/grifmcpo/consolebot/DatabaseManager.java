package com.grifmcpo.consolebot;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class DatabaseManager {

    private final JavaPlugin plugin;
    private Connection connection;
    private final Object lock = new Object();

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        connect();
        createTables();
        plugin.getLogger().info("✅ DatabaseManager инициализирован (SQLite)");
    }

    private void connect() {
        try {
            Class.forName("org.sqlite.JDBC");
            String url = "jdbc:sqlite:" + plugin.getDataFolder() + "/database.db";
            connection = DriverManager.getConnection(url);
            
            // Включаем поддержку FOREIGN KEY
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
            
            plugin.getLogger().info("✅ Подключение к SQLite успешно!");
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Ошибка подключения к SQLite: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTables() {
        String playersTable = """
            CREATE TABLE IF NOT EXISTS players (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid VARCHAR(36) NOT NULL UNIQUE,
                player_name VARCHAR(16) NOT NULL,
                telegram_id VARCHAR(20) UNIQUE,
                ip VARCHAR(45),
                session_start INTEGER DEFAULT 0,
                blocked INTEGER DEFAULT 0,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
        """;

        String punishmentsTable = """
            CREATE TABLE IF NOT EXISTS punishments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid VARCHAR(36) NOT NULL,
                type VARCHAR(10) NOT NULL,
                issuer VARCHAR(36) NOT NULL,
                reason TEXT,
                duration VARCHAR(20),
                timestamp INTEGER NOT NULL,
                expiry INTEGER,
                hidden INTEGER DEFAULT 0,
                active INTEGER DEFAULT 1,
                FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE
            )
        """;

        String commandLogsTable = """
            CREATE TABLE IF NOT EXISTS command_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid VARCHAR(36) NOT NULL,
                command TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE
            )
        """;

        String botBansTable = """
            CREATE TABLE IF NOT EXISTS bot_bans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                telegram_id VARCHAR(20) NOT NULL UNIQUE,
                reason TEXT,
                issuer VARCHAR(20),
                timestamp INTEGER NOT NULL,
                expiry INTEGER
            )
        """;

        // Индексы для быстрого поиска
        String[] indexes = {
            "CREATE INDEX IF NOT EXISTS idx_players_uuid ON players(uuid)",
            "CREATE INDEX IF NOT EXISTS idx_players_telegram ON players(telegram_id)",
            "CREATE INDEX IF NOT EXISTS idx_players_name ON players(player_name)",
            "CREATE INDEX IF NOT EXISTS idx_punishments_player ON punishments(player_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_punishments_active ON punishments(active)",
            "CREATE INDEX IF NOT EXISTS idx_logs_player ON command_logs(player_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_bot_bans_telegram ON bot_bans(telegram_id)"
        };

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(playersTable);
            stmt.execute(punishmentsTable);
            stmt.execute(commandLogsTable);
            stmt.execute(botBansTable);
            
            for (String index : indexes) {
                stmt.execute(index);
            }
            
            plugin.getLogger().info("✅ Таблицы созданы/проверены!");
        } catch (SQLException e) {
            plugin.getLogger().severe("❌ Ошибка создания таблиц: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================================================
    // ==== МЕТОДЫ ДЛЯ РАБОТЫ С ИГРОКАМИ =====
    // =========================================================

    public CompletableFuture<Boolean> linkPlayer(String uuid, String playerName, String telegramId, String ip) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "INSERT OR REPLACE INTO players (uuid, player_name, telegram_id, ip, session_start, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, strftime('%s', 'now'))";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, uuid);
                    ps.setString(2, playerName);
                    ps.setString(3, telegramId);
                    ps.setString(4, ip);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка привязки игрока " + playerName + ": " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<Boolean> unlinkPlayer(String uuid) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "UPDATE players SET telegram_id = NULL, session_start = 0, updated_at = strftime('%s', 'now') WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, uuid);
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка отвязки игрока: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<Map<String, Object>> getPlayer(String uuid) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "SELECT * FROM players WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, uuid);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        Map<String, Object> player = new HashMap<>();
                        player.put("uuid", rs.getString("uuid"));
                        player.put("player_name", rs.getString("player_name"));
                        player.put("telegram_id", rs.getString("telegram_id"));
                        player.put("ip", rs.getString("ip"));
                        player.put("session_start", rs.getLong("session_start"));
                        player.put("blocked", rs.getInt("blocked") == 1);
                        return player;
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка получения игрока: " + e.getMessage());
                }
                return null;
            }
        });
    }

    public CompletableFuture<Map<String, Object>> getPlayerByName(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "SELECT * FROM players WHERE player_name = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerName);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        Map<String, Object> player = new HashMap<>();
                        player.put("uuid", rs.getString("uuid"));
                        player.put("player_name", rs.getString("player_name"));
                        player.put("telegram_id", rs.getString("telegram_id"));
                        player.put("ip", rs.getString("ip"));
                        player.put("session_start", rs.getLong("session_start"));
                        player.put("blocked", rs.getInt("blocked") == 1);
                        return player;
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка получения игрока: " + e.getMessage());
                }
                return null;
            }
        });
    }

    public CompletableFuture<String> getPlayerUuidByName(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "SELECT uuid FROM players WHERE player_name = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerName);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        return rs.getString("uuid");
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка получения UUID: " + e.getMessage());
                }
                return null;
            }
        });
    }

    public CompletableFuture<String> getPlayerNameByUuid(String uuid) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "SELECT player_name FROM players WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, uuid);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        return rs.getString("player_name");
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка получения имени: " + e.getMessage());
                }
                return null;
            }
        });
    }

    public CompletableFuture<String> getPlayerByTelegram(String telegramId) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "SELECT uuid FROM players WHERE telegram_id = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, telegramId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        return rs.getString("uuid");
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка поиска игрока: " + e.getMessage());
                }
                return null;
            }
        });
    }

    public CompletableFuture<Boolean> isTelegramLinked(String telegramId) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "SELECT COUNT(*) FROM players WHERE telegram_id = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, telegramId);
                    ResultSet rs = ps.executeQuery();
                    return rs.next() && rs.getInt(1) > 0;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка проверки: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<Boolean> updatePlayerName(String uuid, String newName) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "UPDATE players SET player_name = ?, updated_at = strftime('%s', 'now') WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, newName);
                    ps.setString(2, uuid);
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка обновления имени: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<Boolean> setBlocked(String uuid, boolean blocked) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "UPDATE players SET blocked = ?, updated_at = strftime('%s', 'now') WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setInt(1, blocked ? 1 : 0);
                    ps.setString(2, uuid);
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка блокировки: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<Boolean> updateSession(String uuid) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "UPDATE players SET session_start = ?, updated_at = strftime('%s', 'now') WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setString(2, uuid);
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка обновления сессии: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<Boolean> updateIp(String uuid, String ip) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "UPDATE players SET ip = ?, updated_at = strftime('%s', 'now') WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, ip);
                    ps.setString(2, uuid);
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка обновления IP: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<List<String>> getPlayersByIp(String ip) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                List<String> players = new ArrayList<>();
                String sql = "SELECT player_name FROM players WHERE ip = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, ip);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        players.add(rs.getString("player_name"));
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка поиска по IP: " + e.getMessage());
                }
                return players;
            }
        });
    }

    public CompletableFuture<List<Map<String, Object>>> getAllPlayers() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                List<Map<String, Object>> players = new ArrayList<>();
                String sql = "SELECT * FROM players WHERE telegram_id IS NOT NULL";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> player = new HashMap<>();
                        player.put("uuid", rs.getString("uuid"));
                        player.put("player_name", rs.getString("player_name"));
                        player.put("telegram_id", rs.getString("telegram_id"));
                        player.put("ip", rs.getString("ip"));
                        player.put("blocked", rs.getInt("blocked") == 1);
                        players.add(player);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка получения игроков: " + e.getMessage());
                }
                return players;
            }
        });
    }

    // =========================================================
    // ==== МЕТОДЫ ДЛЯ РАБОТЫ С НАКАЗАНИЯМИ =====
    // =========================================================

    public CompletableFuture<Boolean> addPunishment(String playerUuid, String type, String issuerUuid,
                                                     String reason, String duration, long expiry, boolean hidden) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "INSERT INTO punishments (player_uuid, type, issuer, reason, duration, timestamp, expiry, hidden) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid);
                    ps.setString(2, type);
                    ps.setString(3, issuerUuid);
                    ps.setString(4, reason);
                    ps.setString(5, duration);
                    ps.setLong(6, System.currentTimeMillis());
                    ps.setLong(7, expiry);
                    ps.setInt(8, hidden ? 1 : 0);
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка добавления наказания: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<List<Map<String, Object>>> getActivePunishments(String playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                List<Map<String, Object>> punishments = new ArrayList<>();
                String sql = "SELECT * FROM punishments WHERE player_uuid = ? AND active = 1 AND (expiry IS NULL OR expiry > ?)";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid);
                    ps.setLong(2, System.currentTimeMillis());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("id", rs.getInt("id"));
                        p.put("type", rs.getString("type"));
                        p.put("issuer", rs.getString("issuer"));
                        p.put("reason", rs.getString("reason"));
                        p.put("duration", rs.getString("duration"));
                        p.put("timestamp", rs.getLong("timestamp"));
                        p.put("expiry", rs.getLong("expiry"));
                        p.put("hidden", rs.getInt("hidden") == 1);
                        punishments.add(p);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка получения наказаний: " + e.getMessage());
                }
                return punishments;
            }
        });
    }

    public CompletableFuture<Boolean> deactivatePunishment(int id) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "UPDATE punishments SET active = 0 WHERE id = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка деактивации наказания: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<Boolean> deactivatePunishmentsByType(String playerUuid, String type) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "UPDATE punishments SET active = 0 WHERE player_uuid = ? AND type = ? AND active = 1";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid);
                    ps.setString(2, type);
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка деактивации наказаний: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<List<Map<String, Object>>> getPunishmentHistory(String playerUuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                List<Map<String, Object>> punishments = new ArrayList<>();
                String sql = "SELECT * FROM punishments WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid);
                    ps.setInt(2, limit);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("id", rs.getInt("id"));
                        p.put("type", rs.getString("type"));
                        p.put("issuer", rs.getString("issuer"));
                        p.put("reason", rs.getString("reason"));
                        p.put("duration", rs.getString("duration"));
                        p.put("timestamp", rs.getLong("timestamp"));
                        p.put("expiry", rs.getLong("expiry"));
                        p.put("hidden", rs.getInt("hidden") == 1);
                        p.put("active", rs.getInt("active") == 1);
                        punishments.add(p);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка получения истории: " + e.getMessage());
                }
                return punishments;
            }
        });
    }

    public CompletableFuture<List<Map<String, Object>>> getIssuerHistory(String issuerUuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                List<Map<String, Object>> punishments = new ArrayList<>();
                String sql = "SELECT * FROM punishments WHERE issuer = ? ORDER BY timestamp DESC LIMIT ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, issuerUuid);
                    ps.setInt(2, limit);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("id", rs.getInt("id"));
                        p.put("player_uuid", rs.getString("player_uuid"));
                        p.put("type", rs.getString("type"));
                        p.put("reason", rs.getString("reason"));
                        p.put("duration", rs.getString("duration"));
                        p.put("timestamp", rs.getLong("timestamp"));
                        p.put("expiry", rs.getLong("expiry"));
                        p.put("hidden", rs.getInt("hidden") == 1);
                        p.put("active", rs.getInt("active") == 1);
                        punishments.add(p);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка получения истории выдач: " + e.getMessage());
                }
                return punishments;
            }
        });
    }

    public CompletableFuture<List<Map<String, Object>>> getAllActivePunishments() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                List<Map<String, Object>> punishments = new ArrayList<>();
                String sql = "SELECT * FROM punishments WHERE active = 1 AND (expiry IS NULL OR expiry > ?)";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setLong(1, System.currentTimeMillis());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("id", rs.getInt("id"));
                        p.put("player_uuid", rs.getString("player_uuid"));
                        p.put("type", rs.getString("type"));
                        p.put("issuer", rs.getString("issuer"));
                        p.put("reason", rs.getString("reason"));
                        p.put("duration", rs.getString("duration"));
                        p.put("timestamp", rs.getLong("timestamp"));
                        p.put("expiry", rs.getLong("expiry"));
                        punishments.add(p);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка получения активных наказаний: " + e.getMessage());
                }
                return punishments;
            }
        });
    }

    public CompletableFuture<Boolean> cleanExpiredPunishments() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "UPDATE punishments SET active = 0 WHERE active = 1 AND expiry IS NOT NULL AND expiry < ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setLong(1, System.currentTimeMillis());
                    int updated = ps.executeUpdate();
                    if (updated > 0) {
                        plugin.getLogger().info("🔄 Автоснято наказаний: " + updated);
                    }
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка очистки наказаний: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    // =========================================================
    // ==== МЕТОДЫ ДЛЯ ЛОГОВ КОМАНД =====
    // =========================================================

    public CompletableFuture<Void> logCommand(String playerUuid, String command) {
        return CompletableFuture.runAsync(() -> {
            synchronized (lock) {
                String sql = "INSERT INTO command_logs (player_uuid, command, timestamp) VALUES (?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid);
                    ps.setString(2, command);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка логирования команды: " + e.getMessage());
                }
            }
        });
    }

    public CompletableFuture<List<Map<String, Object>>> getCommandLogs(String playerUuid, int limit, int days) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                List<Map<String, Object>> logs = new ArrayList<>();
                long cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
                String sql = "SELECT * FROM command_logs WHERE player_uuid = ? AND timestamp > ? ORDER BY timestamp DESC LIMIT ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid);
                    ps.setLong(2, cutoff);
                    ps.setInt(3, limit);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> log = new HashMap<>();
                        log.put("command", rs.getString("command"));
                        log.put("timestamp", rs.getLong("timestamp"));
                        logs.add(log);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка получения логов: " + e.getMessage());
                }
                return logs;
            }
        });
    }

    public CompletableFuture<Integer> cleanOldLogs(int days) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                long cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
                String sql = "DELETE FROM command_logs WHERE timestamp < ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setLong(1, cutoff);
                    int deleted = ps.executeUpdate();
                    if (deleted > 0) {
                        plugin.getLogger().info("🗑️ Удалено старых логов: " + deleted);
                    }
                    return deleted;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка очистки логов: " + e.getMessage());
                    return 0;
                }
            }
        });
    }

    // =========================================================
    // ==== МЕТОДЫ ДЛЯ БАНОВ БОТА =====
    // =========================================================

    public CompletableFuture<Boolean> banBotUser(String telegramId, String reason, String issuer, long expiry) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "INSERT OR REPLACE INTO bot_bans (telegram_id, reason, issuer, timestamp, expiry) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    long now = System.currentTimeMillis();
                    ps.setString(1, telegramId);
                    ps.setString(2, reason);
                    ps.setString(3, issuer);
                    ps.setLong(4, now);
                    ps.setLong(5, expiry);
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка бана пользователя: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<Boolean> unbanBotUser(String telegramId) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "DELETE FROM bot_bans WHERE telegram_id = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, telegramId);
                    ps.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка разбана пользователя: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    public CompletableFuture<Map<String, Object>> getBotBan(String telegramId) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                String sql = "SELECT * FROM bot_bans WHERE telegram_id = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, telegramId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        long expiry = rs.getLong("expiry");
                        if (expiry > 0 && expiry < System.currentTimeMillis()) {
                            unbanBotUser(telegramId);
                            return null;
                        }
                        Map<String, Object> ban = new HashMap<>();
                        ban.put("reason", rs.getString("reason"));
                        ban.put("issuer", rs.getString("issuer"));
                        ban.put("timestamp", rs.getLong("timestamp"));
                        ban.put("expiry", expiry);
                        return ban;
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка проверки бана: " + e.getMessage());
                }
                return null;
            }
        });
    }

    public CompletableFuture<List<Map<String, Object>>> getAllBotBans() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                List<Map<String, Object>> bans = new ArrayList<>();
                String sql = "SELECT * FROM bot_bans";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        long expiry = rs.getLong("expiry");
                        if (expiry > 0 && expiry < System.currentTimeMillis()) {
                            continue;
                        }
                        Map<String, Object> ban = new HashMap<>();
                        ban.put("telegram_id", rs.getString("telegram_id"));
                        ban.put("reason", rs.getString("reason"));
                        ban.put("issuer", rs.getString("issuer"));
                        ban.put("timestamp", rs.getLong("timestamp"));
                        ban.put("expiry", expiry);
                        bans.add(ban);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("❌ Ошибка получения банов: " + e.getMessage());
                }
                return bans;
            }
        });
    }

    // =========================================================
    // ==== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ =====
    // =========================================================

    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("✅ Соединение с SQLite закрыто");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("❌ Ошибка закрытия соединения: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public void reconnect() {
        if (!isConnected()) {
            plugin.getLogger().info("🔄 Переподключение к SQLite...");
            connect();
        }
    }

    // =========================================================
    // ==== МИГРАЦИЯ ИЗ YAML В SQLite =====
    // =========================================================

    public CompletableFuture<Boolean> migrateFromYaml(Map<String, Object> yamlData) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                try {
                    connection.setAutoCommit(false);
                    
                    // Миграция игроков
                    if (yamlData.containsKey("players")) {
                        List<Map<String, Object>> players = (List<Map<String, Object>>) yamlData.get("players");
                        for (Map<String, Object> player : players) {
                            String uuid = (String) player.get("uuid");
                            String name = (String) player.get("player_name");
                            String telegram = (String) player.get("telegram_id");
                            String ip = (String) player.get("ip");
                            long session = (long) player.get("session_start");
                            
                            String sql = "INSERT OR REPLACE INTO players (uuid, player_name, telegram_id, ip, session_start) VALUES (?, ?, ?, ?, ?)";
                            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                                ps.setString(1, uuid);
                                ps.setString(2, name);
                                ps.setString(3, telegram);
                                ps.setString(4, ip);
                                ps.setLong(5, session);
                                ps.executeUpdate();
                            }
                        }
                    }
                    
                    connection.commit();
                    plugin.getLogger().info("✅ Миграция данных из YAML в SQLite завершена!");
                    return true;
                    
                } catch (SQLException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException ex) {
                        plugin.getLogger().severe("❌ Ошибка отката транзакции: " + ex.getMessage());
                    }
                    plugin.getLogger().severe("❌ Ошибка миграции: " + e.getMessage());
                    return false;
                } finally {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException e) {
                        plugin.getLogger().severe("❌ Ошибка восстановления autoCommit: " + e.getMessage());
                    }
                }
            }
        });
    }
}
