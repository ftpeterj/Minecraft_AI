package com.aibots.npc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

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

    /**
     * @param bareName plain NPC name used for registry (no color codes — those break skins)
     * @param nameplate display name with optional colors
     * @param skin Mojang username to pull skin from (e.g. owner name, Steve, Notch)
     */
    public static CitizensHandle spawn(Location loc, String bareName, String nameplate, String skin, JavaPlugin plugin) {
        try {
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Method getRegistry = api.getMethod("getNPCRegistry");
            Object registry = getRegistry.invoke(null);

            String regName = stripColor(bareName == null || bareName.isBlank() ? "CrewBot" : bareName);
            if (regName.length() > 16) {
                regName = regName.substring(0, 16);
            }

            Method createNPC = registry.getClass().getMethod("createNPC", EntityType.class, String.class);
            Object npc = createNPC.invoke(registry, EntityType.PLAYER, regName);

            // Persist + look like a real player
            trySetData(npc, "protected", false);
            tryInvoke(npc, "setProtected", new Class[]{boolean.class}, false);

            Method spawn = npc.getClass().getMethod("spawn", Location.class);
            spawn.invoke(npc, loc);

            Method getId = npc.getClass().getMethod("getId");
            Integer id = (Integer) getId.invoke(npc);
            CitizensHandle handle = new CitizensHandle(npc, id);

            // Skin must be applied after spawn; re-apply a few ticks later so Mojang fetch can land
            final String skinName = normalizeSkin(skin);
            final String plate = nameplate != null ? nameplate : regName;
            applySkin(npc, skinName);
            handle.setNameplate(plate);

            if (plugin != null) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    applySkin(npc, skinName);
                    handle.setNameplate(plate);
                }, 10L);
                Bukkit.getScheduler().runTaskLater(plugin, () -> applySkin(npc, skinName), 40L);
            }

            return handle;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[AIBots] Failed to spawn Citizens NPC: " + t.getMessage(), t);
            return null;
        }
    }

    /** Back-compat helper */
    public static CitizensHandle spawn(Location loc, String nameplate, String skin) {
        return spawn(loc, stripColor(nameplate), nameplate, skin, null);
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

    private static String normalizeSkin(String skin) {
        if (skin == null || skin.isBlank()) {
            return "Steve";
        }
        String s = skin.trim();
        // "Steve"/"Alex" classic names sometimes fail Mojang lookup — map to known working profiles
        if (s.equalsIgnoreCase("Steve")) {
            return "MHF_Steve";
        }
        if (s.equalsIgnoreCase("Alex")) {
            return "MHF_Alex";
        }
        return s;
    }

    private static void applySkin(Object npc, String skin) {
        if (skin == null || skin.isBlank() || npc == null) {
            return;
        }
        String skinName = normalizeSkin(skin);
        try {
            Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
            Method getOrAddTrait = npc.getClass().getMethod("getOrAddTrait", Class.class);
            Object trait = getOrAddTrait.invoke(npc, skinTraitClass);

            // Encourage live Mojang fetch / updates
            tryInvoke(trait, "setFetchDefaultSkins", new Class[]{boolean.class}, true);
            tryInvoke(trait, "setShouldUpdateSkins", new Class[]{boolean.class}, true);

            // Force skin name with update flag when available
            boolean applied = false;
            try {
                Method setSkinName2 = trait.getClass().getMethod("setSkinName", String.class, boolean.class);
                setSkinName2.invoke(trait, skinName, true);
                applied = true;
            } catch (NoSuchMethodException ignored) {
            }
            if (!applied) {
                Method setSkinName = trait.getClass().getMethod("setSkinName", String.class);
                setSkinName.invoke(trait, skinName);
            }

            // Some builds expose setSkinPersistent
            tryInvoke(trait, "setSkinPersistent", new Class[]{String.class, String.class, String.class},
                    null); // no-op if wrong signature

            LOG.info("[AIBots] Applied Citizens skin '" + skinName + "'");
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[AIBots] Could not apply skin '" + skinName + "': " + t.getMessage());
        }
    }

    private static void tryInvoke(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getMethod(method, types);
            m.invoke(target, args);
        } catch (Throwable ignored) {
        }
    }

    private static void trySetData(Object npc, String key, Object value) {
        try {
            // npc.data().setPersistent(key, value) — best-effort
            Method data = npc.getClass().getMethod("data");
            Object mem = data.invoke(npc);
            try {
                Method set = mem.getClass().getMethod("setPersistent", String.class, Object.class);
                set.invoke(mem, key, value);
            } catch (NoSuchMethodException e) {
                Method set = mem.getClass().getMethod("set", String.class, Object.class);
                set.invoke(mem, key, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String stripColor(String s) {
        if (s == null) {
            return "Bot";
        }
        return s.replaceAll("§[0-9a-fk-orA-FK-ORxX]", "").replaceAll("&#[0-9a-fA-F]{6}", "").trim();
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
            // Keep registry name plain; use hologram/name for display
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
