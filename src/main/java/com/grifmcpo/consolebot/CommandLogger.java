package com.grifmcpo.consolebot;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class CommandLogger {

    private final JavaPlugin plugin;
    private final List<LogEntry> logCache = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyy-MM-dd");
    private int maxLogs = 10000;

    public CommandLogger(JavaPlugin plugin) {
        this.plugin = plugin;
        dateFormat.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));
        fileDateFormat.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));
        loadLogs();
    }

    public void logCommand(String playerName, String command) {
        String timestamp = dateFormat.format(new Date());
        LogEntry entry = new LogEntry(timestamp, playerName, command);
        logCache.add(entry);
        if (logCache.size() >= 100) {
            saveLogs();
        }
    }

    public void saveLogs() {
        if (logCache.isEmpty()) return;
        String today = fileDateFormat.format(new Date());
        File logFile = new File(plugin.getDataFolder(), "logs_" + today + ".txt");
        try (FileWriter fw = new FileWriter(logFile, true); BufferedWriter bw = new BufferedWriter(fw)) {
            for (LogEntry entry : logCache) {
                bw.write(entry.toString());
                bw.newLine();
            }
            logCache.clear();
        } catch (IOException e) {
            plugin.getLogger().warning("Ошибка сохранения логов: " + e.getMessage());
        }
    }

    private void loadLogs() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) return;
        File[] files = dataFolder.listFiles((dir, name) -> name.startsWith("logs_") && name.endsWith(".txt"));
        if (files == null) return;
        for (File file : files) {
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    String[] parts = line.split(" \\| ", 3);
                    if (parts.length == 3) {
                        logCache.add(new LogEntry(parts[0], parts[1], parts[2]));
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Ошибка загрузки логов из " + file.getName());
            }
        }
        if (logCache.size() > maxLogs) {
            logCache.subList(0, logCache.size() - maxLogs).clear();
        }
    }

    public List<LogEntry> getLogs(String playerName, int days) {
        Date cutoff = new Date(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));
        return logCache.stream()
                .filter(entry -> {
                    try {
                        Date entryDate = sdf.parse(entry.timestamp);
                        return entry.playerName.equalsIgnoreCase(playerName) && entryDate.after(cutoff);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .sorted((a, b) -> b.timestamp.compareTo(a.timestamp))
                .collect(Collectors.toList());
    }

    public String getFormattedLogs(String playerName, int limit) {
        List<LogEntry> logs = getLogs(playerName, 30);
        if (logs.isEmpty()) {
            return "[БОТ] Нет логов для " + playerName;
        }

        List<LogEntry> recent = logs.stream()
            .limit(limit > 0 ? limit : 10)
            .collect(Collectors.toList());

        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));

        StringBuilder sb = new StringBuilder();
        sb.append("[БОТ] Ответ сервера:\n");
        sb.append("Логи игрока ").append(playerName).append(" (последние ").append(recent.size()).append("):\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        for (LogEntry entry : recent) {
            String time = entry.timestamp;
            try {
                SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                inputFormat.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));
                Date date = inputFormat.parse(entry.timestamp);
                time = sdf.format(date);
            } catch (Exception e) {}
            sb.append(time).append(" | ").append(entry.command).append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Всего записей: ").append(logs.size());

        return sb.toString();
    }

    public static class LogEntry {
        public final String timestamp;
        public final String playerName;
        public final String command;

        public LogEntry(String timestamp, String playerName, String command) {
            this.timestamp = timestamp;
            this.playerName = playerName;
            this.command = command;
        }

        @Override
        public String toString() {
            return timestamp + " | " + playerName + " | " + command;
        }
    }
}
