package com.botinterop;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Plain-text listing of any container block's contents (chest, barrel,
 * furnace, hopper, etc. — anything backed by an InventoryHolder), read via
 * Bukkit server-side. Same reasoning as /botinv list: the mineflayer bot's
 * own client-side item-id decoding is unreliable on this unofficial-26.2
 * setup, so this lets it check a chest for food (or anything else) without
 * trusting its own corrupted view of a chest's window contents.
 */
public class WhatsInCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("§cUsage: /whatsin <x> <y> <z>");
            return true;
        }

        int x, y, z;
        try {
            x = Integer.parseInt(args[0]);
            y = Integer.parseInt(args[1]);
            z = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cCoordinates must be integers.");
            return true;
        }

        World world = sender instanceof Player player ? player.getWorld() : Bukkit.getWorlds().get(0);
        Block block = world.getBlockAt(x, y, z);
        if (!(block.getState() instanceof InventoryHolder holder)) {
            sender.sendMessage("§7No container at " + x + "," + y + "," + z + " (" + block.getType() + ").");
            return true;
        }

        Inventory inv = holder.getInventory();
        sender.sendMessage("§9Container at " + x + "," + y + "," + z + " (" + block.getType() + "):");
        boolean any = false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            any = true;
            String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? item.getItemMeta().getDisplayName() : item.getType().name();
            sender.sendMessage("§9  slot " + i + ": §f" + item.getAmount() + "x " + name);
        }
        if (!any) sender.sendMessage("§7  (empty)");
        return true;
    }
}
