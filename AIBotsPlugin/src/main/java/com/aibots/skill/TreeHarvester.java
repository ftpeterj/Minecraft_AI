package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.CrewBot;
import com.aibots.learn.LearningService;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcLocations;
import com.aibots.npc.NpcService;
import com.aibots.storage.ProtectedZones;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Harvests an entire tree once a Gatherer starts on one, instead of just the nearest
 * log: flood-fills the connected trunk, works through every log — climbing beside the
 * trunk itself for logs above normal reach (no placed blocks, no height cap, since
 * there's nothing to run out of) — and replants a sapling at the base if one ends up
 * in the bag once the trunk is cleared.
 */
public final class TreeHarvester {

    private static final Map<Material, Material> LOG_TO_SAPLING = new HashMap<>();

    static {
        LOG_TO_SAPLING.put(Material.OAK_LOG, Material.OAK_SAPLING);
        LOG_TO_SAPLING.put(Material.SPRUCE_LOG, Material.SPRUCE_SAPLING);
        LOG_TO_SAPLING.put(Material.BIRCH_LOG, Material.BIRCH_SAPLING);
        LOG_TO_SAPLING.put(Material.JUNGLE_LOG, Material.JUNGLE_SAPLING);
        LOG_TO_SAPLING.put(Material.ACACIA_LOG, Material.ACACIA_SAPLING);
        LOG_TO_SAPLING.put(Material.DARK_OAK_LOG, Material.DARK_OAK_SAPLING);
        LOG_TO_SAPLING.put(Material.CRIMSON_STEM, Material.CRIMSON_FUNGUS);
        LOG_TO_SAPLING.put(Material.WARPED_STEM, Material.WARPED_FUNGUS);
        try {
            LOG_TO_SAPLING.put(Material.CHERRY_LOG, Material.CHERRY_SAPLING);
        } catch (Throwable ignored) {
        }
        try {
            LOG_TO_SAPLING.put(Material.MANGROVE_LOG, Material.MANGROVE_PROPAGULE);
        } catch (Throwable ignored) {
        }
    }

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private final LearningService learning;
    private final ProtectedZones protectedZones;
    private final Map<UUID, TreeJob> jobs = new ConcurrentHashMap<>();

    private static final class TreeJob {
        List<Block> remainingLogs;
        Material species;
        Location baseLocation;
        // Sticky current pick — recomputing "nearest remaining log" fresh every tick
        // (like recomputing its approach point fresh every tick) let it flip between
        // two similarly-close logs as the bot's position shifted slightly each tick,
        // same oscillation bug already found and fixed once in ScavengeSkill's own
        // targeting. Fixed the same way here: pick once, keep it until harvested.
        Block currentTarget;
        Location currentApproach;
        boolean currentIsClimb;
    }

    public TreeHarvester(JavaPlugin plugin, NpcService npcService, LearningService learning,
                          ProtectedZones protectedZones) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.learning = learning;
        this.protectedZones = protectedZones;
    }

    public boolean isActive(CrewBot bot) {
        return jobs.containsKey(bot.getId());
    }

    public void clear(CrewBot bot) {
        if (jobs.remove(bot.getId()) != null) {
            npcService.setClimbing(bot.getId(), false);
        }
    }

    /**
     * @param seed only used to start a fresh job when none is active yet — pass the
     *             candidate log target. Ignored (may be null) while a job is already
     *             in progress; callers should check {@link #isActive} first.
     * @return true if this tick was spent working the tree (caller should return and
     *         let this run again next tick); false if the job just finished (or never
     *         started), and the caller should fall through to normal targeting.
     */
    public boolean tick(CrewBot bot, NpcHandle body, Block seed) {
        TreeJob job = jobs.get(bot.getId());
        if (job == null) {
            if (seed == null) {
                return false;
            }
            job = discover(seed);
            if (job == null || job.remainingLogs.isEmpty()) {
                return false;
            }
            jobs.put(bot.getId(), job);
        }

        Location loc = body.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return false;
        }

        // Drop logs that vanished some other way (player mined it, block update) or
        // that fall inside a zone registered protected since this job started.
        job.remainingLogs.removeIf(b -> b.getType().isAir()
                || (protectedZones != null
                && protectedZones.isProtected(b.getLocation().add(0.5, 0.5, 0.5))));

        if (job.remainingLogs.isEmpty()) {
            finish(bot, job);
            jobs.remove(bot.getId());
            npcService.setClimbing(bot.getId(), false);
            return false;
        }

        if (job.currentTarget == null || !job.remainingLogs.contains(job.currentTarget)) {
            Block next = nearestRemaining(job, loc);
            job.currentTarget = next;
            Location approach = approachNear(next, loc);
            job.currentIsClimb = approach == null;
            job.currentApproach = job.currentIsClimb ? besideLog(next) : approach;
        }
        npcService.setClimbing(bot.getId(), job.currentIsClimb);

        if (job.currentIsClimb) {
            // No ground-level approach — climb beside the trunk itself. Deliberately
            // airborne with nothing solid below; NpcService.setClimbing() stops the
            // gravity/unstick safety net from yanking the bot back to the ground.
            if (loc.distanceSquared(job.currentApproach) > 4.0) {
                body.teleport(job.currentApproach);
                body.lookAt(job.currentTarget.getLocation().add(0.5, 0.5, 0.5));
                return true;
            }
        } else {
            if (loc.distanceSquared(job.currentApproach) > 6.25) {
                body.walkTo(job.currentApproach, 0.9);
                return true;
            }
            body.stopWalking();
        }
        harvest(bot, body, job.currentTarget, job);
        job.currentTarget = null;
        job.currentApproach = null;
        return true;
    }

    private TreeJob discover(Block seed) {
        if (!GatherFocus.isTreeLog(seed.getType())) {
            return null;
        }
        int maxBlocks = plugin.getConfig().getInt("titles.gatherer.tree-harvest-max-blocks", 64);
        Set<Block> visited = new LinkedHashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        visited.add(seed);
        queue.add(seed);
        Material species = seed.getType();
        Block lowest = seed;

        while (!queue.isEmpty() && visited.size() < maxBlocks) {
            Block cur = queue.poll();
            if (cur.getY() < lowest.getY()) {
                lowest = cur;
            }
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                    BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                if (visited.size() >= maxBlocks) {
                    break;
                }
                Block n = cur.getRelative(f);
                if (visited.contains(n) || !GatherFocus.isTreeLog(n.getType())) {
                    continue;
                }
                if (protectedZones != null
                        && protectedZones.isProtected(n.getLocation().add(0.5, 0.5, 0.5))) {
                    continue;
                }
                visited.add(n);
                queue.add(n);
            }
        }

        TreeJob job = new TreeJob();
        job.remainingLogs = new ArrayList<>(visited);
        job.species = species;
        job.baseLocation = lowest.getLocation().clone();
        return job;
    }

    private Block nearestRemaining(TreeJob job, Location from) {
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (Block b : job.remainingLogs) {
            double d = b.getLocation().distanceSquared(from);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    /** Simplified stand-adjacent search — ground-level trunk base mostly, tall logs
     *  fail this (no solid ground that high) and fall through to climbing instead. */
    private Location approachNear(Block resource, Location from) {
        World world = resource.getWorld();
        if (world == null) {
            return null;
        }
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
                BlockFace.NORTH_EAST, BlockFace.NORTH_WEST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST};
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int yOff : new int[]{0, -1, 1}) {
            for (BlockFace face : faces) {
                Block n = resource.getRelative(face.getModX(), yOff, face.getModZ());
                if (!NpcLocations.canStandAt(world, n.getX(), n.getY(), n.getZ())) {
                    continue;
                }
                Location feet = new Location(world, n.getX() + 0.5, n.getY(), n.getZ() + 0.5);
                double d = from == null ? 0 : feet.distanceSquared(from);
                if (d < bestD) {
                    bestD = d;
                    best = feet;
                }
            }
        }
        return best;
    }

    /** A passable (leaves/air) spot immediately beside a log, at the log's own height —
     *  the trunk itself is the anchor, not solid ground underfoot. */
    private Location besideLog(Block log) {
        World world = log.getWorld();
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block adj = log.getRelative(f);
            if (NpcLocations.isPassable(adj.getType())) {
                return new Location(world, adj.getX() + 0.5, log.getY(), adj.getZ() + 0.5);
            }
        }
        return new Location(world, log.getX() + 0.5, log.getY(), log.getZ() + 0.5);
    }

    private void harvest(CrewBot bot, NpcHandle body, Block log, TreeJob job) {
        ItemStack axe = bestAxe(bot);
        body.lookAt(log.getLocation().add(0.5, 0.5, 0.5));
        body.equipMainHand(axe);
        body.swingMainHand();
        for (ItemStack drop : log.getDrops(axe)) {
            ItemStack left = bot.getLoot().add(drop);
            if (left != null) {
                log.getWorld().dropItemNaturally(log.getLocation().add(0.5, 0.5, 0.5), left);
            }
        }
        log.setType(Material.AIR, false);
        job.remainingLogs.remove(log);
        bot.setStatus(BotStatus.BUSY);
    }

    /** Whatever axe the bot is actually carrying, not a hardcoded stand-in — so a
     *  netherite axe someone hands the bot actually gets held and used. */
    private ItemStack bestAxe(CrewBot bot) {
        ItemStack axe = bot.getLoot().findFirst(i -> i.getType().name().endsWith("_AXE"));
        return axe != null ? axe : new ItemStack(Material.IRON_AXE);
    }

    private void finish(CrewBot bot, TreeJob job) {
        Material sapling = LOG_TO_SAPLING.get(job.species);
        if (sapling != null && job.baseLocation != null && bot.getLoot().count(sapling) > 0) {
            Block base = job.baseLocation.getBlock();
            Block above = base.getRelative(BlockFace.UP);
            if (above.getType().isAir()) {
                bot.getLoot().remove(sapling, 1);
                above.setType(sapling);
                BlockData data = above.getBlockData();
                if (data instanceof Ageable age) {
                    age.setAge(0);
                    above.setBlockData(age);
                }
                learning.observe(bot, "farm", "Replanted " + sapling.name() + " after clearing a tree", true, null);
            }
        }
        String label = job.species.name().toLowerCase(Locale.ROOT).replace("_log", "")
                .replace("_stem", "").replace('_', ' ');
        bot.remember("Cleared a " + label + " tree");
        learning.observe(bot, "scavenge", "Harvested whole tree (" + job.species.name() + ")", true, null);
    }
}
