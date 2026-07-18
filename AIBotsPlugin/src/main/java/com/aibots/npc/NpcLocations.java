package com.aibots.npc;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Footing helpers — Citizens player NPCs often render/sit one block low.
 */
public final class NpcLocations {

    private NpcLocations() {
    }

    public static double yOffset(JavaPlugin plugin) {
        return plugin.getConfig().getDouble("crew.y-offset", 1.0);
    }

    /**
     * Place feet on walkable ground (not tree canopy / leaves).
     * {@code crew.y-offset} lifts further if models sit low.
     */
    public static Location standOnSurface(Location ref, JavaPlugin plugin) {
        if (ref == null || ref.getWorld() == null) {
            return ref;
        }
        World world = ref.getWorld();
        Location out = ref.clone();
        int x = out.getBlockX();
        int z = out.getBlockZ();
        int y = groundY(world, x, z);
        out.setY(y + yOffset(plugin));
        return out;
    }

    /**
     * Highest solid non-leaf / non-log surface — keeps scavengers out of treetops.
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
                return y + 1; // feet on top of this solid
            }
            y--;
        }
        return world.getHighestBlockYAt(x, z) + 1;
    }

    /** Nudge any location up by configured offset (spawn near player). */
    public static Location withYOffset(Location loc, JavaPlugin plugin) {
        if (loc == null) {
            return null;
        }
        return loc.clone().add(0, yOffset(plugin), 0);
    }
}
