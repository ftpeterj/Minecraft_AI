package com.aibots.crew;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Personal loot bag for a crew bot — right-click opens this instead of villager trades.
 */
public final class CrewLootHolder implements InventoryHolder {

    private final UUID botId;
    private final String botName;
    private final Inventory inventory;

    public CrewLootHolder(UUID botId, String botName) {
        this.botId = botId;
        this.botName = botName;
        // 27 slots — enough to browse what they've acquired
        this.inventory = Bukkit.createInventory(this, 27, "§6" + botName + " §8— Loot");
    }

    public UUID getBotId() {
        return botId;
    }

    public String getBotName() {
        return botName;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public int totalItems() {
        int n = 0;
        for (ItemStack s : inventory.getContents()) {
            if (s != null && !s.getType().isAir()) {
                n += s.getAmount();
            }
        }
        return n;
    }

    public boolean isEmpty() {
        return totalItems() == 0;
    }

    /** @return leftover that did not fit */
    public ItemStack add(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        var leftover = inventory.addItem(stack.clone());
        if (leftover.isEmpty()) {
            return null;
        }
        return leftover.values().iterator().next();
    }
}
