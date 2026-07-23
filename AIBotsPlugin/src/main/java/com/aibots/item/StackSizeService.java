package com.aibots.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies configured max stack size (vanilla component cap is 99).
 */
public final class StackSizeService {

    /** Mojang max_stack_size component limit */
    public static final int HARD_CAP = 99;

    private final JavaPlugin plugin;

    public StackSizeService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public int configuredMax() {
        int n = plugin.getConfig().getInt("items.default-max-stack", 64);
        if (n < 1) {
            return 1;
        }
        return Math.min(n, HARD_CAP);
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("items.apply-max-stack", true)
                && configuredMax() != 64;
    }

    /**
     * Raise stackable items' max stack to config (never above 99).
     * Skips tools/armor/unstackables (default max 1).
     */
    public ItemStack apply(ItemStack stack) {
        if (!enabled() || stack == null || stack.getType().isAir()) {
            return stack;
        }
        Material type = stack.getType();
        int vanilla = type.getMaxStackSize();
        if (vanilla <= 1) {
            return stack; // don't force-stack swords, armor, etc.
        }
        int want = configuredMax();
        // Don't lower below vanilla if config is weird; only raise common 16/64 stacks
        if (want <= vanilla && vanilla >= want) {
            // still set component so merges are consistent when want==99 and vanilla==64
            if (want == vanilla) {
                return stack;
            }
        }
        int target = Math.max(vanilla, want);
        target = Math.min(target, HARD_CAP);

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        try {
            if (meta.hasMaxStackSize() && meta.getMaxStackSize() == target) {
                return stack;
            }
            meta.setMaxStackSize(target);
            stack.setItemMeta(meta);
        } catch (Throwable t) {
            plugin.getLogger().fine("setMaxStackSize failed for " + type + ": " + t.getMessage());
        }
        // Clamp amount if somehow over
        if (stack.getAmount() > target) {
            stack.setAmount(target);
        }
        return stack;
    }

    public void applyInventory(org.bukkit.inventory.Inventory inv) {
        if (!enabled() || inv == null) {
            return;
        }
        ItemStack[] contents = inv.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType().isAir()) {
                continue;
            }
            ItemStack before = s.clone();
            apply(s);
            if (!before.isSimilar(s) || before.getAmount() != s.getAmount()
                    || maxStackOf(before) != maxStackOf(s)) {
                contents[i] = s;
                changed = true;
            }
        }
        if (changed) {
            inv.setContents(contents);
        }
    }

    private static int maxStackOf(ItemStack s) {
        if (s == null) {
            return 64;
        }
        try {
            return s.getMaxStackSize();
        } catch (Throwable t) {
            return s.getType().getMaxStackSize();
        }
    }
}
