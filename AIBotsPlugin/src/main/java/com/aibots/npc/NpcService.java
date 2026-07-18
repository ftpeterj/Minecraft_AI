package com.aibots.npc;

import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Spawns and tracks NPC bodies.
 * Default on Paper 26: armor stand + player head (Citizens player models render solid black).
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
        // Default armorstand — reliable skins on Paper 26
        this.avatarMode = plugin.getConfig().getString("crew.avatar-mode", "armorstand").toLowerCase(Locale.ROOT);
        if (useCitizens()) {
            log.info("Avatar mode: Citizens player NPCs.");
        } else {
            log.info("Avatar mode: ArmorStand + player head (reliable skins). mode=" + avatarMode
                    + (citizensPresent ? " (Citizens installed but not used for bodies)" : ""));
        }
    }

    public boolean usingCitizens() {
        return useCitizens();
    }

    private boolean useCitizens() {
        return citizensPresent && (avatarMode.equals("citizens") || avatarMode.equals("player"));
    }

    public NpcHandle get(UUID botId) {
        return handles.get(botId);
    }

    public NpcHandle spawnFor(CrewBot bot, Location location) {
        despawn(bot.getId());
        // Always clean Citizens ghosts with this name (from older versions)
        if (citizensPresent) {
            int ghosts = CitizensHandle.destroyByName(bot.getName());
            if (ghosts > 0) {
                log.info("Removed " + ghosts + " orphan Citizens NPC(s) named " + bot.getName());
            }
        }

        Location at = NpcLocations.standOnSurface(location, plugin);
        // Armor stands sit lower; slight lift for feet
        if (!useCitizens()) {
            at = at.clone().add(0, 0.0, 0);
        }

        String plate = coloredNameplate(bot);
        NpcHandle handle = null;

        if (useCitizens()) {
            handle = CitizensHandle.spawn(at, bot.getName(), plate, bot.getSkin(), plugin);
        }
        if (handle == null) {
            handle = ArmorStandHandle.spawn(at, plate, bot.getSkin(), colorForTitle(bot.getTitle()), plugin);
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
                + " skin=" + bot.getSkin()
                + " y=" + at.getY()
                + (handle.getCitizensId() != null ? " id=" + handle.getCitizensId() : ""));
        return handle;
    }

    public void respawnFromSave(CrewBot bot) {
        Location loc = bot.getLastLocation() != null ? bot.getLastLocation() : bot.getHome();
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        // Prefer fresh armorstand/citizens spawn over stale Citizens attach
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

    private Color colorForTitle(BotTitle title) {
        return switch (title) {
            case SCAVENGER -> Color.fromRGB(0xC4A35A); // gold/tan
            case WARRIOR -> Color.fromRGB(0xB33A3A);
            case BUILDER -> Color.fromRGB(0x3A8FB7);
            case FARMER -> Color.fromRGB(0x4FAF4F);
        };
    }
}
