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
 * Citizens-backed body via reflection.
 * Critical: NPC registry name must stay plain (no color / brackets) or skins break.
 */
public final class CitizensHandle implements NpcHandle {

    private final Object npc;
    private final Integer id;
    private final JavaPlugin plugin;
    private String displayPlate;
    private static final Logger LOG = Bukkit.getLogger();

    private CitizensHandle(Object npc, Integer id, JavaPlugin plugin) {
        this.npc = npc;
        this.id = id;
        this.plugin = plugin;
    }

    public static boolean isCitizensPresent() {
        Plugin p = Bukkit.getPluginManager().getPlugin("Citizens");
        return p != null && p.isEnabled();
    }

    /**
     * @param bareName plain name only (max 16), used for entity / skin compatibility
     * @param displayPlate optional colored hologram text (title line)
     * @param skin Mojang username or online player name to copy skin from
     */
    public static CitizensHandle spawn(Location loc, String bareName, String displayPlate, String skin, JavaPlugin plugin) {
        try {
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = api.getMethod("getNPCRegistry").invoke(null);

            String regName = sanitizeName(bareName);
            Method createNPC = registry.getClass().getMethod("createNPC", EntityType.class, String.class);
            Object npc = createNPC.invoke(registry, EntityType.PLAYER, regName);

            tryInvoke(npc, "setProtected", new Class[]{boolean.class}, false);

            // Keep nameplate simple — colored titles go on hologram so skins stay intact
            Method setName = npc.getClass().getMethod("setName", String.class);
            setName.invoke(npc, regName);

            Method spawn = npc.getClass().getMethod("spawn", Location.class);
            spawn.invoke(npc, loc);

            Method getId = npc.getClass().getMethod("getId");
            Integer id = (Integer) getId.invoke(npc);
            CitizensHandle handle = new CitizensHandle(npc, id, plugin);
            handle.displayPlate = displayPlate;

            // Hologram line for [Title] without touching the skin name
            applyHologram(npc, regName, displayPlate);

            // Skin: copy online player / Mojang textures (not fragile setSkinName alone)
            SkinApplier.apply(npc, skin, plugin);

            // Re-assert plain name after skin (some skin paths rename)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    setName.invoke(npc, regName);
                    applyHologram(npc, regName, displayPlate);
                    SkinApplier.apply(npc, skin, plugin);
                } catch (Throwable ignored) {
                }
            }, 15L);

            return handle;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[AIBots] Failed to spawn Citizens NPC: " + t.getMessage(), t);
            return null;
        }
    }

    public static CitizensHandle spawn(Location loc, String nameplate, String skin) {
        String bare = sanitizeName(stripColor(nameplate));
        return spawn(loc, bare, nameplate, skin, null);
    }

    public static CitizensHandle attachExisting(int npcId) {
        return attachExisting(npcId, null);
    }

    public static CitizensHandle attachExisting(int npcId, JavaPlugin plugin) {
        try {
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = api.getMethod("getNPCRegistry").invoke(null);
            Object npc = registry.getClass().getMethod("getById", int.class).invoke(registry, npcId);
            if (npc == null) {
                return null;
            }
            return new CitizensHandle(npc, npcId, plugin);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void applyHologram(Object npc, String line1, String line2) {
        try {
            Class<?> holoClass = Class.forName("net.citizensnpcs.trait.text.Text");
            // Older/newer may differ — try HologramTrait
        } catch (ClassNotFoundException ignored) {
        }
        try {
            Class<?> holoClass = Class.forName("net.citizensnpcs.trait.HologramTrait");
            Method getOrAddTrait = npc.getClass().getMethod("getOrAddTrait", Class.class);
            Object holo = getOrAddTrait.invoke(npc, holoClass);
            // clear + add line
            tryInvoke(holo, "clear", new Class[]{});
            if (line2 != null && !line2.isBlank() && !stripColor(line2).equals(stripColor(line1))) {
                // Prefer showing title only as hologram if different from bare name
                String titleOnly = line2;
                // If plate is "§6Rusty §7[Scavenger]", extract bracket part
                int lb = line2.lastIndexOf('[');
                int rb = line2.lastIndexOf(']');
                if (lb >= 0 && rb > lb) {
                    titleOnly = line2.substring(lb, rb + 1);
                }
                try {
                    Method setLine = holo.getClass().getMethod("setLine", int.class, String.class);
                    setLine.invoke(holo, 0, titleOnly);
                    return;
                } catch (NoSuchMethodException ignored) {
                }
                try {
                    Method addLine = holo.getClass().getMethod("addLine", String.class);
                    addLine.invoke(holo, titleOnly);
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable t) {
            // hologram optional
        }
    }

    private static String sanitizeName(String name) {
        String s = stripColor(name == null ? "Bot" : name);
        s = s.replaceAll("[^A-Za-z0-9_]", "");
        if (s.isEmpty()) {
            s = "Bot";
        }
        if (s.length() > 16) {
            s = s.substring(0, 16);
        }
        return s;
    }

    private static String stripColor(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
    }

    private static void tryInvoke(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getMethod(method, types);
            m.invoke(target, args);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String backend() {
        return "citizens";
    }

    @Override
    public boolean isValid() {
        try {
            return Boolean.TRUE.equals(npc.getClass().getMethod("isSpawned").invoke(npc));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void destroy() {
        try {
            npc.getClass().getMethod("destroy").invoke(npc);
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
                Entity entity = (Entity) npc.getClass().getMethod("getEntity").invoke(npc);
                if (entity != null) {
                    entity.teleport(location);
                    return;
                }
            }
            npc.getClass().getMethod("spawn", Location.class).invoke(npc, location);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[AIBots] Citizens teleport failed: " + t.getMessage());
        }
    }

    @Override
    public Location getLocation() {
        try {
            Object loc = npc.getClass().getMethod("getStoredLocation").invoke(npc);
            if (loc instanceof Location location) {
                return location;
            }
            Entity entity = (Entity) npc.getClass().getMethod("getEntity").invoke(npc);
            return entity == null ? null : entity.getLocation();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void setNameplate(String nameplate) {
        this.displayPlate = nameplate;
        try {
            // NEVER put colors/brackets into NPC name — breaks skins
            String bare = sanitizeName(nameplate);
            npc.getClass().getMethod("setName", String.class).invoke(npc, bare);
            applyHologram(npc, bare, nameplate);
            if (isValid()) {
                Entity entity = (Entity) npc.getClass().getMethod("getEntity").invoke(npc);
                if (entity != null) {
                    entity.setCustomName(bare);
                    entity.setCustomNameVisible(true);
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "[AIBots] setNameplate failed: " + t.getMessage());
        }
    }

    @Override
    public void setSkin(String skinNameOrUrl) {
        if (plugin != null) {
            SkinApplier.apply(npc, skinNameOrUrl, plugin);
        } else {
            // best-effort sync path
            try {
                Object trait = npc.getClass().getMethod("getOrAddTrait", Class.class)
                        .invoke(npc, Class.forName("net.citizensnpcs.trait.SkinTrait"));
                trait.getClass().getMethod("setSkinName", String.class, boolean.class)
                        .invoke(trait, skinNameOrUrl, true);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public Entity getEntity() {
        try {
            return (Entity) npc.getClass().getMethod("getEntity").invoke(npc);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Integer getCitizensId() {
        return id;
    }

    /** Expose raw NPC for advanced skin ops */
    public Object rawNpc() {
        return npc;
    }
}
