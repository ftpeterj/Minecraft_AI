package com.botinterop;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The screen a non-friend gets instead of direct inventory access: row 0 is a
 * read-only snapshot of the bot's current items (clicking one marks it as
 * "wanted" — cancelled, never actually taken); row 1 is real, interactive
 * slots where the requester places what they're offering (a genuine
 * placement, physically out of their own inventory the moment they drop it
 * in, same as any chest). Closing the window packages up the offer + wants
 * into a proposal for the owner to approve/deny (see BotTradeService).
 */
public class BotTradeHolder implements InventoryHolder {

    public static final int SIZE = 18;
    private static final int CATALOG_END = 9; // raw slots 0-8 are the read-only catalog

    private final Player target; // the bot
    private final Player requester;
    private final Set<String> wanted = new LinkedHashSet<>();
    private Inventory inventory;
    private boolean submitted = false;

    public BotTradeHolder(Player target, Player requester) {
        this.target = target;
        this.requester = requester;
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

    public Player getRequester() {
        return requester;
    }

    static boolean isCatalogSlot (int rawSlot) {
        return rawSlot >= 0 && rawSlot < CATALOG_END;
    }

    public void loadCatalog() {
        // Read slots 0-35 one at a time rather than via getContents() — bulk
        // inventory-array reads/writes have behaved unpredictably on this
        // build before (see BotInventoryHolder's setContents() note); this
        // is the same per-slot pattern already proven safe for writes.
        int slot = 0;
        for (int i = 0; i < 36 && slot < CATALOG_END; i++) {
            ItemStack item = target.getInventory().getItem(i);
            if (item != null && !item.getType().isAir()) {
                ItemStack display = item.clone();
                display.setAmount(1);
                inventory.setItem(slot++, display);
            }
        }
    }

    /** Marks the item currently shown in this catalog slot as wanted; the display copy is left untouched (the click was cancelled). */
    public void registerWant(int rawSlot) {
        ItemStack item = inventory.getItem(rawSlot);
        if (item != null && !item.getType().isAir()) {
            wanted.add(item.getType().name());
        }
    }

    public Set<String> getWanted() {
        return wanted;
    }

    public List<ItemStack> collectOffer() {
        List<ItemStack> offer = new ArrayList<>();
        for (int i = CATALOG_END; i < SIZE; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir()) {
                offer.add(item);
            }
        }
        return offer;
    }

    public boolean isEmpty() {
        return wanted.isEmpty() && collectOffer().isEmpty();
    }

    public boolean isSubmitted() {
        return submitted;
    }

    public void markSubmitted() {
        submitted = true;
    }
}
