package com.grifmcpo.consolebot; 

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

public class PrivateMessageCommands implements CommandExecutor {

    private final PrivateMessageManager manager;

    public PrivateMessageCommands(PrivateMessageManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        // ===== Toggle PM =====
        if (cmd.equals("togglemsg") || cmd.equals("msgtoggle")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cЭта команда только для игроков!");
                return true;
            }
            manager.toggleMsg((Player) sender);
            return true;
        }

        // ===== Reply =====
        if (cmd.equals("r") || cmd.equals("reply")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cЭта команда только для игроков!");
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage("§cИспользование: /reply <сообщение>");
                return true;
            }
            String message = String.join(" ", args);
            manager.handleReply((Player) sender, message);
            return true;
        }

        // ===== msg, tell, t =====
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /" + cmd + " <ник> <сообщение>");
            return true;
        }

        String targetName = args[0];
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        if (sender instanceof Player) {
            return manager.sendFromPlayer((Player) sender, targetName, message);
        } else {
            sender.sendMessage("§cЭта команда только для игроков!");
            return true;
        }
    }
}
