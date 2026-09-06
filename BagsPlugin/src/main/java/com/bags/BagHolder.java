package com.bags;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Holds the open GUI for one bag. `source` is whichever real Inventory the
 * bag was actually clicked in — your own inventory, a chest, or another
 * plugin's window (e.g. BotInterop's /botinv) — so this works no matter
 * where the bag currently lives, not just your own inventory. Re-locates the
 * physical bag item by its id every time it syncs (rather than trusting a
 * remembered slot index), since that source inventory could shuffle around
 * while the bag GUI is open.
 */
public class BagHolder implements InventoryHolder {

    private final Inventory source;
    private final String bagId;
    private Inventory inventory;
    private boolean flushed = false;

    public BagHolder(Inventory source, String bagId) {
        this.source = source;
        this.bagId = bagId;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public boolean isFlushed() {
        return flushed;
    }

    public String getBagId() {
        return bagId;
    }

    /** The real inventory the bag was clicked in — a player's own inventory, a chest, or another plugin's GUI. */
    public Inventory getSource() {
        return source;
    }

    /** Finds the physical bag item's current slot in the source inventory (plus off-hand, if the source is a real player's), or -1 if it's gone. */
    private int findSlot() {
        for (int i = 0; i < source.getSize(); i++) {
            ItemStack item = source.getItem(i);
            if (BagItem.isBag(item) && bagId.equals(BagItem.idOf(item))) {
                return i;
            }
        }
        if (source.getHolder() instanceof Player p) {
            ItemStack offhand = p.getInventory().getItemInOffHand();
            if (BagItem.isBag(offhand) && bagId.equals(BagItem.idOf(offhand))) {
                return -2; // sentinel for off-hand
            }
        }
        return -1;
    }

    private ItemStack getBagStack(int slot) {
        if (slot == -2) {
            return ((Player) source.getHolder()).getInventory().getItemInOffHand();
        }
        return source.getItem(slot);
    }

    private void setBagStack(int slot, ItemStack bag) {
        if (slot == -2) {
            ((Player) source.getHolder()).getInventory().setItemInOffHand(bag);
        } else {
            source.setItem(slot, bag);
        }
    }

    public void loadFromItem() {
        int slot = findSlot();
        if (slot == -1) {
            return;
        }
        ItemStack[] contents = BagItem.loadContents(getBagStack(slot));
        for (int i = 0; i < BagItem.CLOSE_BUTTON_SLOT; i++) {
            inventory.setItem(i, i < contents.length ? contents[i] : null);
        }
        inventory.setItem(BagItem.CLOSE_BUTTON_SLOT, BagItem.closeButton());
    }

    public void syncToItem() {
        flushed = true;
        int slot = findSlot();
        if (slot == -1) {
            return; // bag no longer where we found it (dropped, given away mid-edit) — nothing to write back to
        }
        ItemStack[] contents = new ItemStack[BagItem.SIZE];
        for (int i = 0; i < BagItem.CLOSE_BUTTON_SLOT; i++) {
            ItemStack item = inventory.getItem(i);
            // Last-resort safety net: never let a bag end up saved inside a
            // bag's own storage, however it got there.
            if (BagItem.isBag(item)) {
                if (source.getHolder() instanceof Player p) {
                    p.getInventory().addItem(item);
                    p.sendMessage("§cA bag can't hold another bag — returned it to your inventory.");
                }
                continue;
            }
            contents[i] = item;
        }
        // Slot CLOSE_BUTTON_SLOT is never real storage — left null in contents deliberately.
        ItemStack bag = getBagStack(slot);
        BagItem.saveContents(bag, contents);
        setBagStack(slot, bag);
        if (source.getHolder() instanceof Player p) {
            p.updateInventory();
        }
    }
}
