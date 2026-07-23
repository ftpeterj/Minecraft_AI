package com.aibots.npc;

import com.aibots.crew.CrewBot;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Spawns and tracks NPC bodies.
 * Default: native villagers (real body movement). Citizens player skins are black on Paper 26.
 */
public class NpcService {

    private final JavaPlugin plugin;
    private final Logger log;
    private final Map<UUID, NpcHandle> handles = new ConcurrentHashMap<>();
    private final boolean citizensPresent;
    private final String avatarMode;
    private org.bukkit.scheduler.BukkitTask gravityTask;

    public NpcService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.citizensPresent = CitizensHandle.isCitizensPresent();
        this.avatarMode = plugin.getConfig().getString("crew.avatar-mode", "villager").toLowerCase(Locale.ROOT);
        log.info("Avatar mode: " + avatarMode
                + (citizensPresent ? " (Citizens present)" : " (no Citizens)"));
        // Physics every 2 ticks so NoAI villagers actually fall
        this.gravityTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickGravity, 2L, 2L);
    }

    /** Drop mid-air crew bodies onto solid ground. */
    private void tickGravity() {
        for (NpcHandle h : handles.values()) {
            if (h == null || !h.isValid()) {
                continue;
            }
            org.bukkit.entity.Entity e = h.getEntity();
            if (e != null) {
                NpcLocations.applyGravity(e);
            }
        }
    }

    public void shutdownPhysics() {
        if (gravityTask != null) {
            gravityTask.cancel();
            gravityTask = null;
        }
    }

    public boolean usingCitizens() {
        return mode() == Mode.CITIZENS;
    }

    private enum Mode { VILLAGER, ARMORSTAND, CITIZENS }

    private Mode mode() {
        return switch (avatarMode) {
            case "citizens", "player" -> citizensPresent ? Mode.CITIZENS : Mode.VILLAGER;
            case "armorstand", "armor_stand", "stand" -> Mode.ARMORSTAND;
            default -> Mode.VILLAGER;
        };
    }

    public NpcHandle get(UUID botId) {
        return handles.get(botId);
    }

    /** True if this entity is a currently driven crew body. */
    public boolean isTrackedEntity(org.bukkit.entity.Entity entity) {
        if (entity == null) {
            return false;
        }
        for (NpcHandle h : handles.values()) {
            if (h == null || !h.isValid()) {
                continue;
            }
            org.bukkit.entity.Entity e = h.getEntity();
            if (e != null && e.getUniqueId().equals(entity.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return a valid body, respawning if the entity was removed (orphan sweep, chunk, etc.).
     */
    public NpcHandle ensureBody(com.aibots.crew.CrewBot bot) {
        if (bot == null) {
            return null;
        }
        NpcHandle h = handles.get(bot.getId());
        if (h != null && h.isValid()) {
            return h;
        }
        Location loc = bot.getLastLocation();
        if (loc == null || loc.getWorld() == null) {
            loc = bot.getHome();
        }
        org.bukkit.entity.Player owner = bot.getOwnerPlayer();
        if (owner != null && owner.isOnline()) {
            // Prefer near owner so "missing" bots reappear with the player
            Location near = NpcLocations.safeSummonInFront(owner, plugin);
            if (near != null) {
                loc = near;
            } else {
                loc = owner.getLocation();
            }
        }
        if (loc == null || loc.getWorld() == null) {
            log.warning("Cannot respawn " + bot.getName() + " — no location.");
            return null;
        }
        log.info("Respawning missing body for " + bot.getName() + " at "
                + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
        return spawnFor(bot, loc);
    }

    /** Resolve which crew bot owns this living entity body (for right-click loot). */
    public java.util.Optional<UUID> botIdForEntity(org.bukkit.entity.Entity entity) {
        if (entity == null) {
            return java.util.Optional.empty();
        }
        for (Map.Entry<UUID, NpcHandle> e : handles.entrySet()) {
            org.bukkit.entity.Entity body = e.getValue().getEntity();
            if (body != null && body.getUniqueId().equals(entity.getUniqueId())) {
                return java.util.Optional.of(e.getKey());
            }
        }
        return java.util.Optional.empty();
    }

    public NpcHandle spawnFor(CrewBot bot, Location location) {
        despawn(bot.getId());
        if (citizensPresent) {
            int ghosts = CitizensHandle.destroyByName(bot.getName());
            if (ghosts > 0) {
                log.info("Removed " + ghosts + " orphan Citizens NPC(s) named " + bot.getName());
            }
        }
        // Kill leftover villagers/armorstands with same name (duplicate nameplates)
        int worldGhosts = EntityCleanup.removeCrewBodiesNamed(bot.getName());
        if (worldGhosts > 0) {
            log.info("Removed " + worldGhosts + " orphan world bod(ies) named " + bot.getName());
        }

        // Summon already computed a safe floor spot — don't re-snap with standOnSurface
        // (that could dig into caves if the sample Y was off). Only fix if inside solid.
        Location at = location.clone();
        if (at.getWorld() != null) {
            int fy = at.getBlockY();
            if (!NpcLocations.canStandAt(at.getWorld(), at.getBlockX(), fy, at.getBlockZ())) {
                Location fixed = NpcLocations.findSafeFeet(
                        at.getWorld(), at.getX(), fy, at.getZ(), fy);
                if (fixed != null) {
                    at = fixed;
                } else {
                    at = NpcLocations.standOnSurface(location, plugin);
                }
            }
        }
        String plate = coloredNameplate(bot);
        NpcHandle handle;

        switch (mode()) {
            case CITIZENS -> {
                handle = CitizensHandle.spawn(at, bot.getName(), plate, bot.getSkin(), plugin);
                if (handle == null) {
                    log.warning("Citizens spawn failed — falling back to villager.");
                    handle = VillagerHandle.spawn(at, plate, bot.getTitle(), plugin);
                }
            }
            case ARMORSTAND -> handle = ArmorStandHandle.spawn(
                    at, plate, bot.getSkin(),
                    org.bukkit.Color.fromRGB(0xC4A35A), plugin);
            default -> handle = VillagerHandle.spawn(at, plate, bot.getTitle(), plugin);
        }

        final Location footing = at.clone();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            NpcHandle h = handles.get(bot.getId());
            if (h != null && h.isValid()) {
                h.teleport(footing);
            }
        }, 5L);

        handles.put(bot.getId(), handle);
        bot.setCitizensNpcId(handle.getCitizensId());
        bot.setLastLocation(at);
        log.info("Spawned " + bot.getName() + " via " + handle.backend()
                + " title=" + bot.getTitle()
                + " y=" + at.getY());
        return handle;
    }

    public void respawnFromSave(CrewBot bot) {
        Location loc = bot.getLastLocation() != null ? bot.getLastLocation() : bot.getHome();
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        spawnFor(bot, loc);
    }

    public void refreshNameplate(CrewBot bot) {
        NpcHandle handle = handles.get(bot.getId());
        if (handle != null && handle.isValid()) {
            handle.setNameplate(coloredNameplate(bot));
            if (handle instanceof VillagerHandle vh) {
                vh.setProfessionForTitle(bot.getTitle());
            }
        }
    }

    public void applySkin(CrewBot bot) {
        NpcHandle handle = handles.get(bot.getId());
        if (handle != null && handle.isValid()) {
            handle.setSkin(bot.getSkin());
        }
    }

    public void despawn(UUID botId) {
        NpcHandle handle = handles.remove(botId);
        if (handle != null) {
            handle.destroy();
        }
    }

    public void despawnAll() {
        for (UUID id : handles.keySet().toArray(new UUID[0])) {
            despawn(id);
        }
    }

    public void tickSyncLocations(Map<UUID, CrewBot> bots) {
        for (Map.Entry<UUID, NpcHandle> e : handles.entrySet()) {
            NpcHandle h = e.getValue();
            if (!h.isValid()) {
                continue;
            }
            // Gravity before location sync (NoAI bodies don't fall on their own)
            org.bukkit.entity.Entity ent = h.getEntity();
            if (ent != null) {
                NpcLocations.applyGravity(ent);
            }
            Location loc = h.getLocation();
            CrewBot bot = bots.get(e.getKey());
            if (bot != null && loc != null) {
                bot.setLastLocation(loc);
            }
        }
    }

    private String coloredNameplate(CrewBot bot) {
        boolean showTitle = plugin.getConfig().getBoolean("crew.show-title-on-nameplate", true);
        ChatColor color = chatColorFor(bot);
        if (showTitle) {
            return color + bot.getName() + ChatColor.GRAY + " [" + bot.getTitle().display() + "]";
        }
        return color + bot.getName();
    }

    private ChatColor chatColorFor(CrewBot bot) {
        String cfg = plugin.getConfig().getString("titles." + bot.getTitle().name().toLowerCase() + ".color");
        if (cfg == null) {
            cfg = bot.getTitle().defaultColor();
        }
        try {
            return ChatColor.valueOf(cfg.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ChatColor.AQUA;
        }
    }
}
