package com.botinterop;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Reports a player's real, server-authoritative position in plain text.
 * Added 2026-09-07: the mineflayer bot's own client-side entity tracking is
 * unreliable on this unofficial-26.2-protocol setup (confirmed live — a
 * player only ~12 blocks away never appeared in bot.entities/bot.players[x]
 * .entity at all), the same general class of bug as the item-id mismatch
 * fixed earlier via /botinv list. This command lets the bot self-query a
 * player's true position from the server instead of trusting its own
 * (apparently broken) entity-spawn packet handling.
 */
public class WhereIsCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§cUsage: /whereis <player>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("§7" + args[0] + " is not currently online.");
            return true;
        }

        Location loc = target.getLocation();
        sender.sendMessage(String.format(
            "§9%s's position: x=%.1f, y=%.1f, z=%.1f, dimension=%s",
            target.getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getWorld().getName()
        ));
        return true;
    }
}
