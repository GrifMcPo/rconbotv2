package com.grifmcpo.consolebot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class AuthListener implements Listener {

    private final TelegramConsoleBot plugin;
    private final AuthManager authManager;
    private final TelegramBotHandler botHandler;

    public AuthListener(TelegramConsoleBot plugin, AuthManager authManager, TelegramBotHandler botHandler) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.botHandler = botHandler;
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        String ip = player.getAddress() != null ? player.getAddress().getHostString() : "—";

        // 1. Проверка: не забанен ли IP?
        if (authManager.isIpBanned(playerName, ip)) {
            long timeLeft = authManager.getBanTimeLeft(playerName);
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    "§4&lВаш IP адрес был заблокирован!\n" +
                    "§fПричина: §cЗапрет входа на аккаунт. §7(с бота)\n" +
                    "§fСрок: §c&n" + authManager.formatTimeLeft(timeLeft));
            return;
        }

        // 2. Если не привязан — пускаем
        if (!authManager.isLinked(playerName)) {
            event.allow();
            return;
        }

        // 3. Если IP совпадает с привязанным
        String linkedIp = authManager.getLinkedIp(playerName);
        if (linkedIp != null && linkedIp.equals(ip)) {
            // Проверяем сессию
            if (authManager.isSessionValid(playerName)) {
                event.allow();
                return;
            } else {
                // Сессия истекла
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                        "§eВаша сессия истекла!\n" +
                        "§fПодтвердите вход через Telegram бота.");
                // Отправляем запрос в Telegram
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    botHandler.sendSessionExpiredRequest(playerName);
                }, 20L);
                return;
            }
        }

        // 4. Новый IP — блокируем вход
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                "§eВход с нового IP!\n" +
                "§fПодтвердите вход через Telegram бота.");

        // Отправляем запрос в Telegram
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            botHandler.sendAuthRequest(playerName, ip);
        }, 20L);

        // Блокируем движение игроку (он будет стоять на месте)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(playerName);
            if (p != null && p.isOnline()) {
                p.setWalkSpeed(0.0f);
                p.setFlySpeed(0.0f);
                p.sendTitle("§c§lОЖИДАНИЕ ПОДТВЕРЖДЕНИЯ", "§7Подтвердите вход в Telegram боте", 10, 70, 20);
            }
        }, 20L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();

        // Если привязан и сессия валидна — всё ок
        if (authManager.isLinked(playerName) && authManager.isSessionValid(playerName)) {
            player.setWalkSpeed(0.2f);
            player.setFlySpeed(0.1f);
            return;
        }

        // Если привязан, но сессия истекла — напоминаем
        if (authManager.isLinked(playerName) && !authManager.isSessionValid(playerName)) {
            player.sendMessage("§e⚠ Ваша сессия истекла! Подтвердите вход через Telegram бота.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();

        // Сбрасываем скорость при выходе
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
    }

    // Периодическая проверка сессий
    public void startSessionChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    String playerName = player.getName();
                    if (authManager.isLinked(playerName) && !authManager.isSessionValid(playerName)) {
                        player.sendMessage("§e⚠ Ваша сессия истекла! Подтвердите вход через Telegram бота.");
                        player.setWalkSpeed(0.0f);
                        player.setFlySpeed(0.0f);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L * 60 * 5, 20L * 60 * 5); // Проверка каждые 5 минут
    }
}
