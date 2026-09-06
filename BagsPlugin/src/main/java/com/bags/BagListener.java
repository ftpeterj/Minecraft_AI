package com.bags;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class BagListener implements Listener {

    private final JavaPlugin plugin;

    public BagListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof BagHolder holder) {
            // The reserved Close button: any click here closes the window
            // and can't be used to store anything (this also blocks placing
            // real items into that slot, which would otherwise be silently
            // discarded since it's never treated as real storage).
            if (event.getRawSlot() == BagItem.CLOSE_BUTTON_SLOT) {
                event.setCancelled(true);
                event.getWhoClicked().closeInventory();
                return;
            }
            // Shift+right-clicking the same bag again while it's open closes
            // it — a quick toggle, matching the gesture that opened it (only
            // reachable if the bag is still visible, e.g. it was opened from
            // your own inventory; the Close button above covers every case).
            if (event.getClick() == ClickType.SHIFT_RIGHT) {
                ItemStack clickedItem = event.getCurrentItem();
                if (BagItem.isBag(clickedItem) && holder.getBagId().equals(BagItem.idOf(clickedItem))) {
                    event.setCancelled(true);
                    event.getWhoClicked().closeInventory();
                    return;
                }
            }
            if (wouldNestBag(event, top)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player p) {
                    p.sendMessage("§cA bag can't hold another bag.");
                }
                return;
            }
            scheduleSync(holder);
            return;
        }

        // Shift+right-clicking a bag opens it, no matter which inventory
        // screen it's currently sitting in — your own, a chest, or another
        // plugin's window (e.g. BotInterop's /botinv) — rather than only
        // when it's in your own inventory. Without this, shift-clicking a
        // bag inside someone else's open inventory just does the default
        // vanilla "move it to my inventory" instead of opening it.
        if (event.getClick() != ClickType.SHIFT_RIGHT) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        ItemStack item = event.getCurrentItem();
        if (clicked == null || !BagItem.isBag(item)) {
            return;
        }

        event.setCancelled(true);
        BagGui.open(player, clicked, BagItem.idOf(item));
    }

    /** True if this click would place/move a bag item into the open bag GUI (nesting), whether via cursor-place or shift-click transfer. */
    private boolean wouldNestBag(InventoryClickEvent event, Inventory top) {
        int topSize = top.getSize();
        int rawSlot = event.getRawSlot();
        boolean targetsTop = rawSlot >= 0 && rawSlot < topSize;

        if (BagItem.isBag(event.getCursor()) && targetsTop) {
            return true; // placing a held bag directly into a bag-GUI slot
        }
        if (event.isShiftClick() && BagItem.isBag(event.getCurrentItem()) && !targetsTop) {
            return true; // shift-clicking a bag from the player's own inventory auto-transfers it into the open (top) bag GUI
        }
        return false;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BagHolder holder)) {
            return;
        }
        if (event.getRawSlots().contains(BagItem.CLOSE_BUTTON_SLOT)) {
            event.setCancelled(true);
            return;
        }
        int topSize = top.getSize();
        boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < topSize);
        if (touchesTop && BagItem.isBag(event.getOldCursor())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player p) {
                p.sendMessage("§cA bag can't hold another bag.");
            }
            return;
        }
        scheduleSync(holder);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BagHolder holder)) {
            return;
        }
        if (!holder.isFlushed()) {
            holder.syncToItem();
        }
        // If the bag was opened from inside another GUI (a chest, /botinv, etc.),
        // go back to that screen instead of dropping the player out to the game
        // entirely. Opening a new inventory from directly inside this event
        // doesn't reliably work in Bukkit while the close is still in progress,
        // so it's deferred a tick, same as scheduleSync() below.
        Inventory source = holder.getSource();
        if (!(source.getHolder() instanceof Player) && event.getPlayer() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(source));
        }
    }

    private void scheduleSync(BagHolder holder) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!holder.getInventory().getViewers().isEmpty()) {
                holder.syncToItem();
            }
        });
    }
}
