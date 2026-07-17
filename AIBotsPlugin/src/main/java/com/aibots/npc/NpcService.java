package com.aibots.npc;

import com.aibots.crew.CrewBot;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Spawns and tracks NPC bodies for crew bots (Citizens preferred, ArmorStand fallback).
 */
public class NpcService {

    private final JavaPlugin plugin;
    private final Logger log;
    private final Map<UUID, NpcHandle> handles = new ConcurrentHashMap<>();
    private final boolean citizens;

    public NpcService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.citizens = CitizensHandle.isCitizensPresent();
        if (citizens) {
            log.info("Citizens detected — using player NPC avatars.");
        } else {
            log.warning("Citizens not found — using ArmorStand fallback avatars. Install Citizens for real skins.");
        }
    }

    public boolean usingCitizens() {
        return citizens;
    }

    public NpcHandle get(UUID botId) {
        return handles.get(botId);
    }

    public NpcHandle spawnFor(CrewBot bot, Location location) {
        despawn(bot.getId());

        String plate = coloredNameplate(bot);
        NpcHandle handle = null;

        if (citizens) {
            // Bare name for Citizens registry; colored plate for display; skin separate
            handle = CitizensHandle.spawn(location, bot.getName(), plate, bot.getSkin(), plugin);
        }
        if (handle == null) {
            handle = ArmorStandHandle.spawn(location, plate, bot.getSkin());
        }

        handles.put(bot.getId(), handle);
        bot.setCitizensNpcId(handle.getCitizensId());
        bot.setLastLocation(location);
        log.info("Spawned " + bot.getName() + " via " + handle.backend()
                + " skin=" + bot.getSkin()
                + (handle.getCitizensId() != null ? " id=" + handle.getCitizensId() : ""));
        return handle;
    }

    public void respawnFromSave(CrewBot bot) {
        Location loc = bot.getLastLocation() != null ? bot.getLastLocation() : bot.getHome();
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        if (citizens && bot.getCitizensNpcId() != null) {
            CitizensHandle existing = CitizensHandle.attachExisting(bot.getCitizensNpcId());
            if (existing != null && existing.isValid()) {
                existing.setNameplate(coloredNameplate(bot));
                existing.setSkin(bot.getSkin());
                handles.put(bot.getId(), existing);
                return;
            }
        }
        spawnFor(bot, loc);
    }

    public void refreshNameplate(CrewBot bot) {
        NpcHandle handle = handles.get(bot.getId());
        if (handle != null && handle.isValid()) {
            handle.setNameplate(coloredNameplate(bot));
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
        ChatColor color = colorFor(bot);
        if (showTitle) {
            return color + bot.getName() + ChatColor.GRAY + " [" + bot.getTitle().display() + "]";
        }
        return color + bot.getName();
    }

    private ChatColor colorFor(CrewBot bot) {
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
