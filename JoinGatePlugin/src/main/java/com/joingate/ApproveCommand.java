package com.joingate;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

public class ApproveCommand implements CommandExecutor, TabCompleter {

    private final JoinGatePlugin plugin;

    public ApproveCommand(JoinGatePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("Usage: /approve <playername>");
            return true;
        }
        String name = args[0];

        // OfflinePlayer lookup by name can block on a Mojang API call for an unseen
        // name, so resolve it off the main thread and hop back to reply/log.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(name);
            target.setWhitelisted(true);
            Bukkit.getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§a[JoinGate] " + name + " is now whitelisted. They can reconnect now."));
            plugin.getLogger().info(sender.getName() + " whitelisted " + name + " via /approve.");
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
