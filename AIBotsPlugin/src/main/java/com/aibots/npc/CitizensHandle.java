package com.aibots.npc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Citizens-backed body via reflection so we soft-depend without compile-time Citizens JAR.
 */
public final class CitizensHandle implements NpcHandle {

    private final Object npc; // net.citizensnpcs.api.npc.NPC
    private final Integer id;
    private static final Logger LOG = Bukkit.getLogger();

    private CitizensHandle(Object npc, Integer id) {
        this.npc = npc;
        this.id = id;
    }

    public static boolean isCitizensPresent() {
        Plugin p = Bukkit.getPluginManager().getPlugin("Citizens");
        return p != null && p.isEnabled();
    }

    public static CitizensHandle spawn(Location loc, String nameplate, String skin) {
        try {
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Method getRegistry = api.getMethod("getNPCRegistry");
            Object registry = getRegistry.invoke(null);

            Method createNPC = registry.getClass().getMethod("createNPC", EntityType.class, String.class);
            Object npc = createNPC.invoke(registry, EntityType.PLAYER, stripColor(nameplate));

            Method setName = npc.getClass().getMethod("setName", String.class);
            setName.invoke(npc, nameplate);

            Method spawn = npc.getClass().getMethod("spawn", Location.class);
            spawn.invoke(npc, loc);

            applySkin(npc, skin);

            Method getId = npc.getClass().getMethod("getId");
            Integer id = (Integer) getId.invoke(npc);

            // Look like a player, protected from default despawn
            try {
                Method setProtected = npc.getClass().getMethod("setProtected", boolean.class);
                setProtected.invoke(npc, false);
            } catch (NoSuchMethodException ignored) {
            }

            return new CitizensHandle(npc, id);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[AIBots] Failed to spawn Citizens NPC: " + t.getMessage(), t);
            return null;
        }
    }

    public static CitizensHandle attachExisting(int npcId) {
        try {
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = api.getMethod("getNPCRegistry").invoke(null);
            Method getById = registry.getClass().getMethod("getById", int.class);
            Object npc = getById.invoke(registry, npcId);
            if (npc == null) {
                return null;
            }
            return new CitizensHandle(npc, npcId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void applySkin(Object npc, String skin) {
        if (skin == null || skin.isBlank()) {
            return;
        }
        try {
            // NPC.getOrAddTrait(SkinTrait.class).setSkinName(skin)
            Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
            Method getOrAddTrait = npc.getClass().getMethod("getOrAddTrait", Class.class);
            Object trait = getOrAddTrait.invoke(npc, skinTraitClass);

            // Prefer setSkinName
            try {
                Method setSkinName = trait.getClass().getMethod("setSkinName", String.class);
                setSkinName.invoke(trait, skin);
                return;
            } catch (NoSuchMethodException ignored) {
            }

            Method setSkinName2 = trait.getClass().getMethod("setSkinName", String.class, boolean.class);
            setSkinName2.invoke(trait, skin, true);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "[AIBots] Could not apply skin '" + skin + "': " + t.getMessage());
        }
    }

    private static String stripColor(String s) {
        if (s == null) {
            return "Bot";
        }
        return s.replaceAll("§[0-9a-fk-or]", "");
    }

    @Override
    public String backend() {
        return "citizens";
    }

    @Override
    public boolean isValid() {
        try {
            Method isSpawned = npc.getClass().getMethod("isSpawned");
            return Boolean.TRUE.equals(isSpawned.invoke(npc));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void destroy() {
        try {
            Method destroy = npc.getClass().getMethod("destroy");
            destroy.invoke(npc);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[AIBots] Failed to destroy Citizens NPC: " + t.getMessage());
        }
    }

    @Override
    public void teleport(Location location) {
        if (location == null) {
            return;
        }
        try {
            if (isValid()) {
                Method getEntity = npc.getClass().getMethod("getEntity");
                Entity entity = (Entity) getEntity.invoke(npc);
                if (entity != null) {
                    entity.teleport(location);
                    return;
                }
            }
            Method spawn = npc.getClass().getMethod("spawn", Location.class);
            spawn.invoke(npc, location);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[AIBots] Citizens teleport failed: " + t.getMessage());
        }
    }

    @Override
    public Location getLocation() {
        try {
            Method getStored = npc.getClass().getMethod("getStoredLocation");
            Object loc = getStored.invoke(npc);
            if (loc instanceof Location location) {
                return location;
            }
            Method getEntity = npc.getClass().getMethod("getEntity");
            Entity entity = (Entity) getEntity.invoke(npc);
            return entity == null ? null : entity.getLocation();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void setNameplate(String nameplate) {
        try {
            Method setName = npc.getClass().getMethod("setName", String.class);
            setName.invoke(npc, nameplate);
            if (isValid()) {
                Method getEntity = npc.getClass().getMethod("getEntity");
                Entity entity = (Entity) getEntity.invoke(npc);
                if (entity != null) {
                    entity.setCustomName(nameplate);
                    entity.setCustomNameVisible(true);
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "[AIBots] setNameplate failed: " + t.getMessage());
        }
    }

    @Override
    public void setSkin(String skinNameOrUrl) {
        applySkin(npc, skinNameOrUrl);
    }

    @Override
    public Entity getEntity() {
        try {
            Method getEntity = npc.getClass().getMethod("getEntity");
            return (Entity) getEntity.invoke(npc);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Integer getCitizensId() {
        return id;
    }
}
