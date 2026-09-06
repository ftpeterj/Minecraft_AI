package com.botinterop;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

final class BotStatusFormatter {

    private BotStatusFormatter() {
    }

    static String format(Player bot) {
        Location loc = bot.getLocation();
        String header = String.format(
                "§e[BotInterop] §f%s §7HP: §c%.1f/%.0f §7Hunger: §6%d/20 §7@ §f%s %d,%d,%d",
                bot.getName(),
                bot.getHealth(),
                bot.getMaxHealth(),
                bot.getFoodLevel(),
                loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        String durability = formatDurability(bot);
        return durability.isEmpty() ? header : header + "\n" + durability;
    }

    private static String formatDurability(Player bot) {
        PlayerInventory inv = bot.getInventory();
        StringBuilder sb = new StringBuilder();
        appendPiece(sb, "Helmet", inv.getHelmet());
        appendPiece(sb, "Chest", inv.getChestplate());
        appendPiece(sb, "Legs", inv.getLeggings());
        appendPiece(sb, "Boots", inv.getBoots());
        appendPiece(sb, "Hand", inv.getItemInMainHand());
        appendPiece(sb, "Off-hand", inv.getItemInOffHand());
        return sb.length() == 0 ? "" : "§7[BotInterop] durability:" + sb;
    }

    private static void appendPiece(StringBuilder sb, String label, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        int max = item.getType().getMaxDurability();
        if (max <= 0) {
            return; // not a damageable item (e.g. a block, or something unbreakable)
        }
        ItemMeta meta = item.getItemMeta();
        int damage = meta instanceof Damageable d ? d.getDamage() : 0;
        int remaining = max - damage;
        sb.append(String.format(" §f%s §7%d/%d", label, remaining, max));
    }
}
