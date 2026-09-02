package com.botinterop;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The bot is a real logged-in player account, so its PlayerInventory is a live
 * Bukkit object like any other online player's. Opening it for the clicking
 * player (the same trick /invsee plugins use) gives real, instant drag-and-drop
 * equip/give/take with no syncing needed.
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
        clicker.openInventory(target.getInventory());
        clicker.sendMessage(BotStatusFormatter.format(target));
    }
}
