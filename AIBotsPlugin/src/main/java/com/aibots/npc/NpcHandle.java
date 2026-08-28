package com.aibots.npc;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

/**
 * Opaque handle for a spawned crew body (villager / Citizens / ArmorStand).
 */
public interface NpcHandle {

    String backend();

    boolean isValid();

    void destroy();

    /** Hard relocate (spawn, rescue). Prefer {@link #walkTo} for movement. */
    void teleport(Location location);

    /**
     * Walk toward a target using real pathfinding when available.
     *
     * @param speed pathfinder speed multiplier (1.0 = normal)
     * @return true if a path or step was started
     */
    default boolean walkTo(Location target, double speed) {
        teleport(target);
        return true;
    }

    /** Cancel active pathfinding. */
    default void stopWalking() {
    }

    /** True if currently following a path. */
    default boolean isWalking() {
        return false;
    }

    Location getLocation();

    void setNameplate(String nameplate);

    void setSkin(String skinNameOrUrl);

    Entity getEntity();

    /** Citizens NPC id if applicable; null for fallback. */
    Integer getCitizensId();

    /** Put a tool in the main hand (player NPCs). */
    default void equipMainHand(ItemStack item) {
    }

    /** Face a world location. */
    default void lookAt(Location loc) {
    }

    /** Play a player arm-swing animation if the body supports it. */
    default void swingMainHand() {
    }
}
