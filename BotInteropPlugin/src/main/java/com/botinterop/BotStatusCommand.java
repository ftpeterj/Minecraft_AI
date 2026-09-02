package com.botinterop;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BotStatusCommand implements CommandExecutor {

    private final BotInteropPlugin plugin;

    public BotStatusCommand(BotInteropPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§cUsage: /botstatus <name>");
            return true;
        }

        String name = args[0];
        if (!plugin.isBotAccount(name)) {
            sender.sendMessage("§c" + name + " is not a configured bot account.");
            return true;
        }

        Player bot = Bukkit.getPlayerExact(name);
        if (bot == null || !bot.isOnline()) {
            sender.sendMessage("§7" + name + " is not currently online.");
            return true;
        }

        sender.sendMessage(BotStatusFormatter.format(bot));
        return true;
    }
}
