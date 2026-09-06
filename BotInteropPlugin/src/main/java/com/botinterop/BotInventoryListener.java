package com.botinterop;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;

/**
 * The bot is a real logged-in player account, but Bukkit only lets the
 * OWNING player open their own PlayerInventory with armor/offhand visible;
 * a plain openInventory(otherPlayer.getInventory()) only shows the 36
 * main/hotbar slots. So instead we build custom GUIs (see BotInventoryHolder,
 * BotArmorHolder) seeded from the bot's real inventory, and sync any edits
 * back to the bot's real inventory on every click/drag/close.
 */
public class BotInventoryListener implements Listener {

    private final BotInteropPlugin plugin;

    public BotInventoryListener(BotInteropPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        // Bukkit can fire this once per hand; only act on the main-hand event.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }
        if (!plugin.isBotAccount(target.getName())) {
            return;
        }

        Player clicker = event.getPlayer();
        if (!clicker.hasPermission("botinterop.use")) {
            return;
        }

        event.setCancelled(true);
        BotGui.openInventory(clicker, target);
    }

    /**
     * Schedules a sync one tick later (click/drag results aren't applied to
     * the Inventory's backing array until after the event finishes). If the
     * player has already closed the window by then, onClose has already done
     * the authoritative final sync and Bukkit may have cleared this
     * now-viewerless Inventory's contents — so skip, or we'd overwrite good
     * data with empty slots.
     */
    private void scheduleSync(Inventory inventory, Runnable syncer) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!inventory.getViewers().isEmpty()) {
                syncer.run();
            }
        });
    }

    private Runnable syncerFor(Inventory inventory) {
        Object holder = inventory.getHolder();
        if (holder instanceof BotInventoryHolder h) {
            return h::syncToTarget;
        }
        if (holder instanceof BotArmorHolder h) {
            return h::syncRejectingInvalid;
        }
        return null;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        int rawSlot = event.getRawSlot();
        boolean clickedTop = rawSlot >= 0 && rawSlot < top.getSize();
        if (top.getHolder() instanceof BotArmorHolder && clickedTop && !BotArmorHolder.isInteractiveSlot(rawSlot)) {
            event.setCancelled(true);
            return;
        }
        Runnable syncer = syncerFor(top);
        if (syncer != null) {
            scheduleSync(top, syncer);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof BotArmorHolder) {
            boolean touchesNonInteractive = event.getRawSlots().stream()
                    .anyMatch(slot -> slot < top.getSize() && !BotArmorHolder.isInteractiveSlot(slot));
            if (touchesNonInteractive) {
                event.setCancelled(true);
                return;
            }
        }
        Runnable syncer = syncerFor(top);
        if (syncer != null) {
            scheduleSync(top, syncer);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Object holder = event.getInventory().getHolder();
        // If BotGui.flushIfOpen already synced this exact holder (because opening
        // a replacement window force-closed it), skip — re-syncing here would read
        // this now-viewerless Inventory after Bukkit may have reset its contents,
        // clobbering the good data that was just correctly written.
        if (holder instanceof BotInventoryHolder h && h.isFlushed()) {
            return;
        }
        if (holder instanceof BotArmorHolder h && h.isFlushed()) {
            return;
        }
        Runnable syncer = syncerFor(event.getInventory());
        if (syncer != null) {
            syncer.run();
        }
    }
}
