package com.botinterop;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BotRepairCommand implements CommandExecutor {

    private final BotInteropPlugin plugin;
    private final BotRepairService repair;

    public BotRepairCommand(BotInteropPlugin plugin, BotRepairService repair) {
        this.plugin = plugin;
        this.repair = repair;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            sender.sendMessage("§cUsage: /botrepair <name> <on|off>");
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

        boolean on = args[1].equalsIgnoreCase("on");
        if (on) {
            repair.enable(target);
            sender.sendMessage("§a[BotInterop] Auto-repair ON for " + target.getName() + " — heals ~1% durability every 10s.");
        } else {
            repair.disable(target);
            sender.sendMessage("§7[BotInterop] Auto-repair OFF for " + target.getName() + ".");
        }
        return true;
    }
}
