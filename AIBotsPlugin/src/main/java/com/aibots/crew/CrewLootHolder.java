package com.aibots.crew;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Personal loot bag for a crew bot — right-click opens this instead of villager trades.
 * Size is a multiple of 9, max 54 (double chest) per Bukkit API.
 */
public final class CrewLootHolder implements InventoryHolder {

    private final UUID botId;
    private final String botName;
    private final Inventory inventory;

    public CrewLootHolder(UUID botId, String botName) {
        this(botId, botName, 54);
    }

    public CrewLootHolder(UUID botId, String botName, int slots) {
        this.botId = botId;
        this.botName = botName;
        this.inventory = Bukkit.createInventory(this, normalizeSlots(slots),
                "§6" + botName + " §8— Loot");
    }

    /** Prefer config {@code crew.loot-slots} (default 54). */
    public static CrewLootHolder create(UUID botId, String botName, JavaPlugin plugin) {
        int slots = 54;
        if (plugin != null) {
            slots = plugin.getConfig().getInt("crew.loot-slots", 54);
        }
        return new CrewLootHolder(botId, botName, slots);
    }

    private static int normalizeSlots(int slots) {
        // Bukkit chest GUIs: 9, 18, 27, 36, 45, 54
        if (slots < 9) {
            return 9;
        }
        if (slots > 54) {
            return 54;
        }
        int rows = (int) Math.ceil(slots / 9.0);
        return Math.min(54, Math.max(9, rows * 9));
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

    public int count(org.bukkit.Material type) {
        if (type == null) {
            return 0;
        }
        int n = 0;
        for (ItemStack s : inventory.getContents()) {
            if (s != null && s.getType() == type) {
                n += s.getAmount();
            }
        }
        return n;
    }

    /** Remove up to {@code amount} of type. @return actually removed */
    public int remove(org.bukkit.Material type, int amount) {
        if (type == null || amount <= 0) {
            return 0;
        }
        int need = amount;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length && need > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType() != type) {
                continue;
            }
            int take = Math.min(need, s.getAmount());
            s.setAmount(s.getAmount() - take);
            if (s.getAmount() <= 0) {
                inventory.setItem(i, null);
            } else {
                inventory.setItem(i, s);
            }
            need -= take;
        }
        return amount - need;
    }

    public ItemStack findFirst(java.util.function.Predicate<ItemStack> pred) {
        for (ItemStack s : inventory.getContents()) {
            if (s != null && !s.getType().isAir() && pred.test(s)) {
                return s;
            }
        }
        return null;
    }

    public java.util.List<ItemStack> findAll(java.util.function.Predicate<ItemStack> pred) {
        java.util.List<ItemStack> list = new java.util.ArrayList<>();
        for (ItemStack s : inventory.getContents()) {
            if (s != null && !s.getType().isAir() && pred.test(s)) {
                list.add(s);
            }
        }
        return list;
    }
}
