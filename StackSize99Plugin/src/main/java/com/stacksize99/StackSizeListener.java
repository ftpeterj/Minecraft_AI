package com.stacksize99;

import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Every path an ItemStack can enter a player's hands: crafting, smelting,
 * block/mob drops, pickups, inventory clicks (covers /give, creative, trades),
 * plus a retroactive sweep on join and on opening any container, so existing
 * items eventually get caught too.
 */
public class StackSizeListener implements Listener {

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        StackSizeUtil.boost(event.getInventory().getResult());
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        StackSizeUtil.boost(event.getCurrentItem());
        StackSizeUtil.boost(event.getRecipe().getResult());
    }

    @EventHandler
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        StackSizeUtil.boostAll(event.getPlayer().getInventory());
    }

    @EventHandler
    public void onBlockDrop(BlockDropItemEvent event) {
        for (Item item : event.getItems()) {
            StackSizeUtil.boost(item.getItemStack());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        for (ItemStack drop : event.getDrops()) {
            StackSizeUtil.boost(drop);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        StackSizeUtil.boost(event.getItem().getItemStack());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        StackSizeUtil.boost(event.getCurrentItem());
        StackSizeUtil.boost(event.getCursor());
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        StackSizeUtil.boostAll(event.getInventory());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        StackSizeUtil.boostAll(event.getPlayer().getInventory());
        StackSizeUtil.boostAll(event.getPlayer().getEnderChest());
    }
}
