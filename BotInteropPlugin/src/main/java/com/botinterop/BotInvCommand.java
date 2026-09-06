package com.botinterop;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BotInvCommand implements CommandExecutor {

    private final BotInteropPlugin plugin;

    public BotInvCommand(BotInteropPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage("§cOnly a player can open this.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("§cUsage: /botinv <name>");
            return true;
        }

        String name = args[0];
        if (!plugin.isBotAccount(name)) {
            sender.sendMessage("§c" + name + " is not a configured bot account.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(name);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("§7" + name + " is not currently online.");
            return true;
        }
        if (!plugin.getFriends().isTrusted(viewer.getName())) {
            sender.sendMessage("§c" + name + " doesn't know you well enough to show you their inventory.");
            return true;
        }

        BotGui.openInventory(viewer, target);
        return true;
    }
}
