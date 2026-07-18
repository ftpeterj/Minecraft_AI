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
     * Place feet on top of the highest block at x/z.
     * {@code crew.y-offset} lifts further (default 1.0 fixes Citizens sitting in the floor).
     */
    public static Location standOnSurface(Location ref, JavaPlugin plugin) {
        if (ref == null || ref.getWorld() == null) {
            return ref;
        }
        World world = ref.getWorld();
        Location out = ref.clone();
        int top = world.getHighestBlockYAt(out.getBlockX(), out.getBlockZ());
        // Block at `top` is solid surface; normal feet = top+1; plus y-offset for NPC sink
        out.setY(top + 1.0 + yOffset(plugin));
        return out;
    }

    /** Nudge any location up by configured offset (spawn near player). */
    public static Location withYOffset(Location loc, JavaPlugin plugin) {
        if (loc == null) {
            return null;
        }
        return loc.clone().add(0, yOffset(plugin), 0);
    }
}
