package com.stacksize99;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class StackSizeUtil {

    static final int TARGET = 99;

    private StackSizeUtil() {
    }

    /** Bumps this stack's effective max size to 99, only if its item is normally a 64-stack. */
    static void boost(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        if (stack.getType().getMaxStackSize() != 64) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || (meta.hasMaxStackSize() && meta.getMaxStackSize() == TARGET)) {
            return;
        }
        meta.setMaxStackSize(TARGET);
        stack.setItemMeta(meta);
    }

    static void boostAll(ItemStack[] contents) {
        if (contents == null) {
            return;
        }
        for (ItemStack stack : contents) {
            boost(stack);
        }
    }

    static void boostAll(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        boostAll(inventory.getContents());
    }
}
