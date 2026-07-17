package com.aibots.npc;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Opaque handle for a spawned crew body (Citizens NPC or ArmorStand).
 */
public interface NpcHandle {

    String backend();

    boolean isValid();

    void destroy();

    void teleport(Location location);

    Location getLocation();

    void setNameplate(String nameplate);

    void setSkin(String skinNameOrUrl);

    Entity getEntity();

    /** Citizens NPC id if applicable; null for fallback. */
    Integer getCitizensId();
}
