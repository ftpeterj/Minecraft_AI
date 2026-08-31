package com.aibots.npc;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Footing helpers — stay on the floor the bot is already on; never snap to rooftops.
 */
public final class NpcLocations {

    private NpcLocations() {
    }

    /**
     * Open any closed door/trapdoor/fence gate immediately adjacent to the entity's
     * feet or head. Villager crew bodies have every vanilla AI goal stripped
     * ({@code VillagerHandle.stripGoals}) to stop wandering/trading/etc — which also
     * removes whatever goal would otherwise physically open a door as the entity
     * walks through it. {@code Navigator.setCanOpenDoors(true)} only makes path
     * planning treat a door as passable; nothing then actually swings it open, so a
     * goal-stripped villager just walks into a closed door forever. Bots handle it
     * themselves instead of depending on a goal we deleted. Deliberately narrow
     * (adjacent blocks only, not a wide radius) so it doesn't fling open every door
     * in the building as a bot passes through it.
     */
    public static void openNearbyDoors(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        Location loc = entity.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        int fx = loc.getBlockX();
        int fy = loc.getBlockY();
        int fz = loc.getBlockZ();
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (int dy = 0; dy <= 1; dy++) {
            Block center = world.getBlockAt(fx, fy + dy, fz);
            openIfClosed(center);
            for (BlockFace face : faces) {
                openIfClosed(center.getRelative(face));
            }
        }
    }

    private static void openIfClosed(Block b) {
        BlockData data = b.getBlockData();
        if (data instanceof Openable openable && !openable.isOpen()) {
            openable.setOpen(true);
            b.setBlockData(openable, false);
        }
    }

    public static double yOffset(JavaPlugin plugin) {
        return plugin.getConfig().getDouble("crew.y-offset", 0.0);
    }

    /**
     * Place feet on solid ground near the reference Y (for spawn).
     * Does not use world highest-block (that puts bots on roofs).
     */
    public static Location standOnSurface(Location ref, JavaPlugin plugin) {
        if (ref == null || ref.getWorld() == null) {
            return ref;
        }
        World world = ref.getWorld();
        Location out = ref.clone();
        int x = out.getBlockX();
        int z = out.getBlockZ();
        int preferY = out.getBlockY();
        int y = groundYNear(world, x, preferY, z);
        out.setY(y + yOffset(plugin));
        return out;
    }

    /**
     * Safe summon spot in front of a player: horizontal only (ignore look pitch),
     * same floor as the player, never into the ground when looking down.
     */
    public static Location safeSummonInFront(org.bukkit.entity.Player player, JavaPlugin plugin) {
        if (player == null || player.getWorld() == null) {
            return null;
        }
        Location feet = player.getLocation();
        World world = feet.getWorld();
        int preferY = feet.getBlockY();

        // Horizontal look only — looking down used to push spawn under the floor
        Vector dir = feet.getDirection().clone();
        dir.setY(0);
        if (dir.lengthSquared() < 1.0e-4) {
            // Pitch ±90: use yaw to face
            float yaw = feet.getYaw();
            double rad = Math.toRadians(yaw);
            dir = new Vector(-Math.sin(rad), 0, Math.cos(rad));
        }
        dir.normalize();

        // Try several distances / side steps on the player's floor
        double[] distances = {2.0, 1.5, 2.5, 1.0, 3.0};
        int[] side = {0, 1, -1, 2, -2};
        for (double dist : distances) {
            for (int s : side) {
                Vector right = new Vector(-dir.getZ(), 0, dir.getX()).normalize();
                Location cand = feet.clone().add(dir.clone().multiply(dist)).add(right.multiply(s));
                cand.setY(preferY);
                Location stand = findSafeFeet(world, cand.getX(), preferY, cand.getZ(), preferY);
                if (stand != null) {
                    stand.setYaw(feet.getYaw());
                    stand.setPitch(0f);
                    stand.setY(stand.getY() + yOffset(plugin));
                    return stand;
                }
            }
        }

        // Fallback: beside the player on their block
        Location beside = feet.clone().add(dir.clone().multiply(0.8));
        Location stand = findSafeFeet(world, beside.getX(), preferY, beside.getZ(), preferY);
        if (stand != null) {
            stand.setYaw(feet.getYaw());
            stand.setPitch(0f);
            stand.setY(stand.getY() + yOffset(plugin));
            return stand;
        }

        // Last resort: exact player feet (copy) + tiny forward
        Location last = feet.clone().add(dir.clone().multiply(1.0));
        last.setY(preferY + yOffset(plugin));
        last.setPitch(0f);
        // If still solid, push up until air
        for (int up = 0; up < 4; up++) {
            int fy = preferY + up;
            if (canStandAt(world, last.getBlockX(), fy, last.getBlockZ())) {
                last.setY(fy + yOffset(plugin));
                return last;
            }
        }
        return feet.clone().add(0, yOffset(plugin), 0);
    }

    /**
     * Find standable feet near (x, preferY, z). Prefers same floor as player — only
     * steps ±1–2, never digs deep into caves under the build.
     */
    public static Location findSafeFeet(World world, double x, int preferY, double z, int playerFloorY) {
        if (world == null) {
            return null;
        }
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        // Prefer player's floor first, then one up (step), then one down (shallow)
        int[] tryY = {
                playerFloorY, preferY,
                playerFloorY + 1, preferY + 1,
                playerFloorY - 1, preferY - 1,
                playerFloorY + 2, preferY + 2
        };
        for (int fy : tryY) {
            if (canStandAt(world, bx, fy, bz)) {
                return new Location(world, bx + 0.5, fy, bz + 0.5);
            }
        }
        // Column search: only a short range around player floor (not caves far below)
        for (int dy = 0; dy <= 3; dy++) {
            for (int sign : new int[]{1, -1}) {
                if (dy == 0 && sign != 1) {
                    continue;
                }
                int fy = playerFloorY + sign * dy;
                if (canStandAt(world, bx, fy, bz)) {
                    return new Location(world, bx + 0.5, fy, bz + 0.5);
                }
            }
        }
        return null;
    }

    /**
     * One walking step toward target without jumping onto roofs.
     * Prefers same floor level; allows at most ±1 block step up/down when clear.
     */
    public static Location walkStep(Location from, Location target, double maxStep, JavaPlugin plugin) {
        if (from == null || target == null || from.getWorld() == null) {
            return from;
        }
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.15) {
            // Already close horizontally — only adjust Y if needed for footing
            Location stay = findStandNear(from.getWorld(), from.getX(), from.getY(), from.getZ(), from.getBlockY());
            return stay != null ? withYaw(stay, from, target, plugin) : from;
        }
        double step = Math.min(Math.max(0.35, maxStep), dist);
        double nx = from.getX() + (dx / dist) * step;
        double nz = from.getZ() + (dz / dist) * step;
        int preferY = from.getBlockY();
        int targetY = target.getBlockY();
        // Prefer stepping toward target's floor (get off roofs when home is below)
        int[] tryDy;
        if (targetY < preferY - 1) {
            tryDy = new int[]{-1, 0, 1, -2};
        } else if (targetY > preferY + 1) {
            tryDy = new int[]{1, 0, -1, 2};
        } else {
            tryDy = new int[]{0, 1, -1};
        }

        // Try same level / toward target Y (never snap to world highest-block)
        for (int dy : tryDy) {
            Location cand = findStandNear(from.getWorld(), nx, preferY + dy + 0.1, nz, preferY + dy);
            if (cand != null && isPathClear(from, cand)) {
                return withYaw(cand, from, target, plugin);
            }
        }

        // Blocked straight ahead — small side steps
        double px = -dz / dist;
        double pz = dx / dist;
        for (double side : new double[]{0.8, -0.8, 1.2, -1.2}) {
            double sx = from.getX() + (dx / dist) * (step * 0.5) + px * side;
            double sz = from.getZ() + (dz / dist) * (step * 0.5) + pz * side;
            Location cand = findStandNear(from.getWorld(), sx, preferY + 0.1, sz, preferY);
            if (cand != null && isPathClear(from, cand)) {
                return withYaw(cand, from, target, plugin);
            }
        }
        return from;
    }

    private static Location withYaw(Location feet, Location from, Location target, JavaPlugin plugin) {
        Location out = feet.clone();
        out.setY(out.getY() + yOffset(plugin));
        Vector dir = target.toVector().subtract(from.toVector());
        dir.setY(0);
        if (dir.lengthSquared() > 1.0e-6) {
            out.setDirection(dir);
        }
        out.setPitch(0f);
        return out;
    }

    /**
     * Find a standable position near (x,y,z). Feet must have solid below and headroom.
     */
    public static Location findStandNear(World world, double x, double y, double z, int preferBlockY) {
        if (world == null) {
            return null;
        }
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        // Search around preferred floor first
        for (int dy = 0; dy <= 2; dy++) {
            for (int sign : new int[]{0, 1, -1}) {
                if (dy == 0 && sign != 0) {
                    continue;
                }
                int footY = preferBlockY + sign * dy;
                if (canStandAt(world, bx, footY, bz)) {
                    return new Location(world, bx + 0.5, footY, bz + 0.5);
                }
            }
        }
        return null;
    }

    public static boolean canStandAt(World world, int x, int footY, int z) {
        if (world == null) {
            return false;
        }
        if (footY < world.getMinHeight() + 1 || footY >= world.getMaxHeight() - 1) {
            return false;
        }
        Block below = world.getBlockAt(x, footY - 1, z);
        Block feet = world.getBlockAt(x, footY, z);
        Block head = world.getBlockAt(x, footY + 1, z);
        if (!isSolidGround(below.getType())) {
            return false;
        }
        if (isWatery(feet.getType()) || isWatery(head.getType())) {
            return false;
        }
        if (!isPassable(feet.getType()) || !isPassable(head.getType())) {
            return false;
        }
        return true;
    }

    public static boolean isWatery(Material type) {
        if (type == null) {
            return false;
        }
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN
                || type == Material.KELP || type == Material.KELP_PLANT
                || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS) {
            return true;
        }
        String n = type.name();
        return n.contains("WATER");
    }

    /**
     * If the body is inside a solid or in water, teleport to nearby dry footing.
     */
    public static boolean unstick(Entity entity) {
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return false;
        }
        Location loc = entity.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        Block feet = loc.getBlock();
        Block head = loc.clone().add(0, 1, 0).getBlock();
        boolean inSolid = feet.getType().isSolid() && !isPassable(feet.getType());
        boolean inWater = isWatery(feet.getType()) || isWatery(head.getType());
        boolean standing = canStandAt(world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (!inSolid && !inWater && standing) {
            return false;
        }
        Location dry = findDryStandNear(loc, 5);
        if (dry == null) {
            return false;
        }
        dry.setYaw(loc.getYaw());
        dry.setPitch(0f);
        entity.teleport(dry);
        return true;
    }

    public static Location findDryStandNear(Location loc, int radius) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        World world = loc.getWorld();
        int ox = loc.getBlockX();
        int oy = loc.getBlockY();
        int oz = loc.getBlockZ();
        if (canStandAt(world, ox, oy, oz)) {
            return new Location(world, ox + 0.5, oy, oz + 0.5, loc.getYaw(), 0f);
        }
        for (int r = 1; r <= Math.max(1, radius); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r && r > 0) {
                        continue;
                    }
                    for (int dy = 0; dy <= 2; dy++) {
                        for (int sign : new int[]{0, 1, -1}) {
                            if (dy == 0 && sign != 0) {
                                continue;
                            }
                            int fy = oy + sign * dy;
                            if (canStandAt(world, ox + dx, fy, oz + dz)) {
                                return new Location(world, ox + dx + 0.5, fy, oz + dz + 0.5, loc.getYaw(), 0f);
                            }
                        }
                    }
                }
            }
        }
        return snapToStand(loc);
    }

    /**
     * Snap a location to a standable foot position in the same column (or nearby).
     * Used when nudging stuck crew bodies.
     */
    public static Location snapToStand(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int preferY = loc.getBlockY();
        int gy = groundYNear(world, x, preferY, z);
        if (canStandAt(world, x, gy, z)) {
            return new Location(world, x + 0.5, gy, z + 0.5, loc.getYaw(), 0f);
        }
        // Try adjacent columns
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                int ny = groundYNear(world, nx, preferY, nz);
                if (canStandAt(world, nx, ny, nz)) {
                    return new Location(world, nx + 0.5, ny, nz + 0.5, loc.getYaw(), 0f);
                }
            }
        }
        return loc.clone();
    }

    /** True if moving from→to doesn't clip through solid blocks at body height. */
    private static boolean isPathClear(Location from, Location to) {
        World world = from.getWorld();
        if (world == null) {
            return false;
        }
        int y = Math.max(from.getBlockY(), to.getBlockY());
        // Sample mid-point at body height
        double mx = (from.getX() + to.getX()) / 2.0;
        double mz = (from.getZ() + to.getZ()) / 2.0;
        int bx = (int) Math.floor(mx);
        int bz = (int) Math.floor(mz);
        Block midFeet = world.getBlockAt(bx, y, bz);
        Block midHead = world.getBlockAt(bx, y + 1, bz);
        return isPassable(midFeet.getType()) && isPassable(midHead.getType());
    }

    public static boolean isSolidGround(Material type) {
        if (type == null || type.isAir()) {
            return false;
        }
        if (!type.isSolid()) {
            return false;
        }
        String n = type.name();
        // Don't stand on leaves / fluid-ish
        if (n.contains("LEAVES") || n.contains("VINE") || n.equals("BAMBOO")) {
            return false;
        }
        return true;
    }

    public static boolean isPassable(Material type) {
        if (type == null || type.isAir()) {
            return true;
        }
        if (!type.isSolid()) {
            return true;
        }
        String n = type.name();
        return n.contains("LEAVES")
                || n.contains("VINE")
                || n.contains("SIGN")
                || n.contains("CARPET")
                || n.contains("BUTTON")
                || n.contains("PRESSURE_PLATE")
                || n.contains("TORCH")
                || n.contains("BANNER")
                || n.equals("SNOW")
                || n.equals("BAMBOO");
    }

    /**
     * Ground Y near a preferred height (for spawn). Walks down/up a short range —
     * never jumps to world sky roof.
     */
    public static int groundYNear(World world, int x, int preferY, int z) {
        int min = world.getMinHeight() + 1;
        int max = world.getMaxHeight() - 2;
        preferY = Math.max(min, Math.min(max, preferY));
        // Prefer solid immediately below preferY
        for (int y = preferY; y >= preferY - 8 && y >= min; y--) {
            if (canStandAt(world, x, y, z)) {
                return y;
            }
        }
        for (int y = preferY + 1; y <= preferY + 3 && y <= max; y++) {
            if (canStandAt(world, x, y, z)) {
                return y;
            }
        }
        // Last resort: classic highest non-leaf (outdoor spawn)
        return groundY(world, x, z);
    }

    /**
     * Highest solid non-leaf surface — outdoor only fallback.
     */
    public static int groundY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        int min = world.getMinHeight();
        while (y > min) {
            var type = world.getBlockAt(x, y, z).getType();
            String n = type.name();
            boolean passable = !type.isSolid()
                    || n.contains("LEAVES")
                    || n.contains("LOG")
                    || n.contains("VINE")
                    || n.contains("SCAFFOLD")
                    || n.equals("BAMBOO");
            if (!passable) {
                return y + 1;
            }
            y--;
        }
        return world.getHighestBlockYAt(x, z) + 1;
    }

    public static Location withYOffset(Location loc, JavaPlugin plugin) {
        if (loc == null) {
            return null;
        }
        return loc.clone().add(0, yOffset(plugin), 0);
    }

    /**
     * Apply gravity to a crew body. NoAI entities often ignore vanilla fall;
     * this drops them onto solid ground when feet are in air.
     *
     * @return true if the entity was moved downward
     */
    public static boolean applyGravity(Entity entity) {
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return false;
        }
        // Citizens player NPCs have real gravity + navigator — don't snap them
        if (entity instanceof org.bukkit.entity.Player) {
            return false;
        }
        // Don't fight active pathfinding — navigation handles slopes/steps
        if (entity instanceof org.bukkit.entity.Mob mob) {
            try {
                if (mob.getPathfinder().hasPath()) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
            // On ground with AI → vanilla gravity is enough
            try {
                if (mob.isOnGround()) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
        }
        if (entity instanceof LivingEntity living) {
            try {
                living.setGravity(true);
            } catch (Throwable ignored) {
            }
        }
        Location loc = entity.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        // Feet at floor of bounding box
        int footY = (int) Math.floor(loc.getY() + 1.0e-4);
        // Already supported?
        Block below = world.getBlockAt(x, footY - 1, z);
        Block feet = world.getBlockAt(x, footY, z);
        if (isSolidGround(below.getType()) && isPassable(feet.getType())) {
            // Snug to floor if floating a bit above the block
            double idealY = footY;
            if (loc.getY() > idealY + 0.05 && loc.getY() < idealY + 0.6) {
                // small float — leave for vanilla / next step
            }
            return false;
        }
        // In air or inside a block — find nearest standable Y below (then slight above)
        int minY = Math.max(world.getMinHeight() + 1, footY - 48);
        Integer landY = null;
        for (int y = footY; y >= minY; y--) {
            if (canStandAt(world, x, y, z)) {
                landY = y;
                break;
            }
        }
        if (landY == null) {
            // Try a bit above if embedded in floor
            for (int y = footY + 1; y <= footY + 3 && y < world.getMaxHeight() - 1; y++) {
                if (canStandAt(world, x, y, z)) {
                    landY = y;
                    break;
                }
            }
        }
        if (landY == null) {
            // Still falling with velocity if engine allows
            try {
                Vector v = entity.getVelocity();
                if (v.getY() > -1.2) {
                    entity.setVelocity(v.clone().setY(Math.max(v.getY() - 0.12, -1.2)));
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
        double destY = landY;
        // Smooth multi-block falls: drop at most ~1.5 blocks per physics tick
        double currentY = loc.getY();
        if (currentY - destY > 1.5) {
            destY = currentY - 1.5;
            // Don't stop mid-air on a non-standable partial step unless solid appears
            int tryFoot = (int) Math.floor(destY);
            if (!canStandAt(world, x, tryFoot, z) && !isSolidGround(world.getBlockAt(x, tryFoot - 1, z).getType())) {
                // keep falling
            } else if (canStandAt(world, x, tryFoot, z)) {
                destY = tryFoot;
            }
        }
        if (Math.abs(currentY - destY) < 0.02) {
            return false;
        }
        Location dest = loc.clone();
        dest.setY(destY);
        dest.setPitch(0f);
        entity.teleport(dest);
        // Zero upward velocity after correction
        try {
            Vector v = entity.getVelocity();
            if (v.getY() > 0) {
                entity.setVelocity(v.clone().setY(0));
            } else if (destY == landY) {
                entity.setVelocity(v.clone().setY(0));
            } else {
                entity.setVelocity(v.clone().setY(Math.min(v.getY(), -0.25)));
            }
        } catch (Throwable ignored) {
        }
        return true;
    }
}
