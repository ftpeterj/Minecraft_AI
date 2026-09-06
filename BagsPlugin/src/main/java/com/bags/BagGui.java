package com.bags;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

final class BagGui {

    private BagGui() {
    }

    /**
     * If the player already has a bag open, sync it to its item right now
     * before opening a new one — opening this one force-closes the old
     * window, which would otherwise fire a second, possibly-stale sync for
     * it (see BotInteropPlugin's identical fix for the same class of bug).
     */
    private static void flushIfOpen(Player viewer) {
        if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof BagHolder holder) {
            holder.syncToItem();
        }
    }

    /** @param source wherever the bag physically is right now — the viewer's own inventory, a chest, another plugin's GUI, etc. */
    static void open(Player viewer, Inventory source, String bagId) {
        flushIfOpen(viewer);

        BagHolder holder = new BagHolder(source, bagId);
        Inventory gui = Bukkit.createInventory(holder, BagItem.SIZE, "Bag");
        holder.setInventory(gui);
        holder.loadFromItem();

        viewer.openInventory(gui);
    }
}
