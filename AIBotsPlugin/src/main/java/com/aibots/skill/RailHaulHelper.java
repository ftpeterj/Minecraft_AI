package com.aibots.skill;

import com.aibots.crew.CrewBot;
import com.aibots.npc.NpcHandle;
import com.aibots.storage.ChestNetwork;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-haul logistics: when deposit target is far, lay rails toward storage and
 * use a chest minecart to carry the bag.
 * <p>
 * v1 — straightish ground rails + chest minecart dump + push toward destination.
 */
public final class RailHaulHelper {

    private final JavaPlugin plugin;
    private final ChestNetwork chests;
    private final ConcurrentHashMap<UUID, Long> lastMsg = new ConcurrentHashMap<>();

    public RailHaulHelper(JavaPlugin plugin, ChestNetwork chests) {
        this.plugin = plugin;
        this.chests = chests;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("crew.rail-haul.enabled", true);
    }

    public int minDistance() {
        return plugin.getConfig().getInt("crew.rail-haul.min-distance", 40);
    }

    /**
     * Try long-haul deposit. Returns true if this tick was consumed by rail work
     * (caller should not also walk-deposit the same tick).
     */
    public boolean tryHaulDeposit(CrewBot bot, NpcHandle body, Location from, Location chestLoc) {
        if (!enabled() || bot == null || body == null || from == null || chestLoc == null) {
            return false;
        }
        if (bot.getLoot().isEmpty()) {
            return false;
        }
        double dist = from.distance(chestLoc);
        if (dist < minDistance()) {
            return false;
        }

        // Prefer real deposit if already next to chest
        if (from.distanceSquared(chestLoc) <= 36.0) {
            return false;
        }

        ensureRailsInBag(bot);
        int rails = countRails(bot);
        if (rails < 4) {
            maybeTell(bot, "Storage is ~" + Math.round(dist)
                    + " blocks away. Need rails in bag/storage for minecart haul "
                    + "(have " + rails + "). Walking for now.");
            return false;
        }

        // Lay a short segment of rail toward the chest each tick (or several)
        int laid = layRailsToward(from, chestLoc, bot, Math.min(8, rails));
        if (laid > 0) {
            maybeTell(bot, "Laid " + laid + " rail toward storage (~"
                    + Math.round(dist) + " blocks left).");
        }

        // If we have a chest minecart nearby with space, dump into it
        StorageMinecart cart = findNearbyChestCart(from, 4);
        if (cart == null && (bot.getLoot().count(Material.CHEST_MINECART) > 0
                || bot.getLoot().count(Material.MINECART) > 0
                || chests.count(Material.CHEST_MINECART) > 0)) {
            cart = spawnChestCartOnRail(bot, body, from, chestLoc);
        }
        if (cart != null && cart.isValid()) {
            int moved = dumpBagIntoCart(bot, cart);
            if (moved > 0) {
                maybeTell(bot, "Loaded " + moved + " items into chest minecart.");
            }
            // Nudge cart toward storage
            pushCartToward(cart, chestLoc);
            // Walk along rails toward storage
            body.walkTo(chestLoc.clone().add(0.5, 0, 0.5), 1.05);
            return true;
        }

        // Keep walking / laying
        body.walkTo(approachRailEnd(from, chestLoc), 1.05);
        return laid > 0;
    }

    private void ensureRailsInBag(CrewBot bot) {
        int need = 16;
        int have = countRails(bot);
        if (have >= need) {
            return;
        }
        int want = need - have;
        for (Material m : new Material[]{Material.RAIL, Material.POWERED_RAIL, Material.DETECTOR_RAIL}) {
            if (want <= 0) {
                break;
            }
            ItemStack got = chests.withdraw(m, want);
            if (got != null && got.getAmount() > 0) {
                bot.getLoot().add(got);
                want -= got.getAmount();
            }
        }
        // Chest minecart
        if (bot.getLoot().count(Material.CHEST_MINECART) < 1
                && bot.getLoot().count(Material.MINECART) < 1) {
            ItemStack cart = chests.withdraw(Material.CHEST_MINECART, 1);
            if (cart == null) {
                cart = chests.withdraw(Material.MINECART, 1);
            }
            if (cart != null) {
                bot.getLoot().add(cart);
            }
        }
    }

    private int countRails(CrewBot bot) {
        return bot.getLoot().count(Material.RAIL)
                + bot.getLoot().count(Material.POWERED_RAIL)
                + bot.getLoot().count(Material.DETECTOR_RAIL)
                + bot.getLoot().count(Material.ACTIVATOR_RAIL);
    }

    private int layRailsToward(Location from, Location to, CrewBot bot, int maxPlace) {
        World world = from.getWorld();
        if (world == null) {
            return 0;
        }
        int placed = 0;
        int fx = from.getBlockX();
        int fz = from.getBlockZ();
        int tx = to.getBlockX();
        int tz = to.getBlockZ();
        int dx = Integer.compare(tx, fx);
        int dz = Integer.compare(tz, fz);
        int x = fx;
        int z = fz;
        int y = from.getBlockY();

        for (int i = 0; i < maxPlace; i++) {
            if (x == tx && z == tz) {
                break;
            }
            // Prefer moving on the longer axis first
            if (Math.abs(tx - x) >= Math.abs(tz - z) && dx != 0) {
                x += dx;
            } else if (dz != 0) {
                z += dz;
            } else if (dx != 0) {
                x += dx;
            } else {
                break;
            }
            // Find ground
            Block ground = findGround(world, x, y, z);
            if (ground == null) {
                continue;
            }
            y = ground.getY();
            Block railSpot = ground.getRelative(BlockFace.UP);
            if (!railSpot.getType().isAir() && !isRail(railSpot.getType())) {
                continue;
            }
            if (isRail(railSpot.getType())) {
                continue; // already rail
            }
            Material railMat = takeOneRail(bot);
            if (railMat == null) {
                break;
            }
            railSpot.setType(railMat, false);
            placed++;
        }
        return placed;
    }

    private static Block findGround(World world, int x, int preferY, int z) {
        for (int dy = 0; dy <= 3; dy++) {
            for (int sign : new int[]{0, -1, 1}) {
                if (dy == 0 && sign != 0) {
                    continue;
                }
                int y = preferY + sign * dy;
                Block b = world.getBlockAt(x, y, z);
                Block above = b.getRelative(BlockFace.UP);
                if (b.getType().isSolid() && (above.getType().isAir() || isRail(above.getType()))) {
                    return b;
                }
            }
        }
        // search down
        for (int y = preferY; y >= preferY - 8 && y > world.getMinHeight(); y--) {
            Block b = world.getBlockAt(x, y, z);
            Block above = b.getRelative(BlockFace.UP);
            if (b.getType().isSolid() && (above.getType().isAir() || isRail(above.getType()))) {
                return b;
            }
        }
        return null;
    }

    private Material takeOneRail(CrewBot bot) {
        for (Material m : new Material[]{
                Material.RAIL, Material.POWERED_RAIL, Material.DETECTOR_RAIL, Material.ACTIVATOR_RAIL}) {
            if (bot.getLoot().count(m) > 0) {
                bot.getLoot().remove(m, 1);
                return m;
            }
        }
        for (Material m : new Material[]{Material.RAIL, Material.POWERED_RAIL}) {
            ItemStack got = chests.withdraw(m, 1);
            if (got != null && got.getAmount() > 0) {
                return m;
            }
        }
        return null;
    }

    private static boolean isRail(Material m) {
        if (m == null) {
            return false;
        }
        return m == Material.RAIL || m == Material.POWERED_RAIL
                || m == Material.DETECTOR_RAIL || m == Material.ACTIVATOR_RAIL;
    }

    private StorageMinecart findNearbyChestCart(Location from, double r) {
        if (from.getWorld() == null) {
            return null;
        }
        StorageMinecart best = null;
        double bestD = r * r;
        for (var e : from.getWorld().getNearbyEntities(from, r, r, r)) {
            if (e instanceof StorageMinecart cart && cart.isValid()) {
                double d = e.getLocation().distanceSquared(from);
                if (d < bestD) {
                    bestD = d;
                    best = cart;
                }
            }
        }
        return best;
    }

    private StorageMinecart spawnChestCartOnRail(CrewBot bot, NpcHandle body, Location from, Location toward) {
        World world = from.getWorld();
        if (world == null) {
            return null;
        }
        // Find nearby rail
        Block rail = null;
        for (int dx = -2; dx <= 2 && rail == null; dx++) {
            for (int dz = -2; dz <= 2 && rail == null; dz++) {
                Block b = world.getBlockAt(from.getBlockX() + dx, from.getBlockY(), from.getBlockZ() + dz);
                if (isRail(b.getType())) {
                    rail = b;
                } else if (isRail(b.getRelative(BlockFace.UP).getType())) {
                    rail = b.getRelative(BlockFace.UP);
                }
            }
        }
        if (rail == null) {
            // place one under feet direction
            Block ground = findGround(world, from.getBlockX(), from.getBlockY(), from.getBlockZ());
            if (ground != null) {
                Material rm = takeOneRail(bot);
                if (rm != null) {
                    Block rs = ground.getRelative(BlockFace.UP);
                    if (rs.getType().isAir()) {
                        rs.setType(rm, false);
                        rail = rs;
                    }
                }
            }
        }
        if (rail == null) {
            return null;
        }
        boolean usedChestCart = bot.getLoot().count(Material.CHEST_MINECART) > 0
                || chests.count(Material.CHEST_MINECART) > 0;
        if (bot.getLoot().count(Material.CHEST_MINECART) > 0) {
            bot.getLoot().remove(Material.CHEST_MINECART, 1);
        } else if (bot.getLoot().count(Material.MINECART) > 0) {
            bot.getLoot().remove(Material.MINECART, 1);
            usedChestCart = false;
        } else {
            ItemStack c = chests.withdraw(Material.CHEST_MINECART, 1);
            if (c == null) {
                c = chests.withdraw(Material.MINECART, 1);
                usedChestCart = false;
            }
            if (c == null) {
                return null;
            }
        }
        Location spawn = rail.getLocation().add(0.5, 0.1, 0.5);
        if (usedChestCart) {
            return (StorageMinecart) world.spawnEntity(spawn, EntityType.CHEST_MINECART);
        }
        // plain minecart can't hold items well — still spawn chest cart if we can craft conceptually
        return (StorageMinecart) world.spawnEntity(spawn, EntityType.CHEST_MINECART);
    }

    private int dumpBagIntoCart(CrewBot bot, StorageMinecart cart) {
        int moved = 0;
        ItemStack[] contents = bot.getLoot().getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType().isAir()) {
                continue;
            }
            var left = cart.getInventory().addItem(s.clone());
            if (left.isEmpty()) {
                moved += s.getAmount();
                bot.getLoot().getInventory().setItem(i, null);
            } else {
                ItemStack rem = left.values().iterator().next();
                moved += s.getAmount() - rem.getAmount();
                bot.getLoot().getInventory().setItem(i, rem);
            }
        }
        return moved;
    }

    private void pushCartToward(StorageMinecart cart, Location to) {
        Location c = cart.getLocation();
        if (c.getWorld() == null || to.getWorld() == null || !c.getWorld().equals(to.getWorld())) {
            return;
        }
        Vector v = to.toVector().subtract(c.toVector());
        v.setY(0);
        if (v.lengthSquared() < 0.01) {
            return;
        }
        v.normalize().multiply(0.25);
        cart.setVelocity(v);
    }

    private Location approachRailEnd(Location from, Location to) {
        // Step a few blocks toward storage for walking
        Vector v = to.toVector().subtract(from.toVector());
        if (v.lengthSquared() < 1) {
            return to.clone();
        }
        v.normalize().multiply(Math.min(6, v.length()));
        return from.clone().add(v);
    }

    private void maybeTell(CrewBot bot, String msg) {
        long now = System.currentTimeMillis();
        Long prev = lastMsg.get(bot.getId());
        if (prev != null && now - prev < 15_000L) {
            return;
        }
        lastMsg.put(bot.getId(), now);
        bot.remember(msg);
        var owner = bot.getOwnerPlayer();
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(org.bukkit.ChatColor.GOLD + bot.getName()
                    + org.bukkit.ChatColor.GRAY + ": " + msg);
        }
    }
}
