package com.bags;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class GiveBagCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§cUsage: /givebag <player>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("§7" + args[0] + " is not currently online.");
            return true;
        }
        var leftover = target.getInventory().addItem(BagItem.create());
        if (!leftover.isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover.values().iterator().next());
        }
        target.sendMessage("§aYou received a bag! Shift+right-click it in your inventory to open it.");
        sender.sendMessage("§aGave " + target.getName() + " a new bag.");
        return true;
    }
}
