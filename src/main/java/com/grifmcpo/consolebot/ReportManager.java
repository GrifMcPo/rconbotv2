package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ReportManager {

    private final TelegramConsoleBot plugin;
    private File reportFile;
    private FileConfiguration reportConfig;
    private final Map<Integer, Report> reports = new ConcurrentHashMap<>();
    private int nextId = 1;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");

    public ReportManager(TelegramConsoleBot plugin) {
        this.plugin = plugin;
        loadReports();
        plugin.getLogger().info("✅ ReportManager загружен! Репортов: " + reports.size());
    }

    private void loadReports() {
        reportFile = new File(plugin.getDataFolder(), "reports.yml");
        if (!reportFile.exists()) {
            try {
                reportFile.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().severe("Не удалось создать reports.yml");
            }
        }
        reportConfig = YamlConfiguration.loadConfiguration(reportFile);
        
        reports.clear();
        if (reportConfig.contains("reports")) {
            for (String key : reportConfig.getConfigurationSection("reports").getKeys(false)) {
                int id = Integer.parseInt(key);
                String reporter = reportConfig.getString("reports." + key + ".reporter");
                String target = reportConfig.getString("reports." + key + ".target");
                String reason = reportConfig.getString("reports." + key + ".reason");
                String timestamp = reportConfig.getString("reports." + key + ".timestamp");
                boolean closed = reportConfig.getBoolean("reports." + key + ".closed", false);
                String closedBy = reportConfig.getString("reports." + key + ".closedBy", "");
                
                Report report = new Report(id, reporter, target, reason, timestamp, closed, closedBy);
                reports.put(id, report);
                if (id >= nextId) nextId = id + 1;
            }
        }
    }

    private void saveReports() {
        reportConfig.set("reports", null);
        for (Report report : reports.values()) {
            String key = "reports." + report.id;
            reportConfig.set(key + ".reporter", report.reporter);
            reportConfig.set(key + ".target", report.target);
            reportConfig.set(key + ".reason", report.reason);
            reportConfig.set(key + ".timestamp", report.timestamp);
            reportConfig.set(key + ".closed", report.closed);
            reportConfig.set(key + ".closedBy", report.closedBy);
        }
        try {
            reportConfig.save(reportFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка сохранения reports.yml: " + e.getMessage());
        }
    }

    // =========================================================
    // ==== СОЗДАНИЕ РЕПОРТА =====
    // =========================================================
    public Report createReport(String reporter, String target, String reason) {
        int id = nextId++;
        String timestamp = dateFormat.format(new Date());
        Report report = new Report(id, reporter, target, reason, timestamp, false, "");
        reports.put(id, report);
        saveReports();
        plugin.getLogger().info("📝 Новый репорт #" + id + " от " + reporter + " на " + target);
        return report;
    }

    // =========================================================
    // ==== ЗАКРЫТИЕ РЕПОРТА =====
    // =========================================================
    public boolean closeReport(int id, String closedBy) {
        Report report = reports.get(id);
        if (report == null) return false;
        if (report.closed) return false;
        
        report.closed = true;
        report.closedBy = closedBy;
        saveReports();
        plugin.getLogger().info("📝 Репорт #" + id + " закрыт администратором " + closedBy);
        return true;
    }

    public int closeAllReports(String closedBy) {
        int count = 0;
        for (Report report : reports.values()) {
            if (!report.closed) {
                report.closed = true;
                report.closedBy = closedBy;
                count++;
            }
        }
        if (count > 0) saveReports();
        return count;
    }

    // =========================================================
    // ==== ПОЛУЧЕНИЕ РЕПОРТОВ =====
    // =========================================================
    public List<Report> getActiveReports() {
        List<Report> result = new ArrayList<>();
        for (Report report : reports.values()) {
            if (!report.closed) result.add(report);
        }
        result.sort((a, b) -> Integer.compare(a.id, b.id));
        return result;
    }

    public List<Report> getAllReports() {
        List<Report> result = new ArrayList<>(reports.values());
        result.sort((a, b) -> Integer.compare(a.id, b.id));
        return result;
    }

    public Report getReport(int id) {
        return reports.get(id);
    }

    public String getFormattedReportList(List<Report> reportList) {
        if (reportList.isEmpty()) {
            return "[БОТ] Список жалоб пуст.";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("[БОТ] Список жалоб:\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        
        for (Report report : reportList) {
            sb.append("---Жалоба #").append(report.id).append("---\n");
            sb.append("Ник отправителя: ").append(report.reporter).append("\n");
            sb.append("Ник на кого жалоба: ").append(report.target).append("\n");
            sb.append("Причина жалобы: ").append(report.reason).append("\n");
            sb.append("Время: ").append(report.timestamp).append("\n");
            if (report.closed) {
                sb.append("Закрыта администратором: ").append(report.closedBy).append("\n");
            }
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        }
        
        return sb.toString();
    }

    // =========================================================
    // ==== ВНУТРЕННИЙ КЛАСС =====
    // =========================================================
    public static class Report {
        public final int id;
        public final String reporter;
        public final String target;
        public final String reason;
        public final String timestamp;
        public boolean closed;
        public String closedBy;

        public Report(int id, String reporter, String target, String reason, String timestamp, boolean closed, String closedBy) {
            this.id = id;
            this.reporter = reporter;
            this.target = target;
            this.reason = reason;
            this.timestamp = timestamp;
            this.closed = closed;
            this.closedBy = closedBy;
        }
    }
}
