package com.aibots.skill;

import com.aibots.crew.CrewBot;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcLocations;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Carves a short, safety-checked corridor toward a buried ore/stone block that has
 * no existing reachable air pocket ({@link ScavengeSkill}'s normal targeting only
 * ever uses pre-existing space). Movement is always horizontal-first with at most
 * one vertical step per move — real staircase mining, never a straight-down shaft.
 * Aborts (does not try to route around) on any lava, void/cave, or falling-block
 * hazard — same graceful "can't reach it" fallback already used for any other
 * unreachable target, not an attempt to be clever about danger.
 */
public final class TunnelDigger {

    /** Bounded corridor length — exceeding this without reaching the target aborts. */
    public static final int MAX_LENGTH = 24;
    /** No point searching for a buried target further than we could ever tunnel to. */
    public static final int MAX_SEARCH_RADIUS = MAX_LENGTH + 6;

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

    private static final class Job {
        Block target;
        int stepsTaken;
    }

    public TunnelDigger(JavaPlugin plugin) {
        // plugin currently unused (no config knobs yet) — kept for parity with the
        // other skill helpers and in case tuning is added later.
    }

    public void clear(CrewBot bot) {
        jobs.remove(bot.getId());
    }

    /** The block an in-progress tunnel is already headed toward, if any — avoids a
     *  fresh area search every tick while a dig is already underway. */
    public Block activeTarget(CrewBot bot) {
        Job job = jobs.get(bot.getId());
        return job == null ? null : job.target;
    }

    /** Nearest matching block with no air-pocket approach — a real tunnel candidate. */
    public Block findBuriedTarget(Location origin, int radius, Predicate<Block> matcher) {
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }
        int r = Math.min(Math.max(radius, 8), MAX_SEARCH_RADIUS);
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    int by = oy + y;
                    if (by < world.getMinHeight() + 2 || by >= world.getMaxHeight() - 2) {
                        continue;
                    }
                    Block b = world.getBlockAt(ox + x, by, oz + z);
                    if (b.getType().isAir() || !matcher.test(b)) {
                        continue;
                    }
                    double d = b.getLocation().distanceSquared(origin);
                    if (d < bestD) {
                        bestD = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Advance one step of the tunnel toward {@code target}.
     *
     * @return true if progress was made (a block was cleared or the bot moved) and
     *         the caller should wait for next tick; false if the attempt was
     *         abandoned (hazard, out of range) and the caller should treat
     *         {@code target} as unreachable, same as any other failed approach.
     */
    public boolean tick(CrewBot bot, NpcHandle body, Block target) {
        Job job = jobs.computeIfAbsent(bot.getId(), id -> new Job());
        if (job.target == null || !sameBlock(job.target, target)) {
            job.target = target;
            job.stepsTaken = 0;
        }
        if (job.stepsTaken >= MAX_LENGTH) {
            jobs.remove(bot.getId());
            return false;
        }

        Location from = body.getLocation();
        if (from == null || from.getWorld() == null) {
            return false;
        }
        World world = from.getWorld();

        Location targetCenter = target.getLocation().add(0.5, 0.5, 0.5);
        if (from.distanceSquared(targetCenter) <= 9.0) {
            // Close enough — normal mine-tick logic takes it from here.
            jobs.remove(bot.getId());
            return true;
        }

        int fx = from.getBlockX();
        int fy = from.getBlockY();
        int fz = from.getBlockZ();
        int tx = target.getX();
        int ty = target.getY();
        int tz = target.getZ();
        int dx = Integer.compare(tx, fx);
        int dz = Integer.compare(tz, fz);
        int dy = Integer.compare(ty, fy);

        int nx = fx;
        int ny = fy;
        int nz = fz;
        if (dx != 0 || dz != 0) {
            if (Math.abs(tx - fx) >= Math.abs(tz - fz) && dx != 0) {
                nx = fx + dx;
            } else {
                nz = fz + dz;
            }
            // At most one vertical step alongside a horizontal one — staircase, never sheer.
            if (dy != 0) {
                ny = fy + dy;
            }
        } else if (dy != 0) {
            ny = fy + dy;
        } else {
            jobs.remove(bot.getId());
            return true;
        }

        Block feet = world.getBlockAt(nx, ny, nz);
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);

        if (anyLavaNear(feet) || anyLavaNear(head) || isHazard(floor.getType())) {
            jobs.remove(bot.getId());
            return false;
        }
        if (isOpenBelow(floor)) {
            jobs.remove(bot.getId());
            return false;
        }

        // Falling block overhead: clear it before stepping under it, not after.
        Block overhead = head.getRelative(0, 1, 0);
        if (isFalling(overhead.getType())) {
            overhead.setType(Material.AIR, false);
            return true;
        }

        if (!NpcLocations.isPassable(feet.getType())) {
            feet.setType(Material.AIR, false);
        } else if (!NpcLocations.isPassable(head.getType())) {
            head.setType(Material.AIR, false);
        } else {
            body.walkTo(new Location(world, nx + 0.5, ny, nz + 0.5), 0.9);
        }
        body.swingMainHand();
        job.stepsTaken++;
        return true;
    }

    private static boolean sameBlock(Block a, Block b) {
        return a.getWorld().equals(b.getWorld()) && a.getX() == b.getX()
                && a.getY() == b.getY() && a.getZ() == b.getZ();
    }

    private static boolean isHazard(Material t) {
        return t == Material.LAVA || t == Material.FIRE;
    }

    private static boolean isFalling(Material t) {
        return t == Material.SAND || t == Material.RED_SAND || t == Material.GRAVEL;
    }

    /** Lava directly here or in any cardinal neighbor — checked before breaking anything. */
    private static boolean anyLavaNear(Block center) {
        if (isHazard(center.getType())) {
            return true;
        }
        return isHazard(center.getRelative(1, 0, 0).getType())
                || isHazard(center.getRelative(-1, 0, 0).getType())
                || isHazard(center.getRelative(0, 0, 1).getType())
                || isHazard(center.getRelative(0, 0, -1).getType());
    }

    /** No solid ground within 3 blocks below — possible ravine/cave/void, don't step in blind. */
    private static boolean isOpenBelow(Block floor) {
        Block b = floor;
        for (int i = 0; i < 3; i++) {
            Material t = b.getType();
            if (t.isSolid() && !NpcLocations.isPassable(t)) {
                return false;
            }
            b = b.getRelative(0, -1, 0);
        }
        return true;
    }
}
