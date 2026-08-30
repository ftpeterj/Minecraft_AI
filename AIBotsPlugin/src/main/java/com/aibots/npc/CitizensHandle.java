package com.aibots.npc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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
    private Location lastWalkGoal;
    private long lastWalkIssuedMs;
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

            // Tag so purge/dismiss can always find us even if IDs drift
            markAsCrew(npc);

            Method spawn = npc.getClass().getMethod("spawn", Location.class);
            spawn.invoke(npc, loc);

            Method getId = npc.getClass().getMethod("getId");
            Integer id = (Integer) getId.invoke(npc);
            CitizensHandle handle = new CitizensHandle(npc, id, plugin);
            handle.displayPlate = displayPlate;

            showInTabList(npc, regName);
            applyHologram(npc, regName, displayPlate);
            SkinApplier.apply(npc, skin, plugin);

            // Re-assert plain name + skin after the entity is fully spawned (Paper 26)
            if (plugin != null) {
                for (long delay : new long[]{15L, 40L, 80L}) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        try {
                            setName.invoke(npc, regName);
                            applyHologram(npc, regName, displayPlate);
                            showInTabList(npc, regName);
                            SkinApplier.apply(npc, skin, plugin);
                        } catch (Throwable ignored) {
                        }
                    }, delay);
                }
            }

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

    /** Keep PLAYER NPCs on the tab list like a real teammate. */
    private static void showInTabList(Object npc, String listName) {
        try {
            Object data = npc.getClass().getMethod("data").invoke(npc);
            setNpcData(data, "remove-from-playerlist", false);
            setNpcData(data, "player-list", true);
            try {
                Class<?> meta = Class.forName("net.citizensnpcs.api.npc.NPC$Metadata");
                for (Object constant : meta.getEnumConstants()) {
                    String n = String.valueOf(constant);
                    if (n.contains("REMOVE_FROM_PLAYERLIST") || n.contains("PLAYERLIST")) {
                        boolean hide = n.contains("REMOVE");
                        try {
                            data.getClass().getMethod("setPersistent", meta, Object.class)
                                    .invoke(data, constant, hide ? Boolean.FALSE : Boolean.TRUE);
                        } catch (NoSuchMethodException e) {
                            data.getClass().getMethod("set", meta, Object.class)
                                    .invoke(data, constant, hide ? Boolean.FALSE : Boolean.TRUE);
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }
        } catch (Throwable ignored) {
        }
        try {
            Entity entity = (Entity) npc.getClass().getMethod("getEntity").invoke(npc);
            if (entity instanceof Player p) {
                p.setPlayerListName(listName);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setNpcData(Object data, String key, Object value) {
        try {
            data.getClass().getMethod("setPersistent", String.class, Object.class).invoke(data, key, value);
            return;
        } catch (Throwable ignored) {
        }
        try {
            data.getClass().getMethod("set", String.class, Object.class).invoke(data, key, value);
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
            // Despawn entity first (API varies by Citizens build)
            try {
                Class<?> reason = Class.forName("net.citizensnpcs.api.event.DespawnReason");
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object pluginReason = Enum.valueOf((Class<Enum>) reason.asSubclass(Enum.class), "PLUGIN");
                Method despawn = npc.getClass().getMethod("despawn", reason);
                despawn.invoke(npc, pluginReason);
            } catch (Throwable ignored) {
                tryInvoke(npc, "despawn", new Class[]{});
            }
            // destroy() removes from world + registry when possible
            try {
                npc.getClass().getMethod("destroy").invoke(npc);
            } catch (NoSuchMethodException e) {
                // fallback: deregister
                Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
                Object registry = api.getMethod("getNPCRegistry").invoke(null);
                registry.getClass().getMethod("deregister", npc.getClass().getInterfaces().length > 0
                        ? Class.forName("net.citizensnpcs.api.npc.NPC")
                        : npc.getClass()).invoke(registry, npc);
            }
            // Always try registry.deregister as belt-and-suspenders
            try {
                Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
                Object registry = api.getMethod("getNPCRegistry").invoke(null);
                Class<?> npcIface = Class.forName("net.citizensnpcs.api.npc.NPC");
                registry.getClass().getMethod("deregister", npcIface).invoke(registry, npc);
            } catch (Throwable ignored) {
            }
            LOG.info("[AIBots] Destroyed Citizens NPC id=" + id);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[AIBots] Failed to destroy Citizens NPC: " + t.getMessage(), t);
        }
    }

    /** Force-remove a Citizens NPC by numeric id (orphans after failed dismiss). */
    public static boolean destroyById(int npcId) {
        try {
            Object registry = registry();
            Object npc = registry.getClass().getMethod("getById", int.class).invoke(registry, npcId);
            if (npc == null) {
                return false;
            }
            forceDestroyNpc(registry, npc);
            LOG.info("[AIBots] Force-destroyed Citizens NPC id=" + npcId);
            return true;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[AIBots] destroyById(" + npcId + ") failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Remove every Citizens NPC whose bare name matches (case-insensitive).
     * Handles ghosts left when IDs drift after failed dismiss / double-summon.
     */
    public static int destroyByName(String bareName) {
        if (bareName == null || bareName.isBlank()) {
            return 0;
        }
        String want = sanitizeName(bareName).toLowerCase();
        int removed = 0;
        try {
            Object registry = registry();
            Iterable<?> all = (Iterable<?>) registry.getClass().getMethod("sorted").invoke(registry);
            java.util.List<Object> snapshot = new java.util.ArrayList<>();
            for (Object n : all) {
                snapshot.add(n);
            }
            for (Object n : snapshot) {
                try {
                    String nName = String.valueOf(n.getClass().getMethod("getName").invoke(n));
                    String bare = sanitizeName(nName).toLowerCase();
                    boolean marked = hasMeta(n, "aibots-crew");
                    if (bare.equals(want) || marked && bare.contains(want)) {
                        Integer id = (Integer) n.getClass().getMethod("getId").invoke(n);
                        forceDestroyNpc(registry, n);
                        LOG.info("[AIBots] Destroyed Citizens NPC by name match '" + nName + "' id=" + id);
                        removed++;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[AIBots] destroyByName failed: " + t.getMessage());
        }
        return removed;
    }

    /** Remove all NPCs tagged as AIBots crew (metadata aibots-crew=true). */
    public static int destroyAllCrewMarked() {
        int removed = 0;
        try {
            Object registry = registry();
            Iterable<?> all = (Iterable<?>) registry.getClass().getMethod("sorted").invoke(registry);
            java.util.List<Object> snapshot = new java.util.ArrayList<>();
            for (Object n : all) {
                snapshot.add(n);
            }
            for (Object n : snapshot) {
                if (hasMeta(n, "aibots-crew")) {
                    try {
                        Integer id = (Integer) n.getClass().getMethod("getId").invoke(n);
                        forceDestroyNpc(registry, n);
                        LOG.info("[AIBots] Destroyed marked crew NPC id=" + id);
                        removed++;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[AIBots] destroyAllCrewMarked failed: " + t.getMessage());
        }
        return removed;
    }

    public static void markAsCrew(Object npc) {
        if (npc == null) {
            return;
        }
        try {
            Object data = npc.getClass().getMethod("data").invoke(npc);
            try {
                data.getClass().getMethod("setPersistent", String.class, Object.class)
                        .invoke(data, "aibots-crew", true);
            } catch (NoSuchMethodException e) {
                data.getClass().getMethod("set", String.class, Object.class)
                        .invoke(data, "aibots-crew", true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasMeta(Object npc, String key) {
        try {
            Object data = npc.getClass().getMethod("data").invoke(npc);
            Object val = data.getClass().getMethod("get", String.class).invoke(data, key);
            if (val == null) {
                try {
                    val = data.getClass().getMethod("get", Object.class).invoke(data, key);
                } catch (Throwable ignored) {
                }
            }
            return Boolean.TRUE.equals(val) || "true".equalsIgnoreCase(String.valueOf(val));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object registry() throws Exception {
        Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
        return api.getMethod("getNPCRegistry").invoke(null);
    }

    private static void forceDestroyNpc(Object registry, Object npc) throws Exception {
        try {
            Class<?> reason = Class.forName("net.citizensnpcs.api.event.DespawnReason");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object pluginReason = Enum.valueOf((Class<Enum>) reason.asSubclass(Enum.class), "PLUGIN");
            npc.getClass().getMethod("despawn", reason).invoke(npc, pluginReason);
        } catch (Throwable ignored) {
            tryInvoke(npc, "despawn", new Class[]{});
        }
        try {
            npc.getClass().getMethod("destroy").invoke(npc);
        } catch (Throwable t) {
            Class<?> npcIface = Class.forName("net.citizensnpcs.api.npc.NPC");
            registry.getClass().getMethod("deregister", npcIface).invoke(registry, npc);
        }
        try {
            Class<?> npcIface = Class.forName("net.citizensnpcs.api.npc.NPC");
            registry.getClass().getMethod("deregister", npcIface).invoke(registry, npc);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean walkTo(Location target, double speed) {
        if (target == null || !isValid()) {
            return false;
        }
        Location dest = NpcLocations.findDryStandNear(target, 3);
        if (dest == null) {
            dest = target.clone();
            dest.setX(dest.getBlockX() + 0.5);
            dest.setZ(dest.getBlockZ() + 0.5);
        }
        long now = System.currentTimeMillis();
        boolean sameGoal = lastWalkGoal != null
                && lastWalkGoal.getWorld() != null
                && lastWalkGoal.getWorld().equals(dest.getWorld())
                && lastWalkGoal.distanceSquared(dest) < 2.25;
        if (sameGoal && isWalking() && (now - lastWalkIssuedMs) < 4000L) {
            return true;
        }
        float spd = (float) Math.max(0.6, Math.min(speed <= 0 ? 1.0 : speed, 1.35));
        Location from = getLocation();
        if (from != null && from.getWorld() != null && dest.getWorld() != null
                && from.getWorld().equals(dest.getWorld())
                && from.distanceSquared(dest) > 16 * 16) {
            spd = Math.min(spd * 1.18f, 1.42f);
        }
        try {
            Object nav = npc.getClass().getMethod("getNavigator").invoke(npc);
            try {
                Object params = nav.getClass().getMethod("getLocalParameters").invoke(nav);
                applyNavigatorSpeed(params, spd);
            } catch (Throwable ignored) {
            }
            nav.getClass().getMethod("setTarget", Location.class).invoke(nav, dest);
            lastWalkGoal = dest.clone();
            lastWalkIssuedMs = now;
            return true;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "[AIBots] Citizens navigator failed: " + t.getMessage());
            return false;
        }
    }

    private static void applyNavigatorSpeed(Object params, float spd) {
        for (String name : new String[]{"speedModifier", "speed", "baseSpeed"}) {
            try {
                params.getClass().getMethod(name, float.class).invoke(params, spd);
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
            try {
                params.getClass().getMethod(name, double.class).invoke(params, (double) spd);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void stopWalking() {
        lastWalkGoal = null;
        try {
            Object nav = npc.getClass().getMethod("getNavigator").invoke(npc);
            nav.getClass().getMethod("cancelNavigation").invoke(nav);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isWalking() {
        try {
            Object nav = npc.getClass().getMethod("getNavigator").invoke(npc);
            Object navigating = nav.getClass().getMethod("isNavigating").invoke(nav);
            return Boolean.TRUE.equals(navigating);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void equipMainHand(ItemStack item) {
        Entity entity = getEntity();
        if (entity instanceof LivingEntity living && item != null) {
            try {
                var eq = living.getEquipment();
                if (eq != null) {
                    eq.setItemInMainHand(item);
                    eq.setItemInMainHandDropChance(0f);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void lookAt(Location loc) {
        if (loc == null) {
            return;
        }
        Entity entity = getEntity();
        if (entity == null) {
            return;
        }
        if (entity instanceof org.bukkit.entity.Mob mob) {
            try {
                mob.lookAt(loc);
                return;
            } catch (Throwable ignored) {
            }
        }
        // Player-type NPC bodies: turn to face without a full position teleport.
        // entity.teleport() sends a full Position+Look packet and resets client-side
        // interpolation every call; calling it repeatedly (e.g. idle look-at-player
        // every ~10s) fights Citizens' own network/interpolation state for its fake
        // players and visibly warps/stretches the model. setRotation only sends the
        // rotation, matching a real player just turning their head/body in place.
        Location here = entity.getLocation();
        Location facing = here.clone();
        facing.setDirection(loc.clone().toVector().subtract(here.toVector()));
        try {
            entity.setRotation(facing.getYaw(), facing.getPitch());
        } catch (Throwable t) {
            entity.teleport(facing);
        }
    }

    @Override
    public void swingMainHand() {
        Entity entity = getEntity();
        if (entity instanceof LivingEntity living) {
            try {
                living.swingMainHand();
            } catch (Throwable ignored) {
            }
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
