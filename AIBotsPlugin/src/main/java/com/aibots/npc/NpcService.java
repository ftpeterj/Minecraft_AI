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

    public NpcService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.citizensPresent = CitizensHandle.isCitizensPresent();
        this.avatarMode = plugin.getConfig().getString("crew.avatar-mode", "villager").toLowerCase(Locale.ROOT);
        log.info("Avatar mode: " + avatarMode
                + (citizensPresent ? " (Citizens present)" : " (no Citizens)"));
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

        Location at = NpcLocations.standOnSurface(location, plugin);
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
