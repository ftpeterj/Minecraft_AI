package com.aibots.npc;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grid-based A* over block coordinates — an opt-in alternative to vanilla Minecraft's
 * {@code Mob.getPathfinder().moveTo()}, which tonight's live testing found real,
 * repeated limitations in (doors, stairs-down, complex indoor geometry). Reuses the
 * same block-classification helpers ({@link NpcLocations#canStandAt}) already
 * battle-tested by every other movement/digging system in this plugin rather than
 * reinventing "what counts as walkable."
 *
 * Off by default ({@code crew.pathfinding: vanilla}) — see {@code ScavengeSkill
 * .stepToward()}, the one call site that opts into this when the config flag is set
 * to {@code custom}. Time-sliced: a bounded number of A* node expansions run per
 * call rather than searching to completion synchronously, so a long search never
 * hitches the tick thread — spread across as many crew ticks as it takes.
 */
public final class CrewPathfinder {

    private final JavaPlugin plugin;
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

    public CrewPathfinder(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void clear(UUID botId) {
        jobs.remove(botId);
    }

    /**
     * Advance this bot's path toward {@code to} by one tick's worth of work — either
     * more A* search, or one step of movement along an already-found path.
     *
     * @return true if the caller should consider this tick "spent" on progress
     *         (searching or moving); false if the path failed or finished and the
     *         caller should fall back to its own logic.
     */
    public boolean step(UUID botId, NpcHandle body, Location from, Location to, double speed) {
        if (botId == null || body == null || from == null || from.getWorld() == null
                || to == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return false;
        }

        Block goalBlock = to.getBlock();
        Job job = jobs.get(botId);
        if (job == null || !job.goal.equals(goalBlock)) {
            job = newJob(from.getBlock(), goalBlock);
            jobs.put(botId, job);
        }

        if (job.result == null) {
            if (job.failed) {
                jobs.remove(botId);
                return false;
            }
            advanceSearch(job);
            return true;
        }

        return advanceAlongPath(job, body, speed);
    }

    // ---- search ----

    private static final class SearchNode {
        Block block;
        SearchNode parent;
        double g;
        double f;
    }

    private static final class Job {
        Block start;
        Block goal;
        PriorityQueue<SearchNode> open;
        Map<Block, Double> bestG;
        Set<Block> closed;
        int nodesExpanded;
        boolean failed;
        List<Location> result;
        int waypointIndex;
    }

    private Job newJob(Block start, Block goal) {
        Job job = new Job();
        job.start = start;
        job.goal = goal;
        job.open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        job.bestG = new HashMap<>();
        job.closed = new HashSet<>();
        SearchNode startNode = new SearchNode();
        startNode.block = start;
        startNode.g = 0;
        startNode.f = heuristic(start, goal);
        job.open.add(startNode);
        job.bestG.put(start, 0.0);
        return job;
    }

    private void advanceSearch(Job job) {
        int budget = plugin.getConfig().getInt("crew.pathfinding-nodes-per-tick", 300);
        int maxNodes = plugin.getConfig().getInt("crew.pathfinding-max-nodes", 4000);
        int maxFall = plugin.getConfig().getInt("crew.pathfinding-max-fall", 3);

        for (int i = 0; i < budget; i++) {
            if (job.open.isEmpty()) {
                job.failed = true;
                return;
            }
            SearchNode cur = job.open.poll();
            if (job.closed.contains(cur.block)) {
                // stale duplicate from lazy-deletion decrease-key — skip, not an error
                continue;
            }
            if (cur.block.equals(job.goal)) {
                job.result = reconstruct(cur);
                return;
            }
            job.closed.add(cur.block);
            job.nodesExpanded++;
            if (job.nodesExpanded > maxNodes) {
                job.failed = true;
                return;
            }
            for (SearchNode neighbor : neighbors(cur, maxFall)) {
                if (job.closed.contains(neighbor.block)) {
                    continue;
                }
                Double knownG = job.bestG.get(neighbor.block);
                if (knownG != null && knownG <= neighbor.g) {
                    continue;
                }
                job.bestG.put(neighbor.block, neighbor.g);
                neighbor.f = neighbor.g + heuristic(neighbor.block, job.goal);
                neighbor.parent = cur;
                job.open.add(neighbor);
            }
        }
        // Budget exhausted this call, not the search — job stays in the map, resumes
        // next call (job.result and job.failed both still unset).
    }

    private List<SearchNode> neighbors(SearchNode cur, int maxFall) {
        List<SearchNode> out = new ArrayList<>(6);
        Block b = cur.block;
        World world = b.getWorld();
        int[][] cardinal = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : cardinal) {
            int nx = b.getX() + d[0];
            int nz = b.getZ() + d[1];

            if (NpcLocations.canStandAt(world, nx, b.getY(), nz)) {
                out.add(node(world, nx, b.getY(), nz, cur.g + 1.0));
                continue; // same-level reachable — don't also offer step-up/down here
            }
            if (NpcLocations.canStandAt(world, nx, b.getY() + 1, nz)) {
                out.add(node(world, nx, b.getY() + 1, nz, cur.g + 1.2));
                continue;
            }
            for (int dy = 1; dy <= maxFall; dy++) {
                int ny = b.getY() - dy;
                if (NpcLocations.canStandAt(world, nx, ny, nz)) {
                    out.add(node(world, nx, ny, nz, cur.g + 1.0 + dy * 0.1));
                    break;
                }
            }
        }
        return out;
    }

    private static SearchNode node(World world, int x, int y, int z, double g) {
        SearchNode n = new SearchNode();
        n.block = world.getBlockAt(x, y, z);
        n.g = g;
        return n;
    }

    private static double heuristic(Block a, Block b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private List<Location> reconstruct(SearchNode end) {
        List<Location> waypoints = new ArrayList<>();
        SearchNode cur = end;
        while (cur != null) {
            waypoints.add(new Location(cur.block.getWorld(), cur.block.getX() + 0.5,
                    cur.block.getY(), cur.block.getZ() + 0.5));
            cur = cur.parent;
        }
        java.util.Collections.reverse(waypoints);
        return waypoints;
    }

    // ---- following a found path ----

    private boolean advanceAlongPath(Job job, NpcHandle body, double speed) {
        if (job.waypointIndex >= job.result.size()) {
            return false;
        }
        Location loc = body.getLocation();
        if (loc == null) {
            return false;
        }
        Location waypoint = job.result.get(job.waypointIndex);
        if (loc.distanceSquared(waypoint) < 1.0) {
            job.waypointIndex++;
            if (job.waypointIndex >= job.result.size()) {
                return false;
            }
            waypoint = job.result.get(job.waypointIndex);
        }
        // Each hop is one pre-validated block step — short enough that vanilla
        // pathfinding's real limitation (routing a long/complex path) never comes
        // into play; it only ever has to solve "walk one block."
        body.walkTo(waypoint, speed);
        return true;
    }
}
