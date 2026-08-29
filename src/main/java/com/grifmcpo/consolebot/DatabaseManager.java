package com.grifmcpo.consolebot;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class DatabaseManager {

    private final JavaPlugin plugin;
    private Connection connection;
    private String dbType; // "sqlite" или "mysql"
    
    // Настройки MySQL
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
        connect();
        createTables();
    }

    private void loadConfig() {
        dbType = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        
        if (dbType.equals("mysql")) {
            host = plugin.getConfig().getString("database.mysql.host", "localhost");
            port = plugin.getConfig().getInt("database.mysql.port", 3306);
            database = plugin.getConfig().getString("database.mysql.database", "minecraft");
            username = plugin.getConfig().getString("database.mysql.username", "root");
            password = plugin.getConfig().getString("database.mysql.password", "");
        }
    }

    private void connect() {
        try {
            if (dbType.equals("mysql")) {
                Class.forName("com.mysql.jdbc.Driver");
                String url = "jdbc:mysql://" + host + ":" + port + "/" + database + 
                            "?useSSL=false&autoReconnect=true&characterEncoding=utf8";
                connection = DriverManager.getConnection(url, username, password);
                plugin.getLogger().info("✅ Подключение к MySQL успешно!");
            } else {
                Class.forName("org.sqlite.JDBC");
                String url = "jdbc:sqlite:" + plugin.getDataFolder() + "/database.db";
                connection = DriverManager.getConnection(url);
                plugin.getLogger().info("✅ Подключение к SQLite успешно!");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Ошибка подключения к БД: " + e.getMessage());
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
                session_start BIGINT,
                blocked BOOLEAN DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
                timestamp BIGINT NOT NULL,
                expiry BIGINT,
                hidden BOOLEAN DEFAULT 0,
                active BOOLEAN DEFAULT 1,
                FOREIGN KEY (player_uuid) REFERENCES players(uuid)
            )
        """;

        String commandLogsTable = """
            CREATE TABLE IF NOT EXISTS command_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid VARCHAR(36) NOT NULL,
                command TEXT NOT NULL,
                timestamp BIGINT NOT NULL,
                FOREIGN KEY (player_uuid) REFERENCES players(uuid)
            )
        """;

        String botBansTable = """
            CREATE TABLE IF NOT EXISTS bot_bans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                telegram_id VARCHAR(20) NOT NULL UNIQUE,
                reason TEXT,
                issuer VARCHAR(20),
                timestamp BIGINT NOT NULL,
                expiry BIGINT
            )
        """;

        // Создаем индексы для быстрого поиска
        String index1 = "CREATE INDEX IF NOT EXISTS idx_players_uuid ON players(uuid)";
        String index2 = "CREATE INDEX IF NOT EXISTS idx_players_telegram ON players(telegram_id)";
        String index3 = "CREATE INDEX IF NOT EXISTS idx_punishments_player ON punishments(player_uuid)";
        String index4 = "CREATE INDEX IF NOT EXISTS idx_punishments_active ON punishments(active)";
        String index5 = "CREATE INDEX IF NOT EXISTS idx_logs_player ON command_logs(player_uuid)";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(playersTable);
            stmt.execute(punishmentsTable);
            stmt.execute(commandLogsTable);
            stmt.execute(botBansTable);
            stmt.execute(index1);
            stmt.execute(index2);
            stmt.execute(index3);
            stmt.execute(index4);
            stmt.execute(index5);
            plugin.getLogger().info("✅ Таблицы созданы/проверены!");
        } catch (SQLException e) {
            plugin.getLogger().severe("❌ Ошибка создания таблиц: " + e.getMessage());
        }
    }

    // =========================================================
    // ==== МЕТОДЫ ДЛЯ РАБОТЫ С ИГРОКАМИ =====
    // =========================================================

    public CompletableFuture<Boolean> linkPlayer(String uuid, String playerName, String telegramId, String ip) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT OR REPLACE INTO players (uuid, player_name, telegram_id, ip, session_start) VALUES (?, ?, ?, ?, ?)";
            if (dbType.equals("mysql")) {
                sql = "INSERT INTO players (uuid, player_name, telegram_id, ip, session_start) " +
                      "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE player_name=?, telegram_id=?, ip=?, session_start=?";
            }

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid);
                ps.setString(2, playerName);
                ps.setString(3, telegramId);
                ps.setString(4, ip);
                ps.setLong(5, System.currentTimeMillis());
                
                if (dbType.equals("mysql")) {
                    ps.setString(6, playerName);
                    ps.setString(7, telegramId);
                    ps.setString(8, ip);
                    ps.setLong(9, System.currentTimeMillis());
                }
                
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("❌ Ошибка привязки игрока: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> unlinkPlayer(String uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "UPDATE players SET telegram_id = NULL, session_start = 0 WHERE uuid = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("❌ Ошибка отвязки игрока: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Map<String, Object>> getPlayer(String uuid) {
        return CompletableFuture.supplyAsync(() -> {
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
                    player.put("blocked", rs.getBoolean("blocked"));
                    return player;
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("❌ Ошибка получения игрока: " + e.getMessage());
            }
            return null;
        });
    }

    public CompletableFuture<String> getPlayerByTelegram(String telegramId) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    // =========================================================
    // ==== МЕТОДЫ ДЛЯ РАБОТЫ С НАКАЗАНИЯМИ =====
    // =========================================================

    public CompletableFuture<Boolean> addPunishment(String playerUuid, String type, String issuer, 
                                                     String reason, String duration, long expiry, boolean hidden) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO punishments (player_uuid, type, issuer, reason, duration, timestamp, expiry, hidden) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid);
                ps.setString(2, type);
                ps.setString(3, issuer);
                ps.setString(4, reason);
                ps.setString(5, duration);
                ps.setLong(6, System.currentTimeMillis());
                ps.setLong(7, expiry);
                ps.setBoolean(8, hidden);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("❌ Ошибка добавления наказания: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<List<Map<String, Object>>> getActivePunishments(String playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
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
                    p.put("hidden", rs.getBoolean("hidden"));
                    punishments.add(p);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("❌ Ошибка получения наказаний: " + e.getMessage());
            }
            return punishments;
        });
    }

    public CompletableFuture<Boolean> deactivatePunishment(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "UPDATE punishments SET active = 0 WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, id);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("❌ Ошибка деактивации наказания: " + e.getMessage());
                return false;
            }
        });
    }

    // =========================================================
    // ==== МЕТОДЫ ДЛЯ ЛОГОВ КОМАНД =====
    // =========================================================

    public CompletableFuture<Void> logCommand(String playerUuid, String command) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO command_logs (player_uuid, command, timestamp) VALUES (?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid);
                ps.setString(2, command);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("❌ Ошибка логирования команды: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<List<Map<String, Object>>> getCommandLogs(String playerUuid, int limit, int days) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    // =========================================================
    // ==== МЕТОДЫ ДЛЯ БАНОВ БОТА =====
    // =========================================================

    public CompletableFuture<Boolean> banBotUser(String telegramId, String reason, String issuer, long expiry) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT OR REPLACE INTO bot_bans (telegram_id, reason, issuer, timestamp, expiry) VALUES (?, ?, ?, ?, ?)";
            if (dbType.equals("mysql")) {
                sql = "INSERT INTO bot_bans (telegram_id, reason, issuer, timestamp, expiry) " +
                      "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE reason=?, issuer=?, timestamp=?, expiry=?";
            }

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                long now = System.currentTimeMillis();
                ps.setString(1, telegramId);
                ps.setString(2, reason);
                ps.setString(3, issuer);
                ps.setLong(4, now);
                ps.setLong(5, expiry);
                
                if (dbType.equals("mysql")) {
                    ps.setString(6, reason);
                    ps.setString(7, issuer);
                    ps.setLong(8, now);
                    ps.setLong(9, expiry);
                }
                
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("❌ Ошибка бана пользователя: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> unbanBotUser(String telegramId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM bot_bans WHERE telegram_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, telegramId);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("❌ Ошибка разбана пользователя: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Map<String, Object>> getBotBan(String telegramId) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    // =========================================================
    // ==== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ =====
    // =========================================================

    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("✅ Соединение с БД закрыто");
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
            plugin.getLogger().info("🔄 Переподключение к БД...");
            connect();
        }
    }

    // Преобразование UUID <-> ник (для совместимости)
    public CompletableFuture<String> getPlayerName(String uuid) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    public CompletableFuture<String> getPlayerUuid(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    // Очистка старых логов (раз в день)
    public void cleanOldLogs(int days) {
        CompletableFuture.runAsync(() -> {
            long cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
            String sql = "DELETE FROM command_logs WHERE timestamp < ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, cutoff);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    plugin.getLogger().info("🗑️ Удалено старых логов: " + deleted);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("❌ Ошибка очистки логов: " + e.getMessage());
            }
        });
    }
}
