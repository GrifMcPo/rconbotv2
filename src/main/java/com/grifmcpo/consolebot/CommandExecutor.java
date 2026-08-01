package com.grifmcpo.consolebot; 

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class CommandExecutor {

    private final JavaPlugin plugin;

    public CommandExecutor(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public String executeCommand(String command, String senderName) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        plugin.getLogger().info("Команда выполнена: " + command + " (от " + senderName + ")");
        return "Команда выполнена: " + command;
    }

    public void close() {}

    public void reconnect() {}

    public boolean isConnected() {
        return true;
    }
}
