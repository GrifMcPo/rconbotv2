package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PrivateMessageManager implements CommandExecutor, Listener {

    private final TelegramConsoleBot plugin;
    private final Map<UUID, UUID> lastReply = new HashMap<>();
    private final Map<UUID, Boolean> msgToggle = new HashMap<>();

    public PrivateMessageManager(TelegramConsoleBot plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerCommands();
    }

    private void registerCommands() {
        try {
            plugin.getCommand("msg").setExecutor(this);
            plugin.getCommand("tell").setExecutor(this);
            plugin.getCommand("t").setExecutor(this);
            plugin.getCommand("r").setExecutor(this);
            plugin.getCommand("reply").setExecutor(this);
            plugin.getCommand("togglemsg").setExecutor(this);
            plugin.getCommand("msgtoggle").setExecutor(this);
            plugin.getLogger().info("✅ Команды ЛС зарегистрированы!");
        } catch (NullPointerException e) {
            plugin.getLogger().warning("Некоторые команды ЛС не найдены в plugin.yml");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("togglemsg") || cmd.equals("msgtoggle")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cЭта команда только для игроков!");
                return true;
            }
            return handleToggleMsg((Player) sender);
        }

        if (cmd.equals("r") || cmd.equals("reply")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cЭта команда только для игроков!");
                return true;
            }
            return handleReply((Player) sender, args);
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /" + cmd + " <ник> <сообщение>");
            return true;
        }

        String targetName = args[0];
        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        String senderName = sender instanceof Player ? ((Player) sender).getName() : "Console";

        if (sender instanceof Player) {
            Player p = (Player) sender;
            if (msgToggle.getOrDefault(p.getUniqueId(), false)) {
                p.sendMessage("§cУ вас отключены личные сообщения! Используйте /togglemsg");
                return true;
            }
        }

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sender.sendMessage("§cИгрок " + targetName + " не найден!");
            return true;
        }

        if (sender instanceof Player && sender.equals(target)) {
            sender.sendMessage("§cВы не можете отправить сообщение самому себе!");
            return true;
        }

        if (target instanceof Player && msgToggle.getOrDefault(target.getUniqueId(), false)) {
            sender.sendMessage("§cИгрок " + targetName + " отключил личные сообщения!");
            return true;
        }

        // Сохраняем для /reply
        if (sender instanceof Player) {
            lastReply.put(((Player) sender).getUniqueId(), target.getUniqueId());
            lastReply.put(target.getUniqueId(), ((Player) sender).getUniqueId());
        }

        // Отправка сообщения отправителю
        String senderMsg = "§6[§c" + senderName + " §6-> §c" + target.getName() + "§6]§f " + message;
        sender.sendMessage(senderMsg);

        // Отправка сообщения получателю
        String targetMsg = "§6[§c" + senderName + " §6-> §cя§6]§f " + message;
        target.sendMessage(targetMsg);

        // Логирование в консоль
        plugin.getLogger().info("[PM] " + senderName + " -> " + target.getName() + ": " + message);

        return true;
    }

    private boolean handleReply(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage("§cИспользование: /reply <сообщение>");
            return true;
        }

        UUID lastTarget = lastReply.get(player.getUniqueId());
        if (lastTarget == null) {
            player.sendMessage("§cУ вас нет последнего получателя!");
            return true;
        }

        Player target = Bukkit.getPlayer(lastTarget);
        if (target == null) {
            player.sendMessage("§cИгрок больше не в сети!");
            lastReply.remove(player.getUniqueId());
            return true;
        }

        String message = String.join(" ", args);
        
        if (msgToggle.getOrDefault(target.getUniqueId(), false)) {
            player.sendMessage("§cИгрок " + target.getName() + " отключил личные сообщения!");
            return true;
        }

        // Отправка сообщения отправителю
        String senderMsg = "§6[§c" + player.getName() + " §6-> §c" + target.getName() + "§6]§f " + message;
        player.sendMessage(senderMsg);

        // Отправка сообщения получателю
        String targetMsg = "§6[§c" + player.getName() + " §6-> §cя§6]§f " + message;
        target.sendMessage(targetMsg);

        plugin.getLogger().info("[PM] " + player.getName() + " -> " + target.getName() + ": " + message);
        return true;
    }

    private boolean handleToggleMsg(Player player) {
        boolean current = msgToggle.getOrDefault(player.getUniqueId(), false);
        msgToggle.put(player.getUniqueId(), !current);
        
        if (msgToggle.get(player.getUniqueId())) {
            player.sendMessage("§cВы ОТКЛЮЧИЛИ личные сообщения!");
        } else {
            player.sendMessage("§aВы ВКЛЮЧИЛИ личные сообщения!");
        }
        return true;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastReply.remove(uuid);
        msgToggle.remove(uuid);
    }

    // =========================================================
    // ==== МЕТОД ДЛЯ ОТПРАВКИ ЛС ИЗ БОТА =====
    // =========================================================
    public boolean sendFromBot(String senderName, String targetName, String message) {
        if (senderName == null || senderName.isEmpty()) {
            senderName = "Console";
        }

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            return false;
        }

        if (msgToggle.getOrDefault(target.getUniqueId(), false)) {
            return false;
        }

        // Сохраняем для /reply
        UUID senderUuid = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(senderName)) {
                senderUuid = p.getUniqueId();
                break;
            }
        }
        if (senderUuid != null) {
            lastReply.put(senderUuid, target.getUniqueId());
            lastReply.put(target.getUniqueId(), senderUuid);
        }

        // Сообщение получателю
        String targetMsg = "§6[§c" + senderName + " §6-> §cя§6]§f " + message;
        target.sendMessage(targetMsg);

        plugin.getLogger().info("[PM] " + senderName + " -> " + target.getName() + ": " + message);
        return true;
    }
}
