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

    /** Populates the GUI from the bot's current real inventory. */
    public void loadFromTarget() {
        ItemStack[] contents = target.getInventory().getContents();
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, i < contents.length ? contents[i] : null);
        }
    }

    /** Writes the GUI's current contents back to the bot's real inventory. Safe to call more than once. */
    public void syncToTarget() {
        flushed = true;
        if (!target.isOnline()) {
            return;
        }
        ItemStack[] main = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) {
            main[i] = inventory.getItem(i);
        }
        target.getInventory().setContents(main);
    }
}
