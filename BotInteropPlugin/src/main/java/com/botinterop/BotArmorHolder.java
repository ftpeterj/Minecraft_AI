package com.botinterop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * An 18-slot GUI for a bot account's armor + offhand + main hand: row 0 is
 * static, non-interactive labels (helmet/chestplate/leggings/boots/offhand/
 * main-hand icons); row 1 directly below holds the real, editable items.
 * Only real armor of the matching type is accepted in the
 * helmet/chestplate/leggings/boots slots (offhand and main hand stay
 * unrestricted, same as vanilla lets you hold anything). Synced back to the
 * bot's real PlayerInventory on every click/drag/close.
 */
public class BotArmorHolder implements InventoryHolder {

    public static final int SIZE = 18;
    private static final int LABEL_ROW_END = 9; // raw slots 0-8 are labels

    public static final int HELMET_SLOT = 9;
    public static final int CHESTPLATE_SLOT = 10;
    public static final int LEGGINGS_SLOT = 11;
    public static final int BOOTS_SLOT = 12;
    public static final int OFFHAND_SLOT = 13;
    public static final int MAIN_HAND_SLOT = 14;

    private static final Predicate<ItemStack> IS_HELMET =
            i -> i.getType().name().endsWith("_HELMET") || i.getType() == Material.CARVED_PUMPKIN;
    private static final Predicate<ItemStack> IS_CHESTPLATE =
            i -> i.getType().name().endsWith("_CHESTPLATE") || i.getType() == Material.ELYTRA;
    private static final Predicate<ItemStack> IS_LEGGINGS = i -> i.getType().name().endsWith("_LEGGINGS");
    private static final Predicate<ItemStack> IS_BOOTS = i -> i.getType().name().endsWith("_BOOTS");

    private final Player target;
    private Inventory inventory;
    private boolean flushed = false;

    public BotArmorHolder(Player target) {
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

    static boolean isLabelSlot(int rawSlot) {
        return rawSlot >= 0 && rawSlot < LABEL_ROW_END;
    }

    /** Only these slots (directly under the labels) actually hold/sync anything; everything else in this GUI must reject items or they'd be silently lost. */
    static boolean isInteractiveSlot(int rawSlot) {
        return rawSlot >= HELMET_SLOT && rawSlot <= MAIN_HAND_SLOT;
    }

    private static ItemStack label(Material icon, String name) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e" + name);
        item.setItemMeta(meta);
        return item;
    }

    public void loadFromTarget() {
        inventory.setItem(0, label(Material.LEATHER_HELMET, "Helmet ↓"));
        inventory.setItem(1, label(Material.LEATHER_CHESTPLATE, "Chestplate ↓"));
        inventory.setItem(2, label(Material.LEATHER_LEGGINGS, "Leggings ↓"));
        inventory.setItem(3, label(Material.LEATHER_BOOTS, "Boots ↓"));
        inventory.setItem(4, label(Material.SHIELD, "Off-hand ↓"));
        inventory.setItem(5, label(Material.IRON_SWORD, "Main hand ↓"));

        inventory.setItem(HELMET_SLOT, target.getInventory().getHelmet());
        inventory.setItem(CHESTPLATE_SLOT, target.getInventory().getChestplate());
        inventory.setItem(LEGGINGS_SLOT, target.getInventory().getLeggings());
        inventory.setItem(BOOTS_SLOT, target.getInventory().getBoots());
        inventory.setItem(OFFHAND_SLOT, target.getInventory().getItemInOffHand());
        inventory.setItem(MAIN_HAND_SLOT, target.getInventory().getItemInMainHand());
    }

    /**
     * Pulls out any item sitting in a restricted slot (helmet/chestplate/
     * leggings/boots) that isn't actually that piece of armor, clearing the
     * slot. Call before syncToTarget(); give the returned items back to
     * whoever was editing.
     */
    private List<ItemStack> extractInvalidItems() {
        List<ItemStack> rejected = new ArrayList<>();
        rejectIfInvalid(HELMET_SLOT, IS_HELMET, rejected);
        rejectIfInvalid(CHESTPLATE_SLOT, IS_CHESTPLATE, rejected);
        rejectIfInvalid(LEGGINGS_SLOT, IS_LEGGINGS, rejected);
        rejectIfInvalid(BOOTS_SLOT, IS_BOOTS, rejected);
        return rejected;
    }

    private void rejectIfInvalid(int slot, Predicate<ItemStack> valid, List<ItemStack> rejected) {
        ItemStack item = inventory.getItem(slot);
        if (item != null && !item.getType().isAir() && !valid.test(item)) {
            rejected.add(item);
            inventory.setItem(slot, null);
        }
    }

    /** Writes the GUI's current contents back to the bot's real inventory. Safe to call more than once. */
    private void syncToTarget() {
        flushed = true;
        if (!target.isOnline()) {
            return;
        }
        var equipment = target.getEquipment();
        equipment.setHelmet(inventory.getItem(HELMET_SLOT));
        equipment.setChestplate(inventory.getItem(CHESTPLATE_SLOT));
        equipment.setLeggings(inventory.getItem(LEGGINGS_SLOT));
        equipment.setBoots(inventory.getItem(BOOTS_SLOT));
        equipment.setItemInOffHand(inventory.getItem(OFFHAND_SLOT));
        equipment.setItemInMainHand(inventory.getItem(MAIN_HAND_SLOT));
        target.updateInventory();
    }

    /**
     * Strips out any mismatched item from a restricted slot (returning it to
     * whoever's currently viewing, or dropping it at their feet if their
     * inventory's full), then writes the corrected contents to the real bot
     * inventory. This is the one entry point every caller should use instead
     * of syncToTarget() directly, so the type restriction can never be
     * bypassed by a different call site.
     */
    public void syncRejectingInvalid() {
        List<ItemStack> rejected = extractInvalidItems();
        if (!rejected.isEmpty() && inventory != null) {
            for (org.bukkit.entity.HumanEntity viewer : List.copyOf(inventory.getViewers())) {
                if (viewer instanceof Player player) {
                    for (ItemStack item : rejected) {
                        player.getInventory().addItem(item).values()
                                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                    }
                    player.sendMessage("§c[BotInterop] That slot only accepts the matching armor piece — returned to your inventory.");
                }
            }
        }
        syncToTarget();
    }
}
