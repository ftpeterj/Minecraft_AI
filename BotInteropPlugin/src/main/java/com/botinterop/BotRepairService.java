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
        PlayerInventory inv = target.getInventory();
        repair(inv.getHelmet());
        repair(inv.getChestplate());
        repair(inv.getLeggings());
        repair(inv.getBoots());
        repair(inv.getItemInMainHand());
        repair(inv.getItemInOffHand());
    }

    private static final double HEAL_FRACTION_PER_TICK = 0.01; // ~1% of max durability per run

    private void repair(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        int max = item.getType().getMaxDurability();
        if (max <= 0) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable d && d.getDamage() > 0) {
            int healAmount = Math.max(1, (int) Math.round(max * HEAL_FRACTION_PER_TICK));
            d.setDamage(Math.max(0, d.getDamage() - healAmount));
            item.setItemMeta(meta);
        }
    }
}
