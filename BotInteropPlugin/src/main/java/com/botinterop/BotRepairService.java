package com.botinterop;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Per-bot toggleable auto-repair: while on, resets durability to full on every worn/held item every few seconds. */
public class BotRepairService {

    private static final long PERIOD_TICKS = 200L; // 10s

    private final JavaPlugin plugin;
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();

    public BotRepairService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled(Player target) {
        return tasks.containsKey(target.getUniqueId());
    }

    public void enable(Player target) {
        UUID id = target.getUniqueId();
        if (tasks.containsKey(id)) {
            return;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> repairAll(target), 0L, PERIOD_TICKS);
        tasks.put(id, task);
    }

    public void disable(Player target) {
        BukkitTask task = tasks.remove(target.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public void stopAll() {
        for (BukkitTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
    }

    private void repairAll(Player target) {
        if (!target.isOnline()) {
            disable(target);
            return;
        }
        // Explicitly write each repaired item back with the matching setter —
        // confirmed live that getItemInMainHand() doesn't reliably return a
        // live, auto-syncing reference on this build (mutating it silently
        // did nothing: gave the bot a damaged wooden axe, waited past two
        // repair cycles, damage never changed). Same class of bug as the
        // getContents()/setContents() unreliability found earlier — never
        // trust a bulk/getter-based inventory read to auto-sync here.
        PlayerInventory inv = target.getInventory();
        ItemStack helmet = repair(inv.getHelmet());
        if (helmet != null) inv.setHelmet(helmet);
        ItemStack chestplate = repair(inv.getChestplate());
        if (chestplate != null) inv.setChestplate(chestplate);
        ItemStack leggings = repair(inv.getLeggings());
        if (leggings != null) inv.setLeggings(leggings);
        ItemStack boots = repair(inv.getBoots());
        if (boots != null) inv.setBoots(boots);
        ItemStack mainHand = repair(inv.getItemInMainHand());
        if (mainHand != null) inv.setItemInMainHand(mainHand);
        ItemStack offHand = repair(inv.getItemInOffHand());
        if (offHand != null) inv.setItemInOffHand(offHand);
        target.updateInventory();
    }

    private static final double HEAL_FRACTION_PER_TICK = 0.01; // ~1% of max durability per run

    /** Returns the mutated item to write back, or null if there was nothing to repair (air, no durability, or already full). */
    private ItemStack repair(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        int max = item.getType().getMaxDurability();
        if (max <= 0) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable d && d.getDamage() > 0) {
            int healAmount = Math.max(1, (int) Math.round(max * HEAL_FRACTION_PER_TICK));
            d.setDamage(Math.max(0, d.getDamage() - healAmount));
            item.setItemMeta(meta);
            return item;
        }
        return null;
    }
}
