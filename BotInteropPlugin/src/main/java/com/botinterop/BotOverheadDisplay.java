package com.botinterop;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * A floating TextDisplay hologram above a bot account's head, refreshed once
 * a second with HP%, hunger%, and durability% for each worn/held item. One
 * per online bot account; spawned on join, removed on quit.
 */
public class BotOverheadDisplay {

    private static final double HEIGHT_OFFSET = 2.4;
    private static final NamespacedKey MARKER = new NamespacedKey("botinterop", "overhead");

    private final JavaPlugin plugin;
    private final Map<UUID, TextDisplay> displays = new HashMap<>();
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();

    public BotOverheadDisplay(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Removes any leftover marked displays from a previous run (crash, unclean shutdown) before spawning fresh ones. */
    public void cleanupOrphans() {
        for (var world : Bukkit.getWorlds()) {
            for (var entity : world.getEntitiesByClass(TextDisplay.class)) {
                if (entity.getPersistentDataContainer().has(MARKER)) {
                    entity.remove();
                }
            }
        }
    }

    public void start(Player target) {
        UUID id = target.getUniqueId();
        if (displays.containsKey(id)) {
            return;
        }
        TextDisplay display = target.getWorld().spawn(headLocation(target), TextDisplay.class);
        display.getPersistentDataContainer().set(MARKER, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(true);
        display.setShadowed(false);
        displays.put(id, display);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> refresh(target), 0L, 20L);
        tasks.put(id, task);
    }

    public void stop(Player target) {
        UUID id = target.getUniqueId();
        BukkitTask task = tasks.remove(id);
        if (task != null) {
            task.cancel();
        }
        TextDisplay display = displays.remove(id);
        if (display != null) {
            display.remove();
        }
    }

    public void stopAll() {
        for (BukkitTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        for (TextDisplay display : displays.values()) {
            display.remove();
        }
        displays.clear();
    }

    private Location headLocation(Player target) {
        return target.getLocation().add(0, HEIGHT_OFFSET, 0);
    }

    private void refresh(Player target) {
        TextDisplay display = displays.get(target.getUniqueId());
        if (display == null || display.isDead()) {
            return;
        }
        if (!target.isOnline()) {
            stop(target);
            return;
        }
        display.teleport(headLocation(target));
        display.text(buildText(target));
    }

    private Component buildText(Player target) {
        int hpPct = (int) Math.round(100.0 * target.getHealth() / target.getMaxHealth());
        int hungerPct = (int) Math.round(100.0 * target.getFoodLevel() / 20.0);

        Component text = Component.text(target.getName(), NamedTextColor.YELLOW)
                .append(Component.newline())
                .append(Component.text("HP " + hpPct + "%  Hunger " + hungerPct + "%", NamedTextColor.WHITE));

        PlayerInventory inv = target.getInventory();
        java.util.List<Component> gearPieces = new java.util.ArrayList<>();
        addDurabilityLine(gearPieces, "Helmet", inv.getHelmet());
        addDurabilityLine(gearPieces, "Chest", inv.getChestplate());
        addDurabilityLine(gearPieces, "Legs", inv.getLeggings());
        addDurabilityLine(gearPieces, "Boots", inv.getBoots());
        addDurabilityLine(gearPieces, "Hand", inv.getItemInMainHand());
        addDurabilityLine(gearPieces, "Off", inv.getItemInOffHand());

        if (!gearPieces.isEmpty()) {
            Component gear = Component.empty();
            for (Component piece : gearPieces) {
                gear = gear.append(piece);
            }
            text = text.append(Component.newline()).append(gear);
        }
        return text;
    }

    private void addDurabilityLine(java.util.List<Component> out, String label, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        int max = item.getType().getMaxDurability();
        if (max <= 0) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        int damage = meta instanceof Damageable d ? d.getDamage() : 0;
        int pct = (int) Math.round(100.0 * (max - damage) / max);
        NamedTextColor color = pct <= 20 ? NamedTextColor.RED : pct <= 50 ? NamedTextColor.GOLD : NamedTextColor.GREEN;
        out.add(Component.text(" " + label + " " + pct + "%", color));
    }
}
