package com.aibots.listener;

import com.aibots.item.StackSizeService;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps stackable items at configured max (default 99).
 */
public final class StackSizeListener implements Listener {

    private final JavaPlugin plugin;
    private final StackSizeService stacks;

    public StackSizeListener(JavaPlugin plugin, StackSizeService stacks) {
        this.plugin = plugin;
        this.stacks = stacks;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!stacks.enabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player p = event.getPlayer();
            if (p.isOnline()) {
                stacks.applyInventory(p.getInventory());
                stacks.applyInventory(p.getEnderChest());
            }
        }, 10L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!stacks.enabled()) {
            return;
        }
        Item entity = event.getEntity();
        ItemStack stack = entity.getItemStack();
        stacks.apply(stack);
        entity.setItemStack(stack);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!stacks.enabled()) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        stacks.apply(stack);
        event.getItem().setItemStack(stack);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInvOpen(InventoryOpenEvent event) {
        if (!stacks.enabled()) {
            return;
        }
        stacks.applyInventory(event.getInventory());
        if (event.getPlayer() instanceof Player p) {
            stacks.applyInventory(p.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!stacks.enabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.getWhoClicked() instanceof Player p) {
                stacks.applyInventory(p.getInventory());
            }
            if (event.getInventory() != null) {
                stacks.applyInventory(event.getInventory());
            }
            ItemStack cur = event.getCurrentItem();
            if (cur != null) {
                stacks.apply(cur);
            }
            ItemStack cursor = event.getCursor();
            if (cursor != null) {
                stacks.apply(cursor);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!stacks.enabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.getWhoClicked() instanceof Player p) {
                stacks.applyInventory(p.getInventory());
            }
            stacks.applyInventory(event.getInventory());
        });
    }
}
