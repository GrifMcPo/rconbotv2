package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PrivateMessageManager implements Listener {

    private final TelegramConsoleBot plugin;
    private final Map<UUID, UUID> lastReply = new HashMap<>();
    private final Map<UUID, Boolean> msgToggle = new HashMap<>();

    public PrivateMessageManager(TelegramConsoleBot plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("✅ PrivateMessageManager загружен!");
    }

    // =========================================================
    // ==== ОТПРАВКА ИЗ БОТА =====
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

    // =========================================================
    // ==== ОТПРАВКА ОТ ИГРОКА =====
    // =========================================================
    public boolean sendFromPlayer(Player sender, String targetName, String message) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sender.sendMessage("§cИгрок " + targetName + " не найден!");
            return false;
        }

        if (sender.equals(target)) {
            sender.sendMessage("§cВы не можете отправить сообщение самому себе!");
            return false;
        }

        if (msgToggle.getOrDefault(target.getUniqueId(), false)) {
            sender.sendMessage("§cИгрок " + targetName + " отключил личные сообщения!");
            return false;
        }

        // Сохраняем для /reply
        lastReply.put(sender.getUniqueId(), target.getUniqueId());
        lastReply.put(target.getUniqueId(), sender.getUniqueId());

        // Отправка сообщения отправителю
        String senderMsg = "§6[§cя §6-> §c" + target.getName() + "§6]§f " + message;
        sender.sendMessage(senderMsg);

        // Отправка сообщения получателю
        String targetMsg = "§6[§c" + sender.getName() + " §6-> §cя§6]§f " + message;
        target.sendMessage(targetMsg);

        plugin.getLogger().info("[PM] " + sender.getName() + " -> " + target.getName() + ": " + message);
        return true;
    }

    // =========================================================
    // ==== ОТВЕТ =====
    // =========================================================
    public boolean handleReply(Player player, String message) {
        UUID lastTarget = lastReply.get(player.getUniqueId());
        if (lastTarget == null) {
            player.sendMessage("§cУ вас нет последнего получателя!");
            return false;
        }

        Player target = Bukkit.getPlayer(lastTarget);
        if (target == null) {
            player.sendMessage("§cИгрок больше не в сети!");
            lastReply.remove(player.getUniqueId());
            return false;
        }

        if (msgToggle.getOrDefault(target.getUniqueId(), false)) {
            player.sendMessage("§cИгрок " + target.getName() + " отключил личные сообщения!");
            return false;
        }

        // Отправка сообщения отправителю
        String senderMsg = "§6[§cя §6-> §c" + target.getName() + "§6]§f " + message;
        player.sendMessage(senderMsg);

        // Отправка сообщения получателю
        String targetMsg = "§6[§c" + player.getName() + " §6-> §cя§6]§f " + message;
        target.sendMessage(targetMsg);

        plugin.getLogger().info("[PM] " + player.getName() + " -> " + target.getName() + ": " + message);
        return true;
    }

    // =========================================================
    // ==== ВКЛ/ВЫКЛ ЛС =====
    // =========================================================
    public boolean toggleMsg(Player player) {
        boolean current = msgToggle.getOrDefault(player.getUniqueId(), false);
        msgToggle.put(player.getUniqueId(), !current);
        
        if (msgToggle.get(player.getUniqueId())) {
            player.sendMessage("§cВы ОТКЛЮЧИЛИ личные сообщения!");
        } else {
            player.sendMessage("§aВы ВКЛЮЧИЛИ личные сообщения!");
        }
        return true;
    }

    public boolean isMsgEnabled(Player player) {
        return !msgToggle.getOrDefault(player.getUniqueId(), false);
    }

    // =========================================================
    // ==== СОБЫТИЯ =====
    // =========================================================
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastReply.remove(uuid);
        msgToggle.remove(uuid);
    }
}
