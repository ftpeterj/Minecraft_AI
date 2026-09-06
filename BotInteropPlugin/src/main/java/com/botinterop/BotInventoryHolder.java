package com.botinterop;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * A 36-slot GUI mirroring a bot account's real main inventory + hotbar. It's a
 * live copy synced back to the bot's real PlayerInventory on every
 * click/drag/close (see BotInventoryListener) — not a direct view, because
 * Bukkit only allows the owning player to open their own PlayerInventory with
 * armor slots visible. Armor/offhand are handled separately by
 * BotArmorHolder: containers bigger than 36 slots silently lost their extra
 * rows on this server's (unofficial, bleeding-edge) Paper 26.2 build when
 * opened for a non-owning viewer, so this stays at the one size that's
 * confirmed to render correctly.
 */
public class BotInventoryHolder implements InventoryHolder {

    public static final int SIZE = 36;

    private final Player target;
    private Inventory inventory;
    private boolean flushed = false;

    public BotInventoryHolder(Player target) {
        this.target = target;
    }

    /** True once this specific holder's contents have already been written to the target (see BotGui.flushIfOpen). */
    public boolean isFlushed() {
        return flushed;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player getTarget() {
        return target;
    }

    /**
     * Populates the GUI from the bot's current real inventory. Reads slots
     * one at a time rather than via getContents() — bulk inventory-array
     * reads/writes have behaved unpredictably on this build (see
     * syncToTarget()'s note on setContents() wiping armor); this mirrors the
     * same per-slot pattern already proven safe there.
     */
    public void loadFromTarget() {
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, target.getInventory().getItem(i));
        }
    }

    /**
     * Writes the GUI's current contents back to the bot's real inventory. Safe
     * to call more than once. Deliberately sets each of the 36 slots
     * individually rather than calling PlayerInventory#setContents() with a
     * 36-element array — that call clears the entire underlying storage
     * (armor + offhand included) before refilling just the 36 given slots,
     * silently wiping armor as a side effect of simply opening and closing
     * this window. Confirmed as the actual cause of "armor disappears" — not
     * a race condition, not a platform bug, just this one bulk-write call.
     */
    public void syncToTarget() {
        flushed = true;
        if (!target.isOnline()) {
            return;
        }
        for (int i = 0; i < SIZE; i++) {
            target.getInventory().setItem(i, inventory.getItem(i));
        }
        target.updateInventory(); // push the change to the bot's own client, or its local model goes stale
    }
}
