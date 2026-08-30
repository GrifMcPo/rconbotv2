    // =========================================================
    // ==== TEX (Технические работы) =====
    // =========================================================
    private void handleTechWorks(long chatId, String cmd, long userId) {
        String[] parts = cmd.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "[БОТ] Использование: !rcon global tex <on/off/auto/status>");
            return;
        }

        String action = parts[1].toLowerCase();
        String issuer = plugin.getCustomSender(userId);
        if (issuer == null) issuer = "RCON@" + userId;

        switch (action) {
            case "on": {
                if (parts.length < 3) {
                    sendMessage(chatId, "[БОТ] Использование: !rcon global tex on <причина> [время]");
                    sendMessage(chatId, "[БОТ] Пример: !rcon global tex on &cТехнические работы! 1h");
                    return;
                }

                if (!plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
                    sendMessage(chatId, "[БОТ] У вас нет прав!");
                    return;
                }

                String reason = parts[2];
                String duration = null;
                if (parts.length >= 4) {
                    duration = parts[3];
                }

                boolean success = plugin.getTechWorksManager().turnOn(issuer, reason, duration);
                if (success) {
                    String msg = "[БОТ] 🔧 ТЕХНИЧЕСКИЕ РАБОТЫ ВКЛЮЧЕНЫ!\n" +
                            "Причина: " + reason + "\n" +
                            "Администратор: " + issuer + "\n" +
                            "Окончание: " + plugin.getTechWorksManager().getEndTimeFormatted() + "\n" +
                            "Осталось: " + plugin.getTechWorksManager().getTimeLeft();
                    sendMessage(chatId, msg);
                } else {
                    sendMessage(chatId, "[БОТ] Технические работы уже включены!");
                }
                break;
            }

            case "off": {
                if (!plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
                    sendMessage(chatId, "[БОТ] У вас нет прав!");
                    return;
                }

                boolean success = plugin.getTechWorksManager().turnOff();
                if (success) {
                    sendMessage(chatId, "[БОТ] 🔧 Технические работы ВЫКЛЮЧЕНЫ!");
                } else {
                    sendMessage(chatId, "[БОТ] Технические работы и так выключены!");
                }
                break;
            }

            case "auto": {
                if (parts.length < 4) {
                    sendMessage(chatId, "[БОТ] Использование: !rcon global tex auto <время> <причина>");
                    sendMessage(chatId, "[БОТ] Пример: !rcon global tex auto 1h &cПлановые работы");
                    return;
                }

                if (!plugin.isAdmin(userId) && userId != plugin.getOwnerId()) {
                    sendMessage(chatId, "[БОТ] У вас нет прав!");
                    return;
                }

                String duration = parts[2];
                String reason = String.join(" ", Arrays.copyOfRange(parts, 3, parts.length));

                boolean success = plugin.getTechWorksManager().setAutoStart(duration, reason);
                if (success) {
                    sendMessage(chatId, "[БОТ] ⏰ Авто-включение тех. работ запланировано!\n" +
                            "Через: " + duration + "\n" +
                            "Причина: " + reason);
                } else {
                    sendMessage(chatId, "[БОТ] Неверный формат времени! Используйте: 1h, 30m, 1d и т.д.");
                }
                break;
            }

            case "status": {
                if (!plugin.getTechWorksManager().isTechMode()) {
                    sendMessage(chatId, "[БОТ] Технические работы ВЫКЛЮЧЕНЫ");
                } else {
                    String msg = "[БОТ] 🔧 Технические работы ВКЛЮЧЕНЫ!\n" +
                            "Причина: " + plugin.getTechWorksManager().getKickReason() + "\n" +
                            "Администратор: " + plugin.getTechWorksManager().getAdminWhoStarted() + "\n" +
                            "Начало: " + plugin.getTechWorksManager().getStartTime() + "\n" +
                            "Окончание: " + plugin.getTechWorksManager().getEndTimeFormatted() + "\n" +
                            "Осталось: " + plugin.getTechWorksManager().getTimeLeft();
                    sendMessage(chatId, msg);
                }
                break;
            }

            default:
                sendMessage(chatId, "[БОТ] Неизвестная подкоманда!\n" +
                        "Использование: !rcon global tex <on/off/auto/status>");
                break;
        }
    }
