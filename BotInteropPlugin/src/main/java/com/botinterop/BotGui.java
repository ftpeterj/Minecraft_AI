package com.botinterop;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class BotGui {

    private BotGui() {
    }

    /**
     * If the viewer already has one of our bot GUIs open, flush its edits to
     * the real target inventory right now, before we snapshot fresh state
     * into a new window. Without this, re-running /botinv or /botarmor while
     * a previous window is still open force-closes it — and loading a new
     * snapshot from the target before that old window's close-sync runs
     * means the new window can start from stale data, or the old window's
     * delayed close-sync can clobber what the new window just captured.
     */
    private static void flushIfOpen(Player viewer) {
        InventoryHolder holder = viewer.getOpenInventory().getTopInventory().getHolder();
        if (holder instanceof BotInventoryHolder h) {
            h.syncToTarget();
        } else if (holder instanceof BotArmorHolder h) {
            h.syncRejectingInvalid();
        }
    }

    static void openInventory(Player viewer, Player target) {
        flushIfOpen(viewer);

        BotInventoryHolder holder = new BotInventoryHolder(target);
        Inventory gui = Bukkit.createInventory(holder, BotInventoryHolder.SIZE, target.getName());
        holder.setInventory(gui);
        holder.loadFromTarget();

        viewer.openInventory(gui);
        viewer.sendMessage(BotStatusFormatter.format(target));
    }

    static void openArmor(Player viewer, Player target) {
        flushIfOpen(viewer);

        BotArmorHolder holder = new BotArmorHolder(target);
        Inventory gui = Bukkit.createInventory(holder, BotArmorHolder.SIZE, target.getName() + " armor");
        holder.setInventory(gui);
        holder.loadFromTarget();

        viewer.openInventory(gui);
        viewer.sendMessage(BotStatusFormatter.format(target));
    }

    static void openTrade(Player viewer, Player target) {
        flushIfOpen(viewer);

        BotTradeHolder holder = new BotTradeHolder(target, viewer);
        Inventory gui = Bukkit.createInventory(holder, BotTradeHolder.SIZE, target.getName() + " trade");
        holder.setInventory(gui);
        holder.loadCatalog();

        viewer.openInventory(gui);
        viewer.sendMessage("§7Top row: click an item to mark it as wanted. Bottom row: place what you're offering. Close when done.");
    }
}
