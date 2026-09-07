package com.botinterop;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Finds the nearest block of a given real Material near the sender, scanned
 * live via Bukkit server-side. Added 2026-09-07 after confirming that the
 * mineflayer bot's own client-side block-type matching is unreliable on this
 * unofficial-26.2 setup too, not just items/entities: searching for a
 * furnace/smoker actually matched a real campfire instead (the same general
 * class of bug as the item-id/entity-id corruption already fixed elsewhere),
 * causing bot.openFurnace() to hang waiting for a window that could never
 * open. This lets the bot ask the server directly instead of trusting its
 * own (apparently unreliable) local block-type decoding.
 */
public class FindBlockCommand implements CommandExecutor {

    private static final int MAX_RADIUS = 32;
    private static final int VERTICAL_RANGE = 6;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly a player can use this.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /findblock <material1,material2,...> [radius]");
            return true;
        }

        String[] wanted = args[0].toUpperCase().split(",");
        int radius = Math.min(MAX_RADIUS, args.length >= 2 ? parseIntOr(args[1], 16) : 16);

        Location origin = player.getLocation();
        World world = origin.getWorld();
        Block nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -VERTICAL_RANGE; dy <= VERTICAL_RANGE; dy++) {
                    Block block = world.getBlockAt(originX + dx, originY + dy, originZ + dz);
                    Material type = block.getType();
                    for (String w : wanted) {
                        if (!type.name().equals(w.trim())) continue;
                        double distSq = block.getLocation().distanceSquared(origin);
                        if (distSq < nearestDistSq) {
                            nearestDistSq = distSq;
                            nearest = block;
                        }
                    }
                }
            }
        }

        if (nearest == null) {
            sender.sendMessage("§7No matching block found within " + radius + " blocks.");
            return true;
        }
        sender.sendMessage("§9Found " + nearest.getType() + " at " + nearest.getX() + "," + nearest.getY() + "," + nearest.getZ());
        return true;
    }

    private int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
