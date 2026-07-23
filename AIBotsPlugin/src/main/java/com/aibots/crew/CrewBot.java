package com.aibots.crew;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent identity + runtime state for one crew member.
 */
public class CrewBot {

    private final UUID id;
    private String name;
    private BotTitle title;
    private String skin;
    private UUID ownerId;
    private BotStatus status;
    private Location home;
    private Location lastLocation;
    private Integer citizensNpcId;
    private String currentOrder;
    private final Deque<String> memory = new ArrayDeque<>();
    private final CrewLootHolder loot;

    public CrewBot(UUID id, String name, BotTitle title, String skin, UUID ownerId) {
        this(id, name, title, skin, ownerId, null);
    }

    public CrewBot(UUID id, String name, BotTitle title, String skin, UUID ownerId, JavaPlugin plugin) {
        this.id = id;
        this.name = name;
        this.title = title;
        this.skin = skin;
        this.ownerId = ownerId;
        this.status = BotStatus.IDLE;
        this.loot = CrewLootHolder.create(id, name, plugin);
    }

    public CrewLootHolder getLoot() {
        return loot;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BotTitle getTitle() {
        return title;
    }

    public void setTitle(BotTitle title) {
        this.title = title;
    }

    public String getSkin() {
        return skin;
    }

    public void setSkin(String skin) {
        this.skin = skin;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public BotStatus getStatus() {
        return status;
    }

    public void setStatus(BotStatus status) {
        this.status = status;
    }

    public Location getHome() {
        return home;
    }

    public void setHome(Location home) {
        this.home = home == null ? null : home.clone();
    }

    public Location getLastLocation() {
        return lastLocation;
    }

    public void setLastLocation(Location lastLocation) {
        this.lastLocation = lastLocation == null ? null : lastLocation.clone();
    }

    public Integer getCitizensNpcId() {
        return citizensNpcId;
    }

    public void setCitizensNpcId(Integer citizensNpcId) {
        this.citizensNpcId = citizensNpcId;
    }

    public String getCurrentOrder() {
        return currentOrder;
    }

    public void setCurrentOrder(String currentOrder) {
        this.currentOrder = currentOrder;
        if (currentOrder != null && !currentOrder.isBlank()) {
            this.status = BotStatus.BUSY;
        }
    }

    public void remember(String line) {
        memory.addLast(line);
        while (memory.size() > 12) {
            memory.removeFirst();
        }
    }

    public String memorySummary() {
        if (memory.isEmpty()) {
            return "(no recent memory)";
        }
        return String.join(" | ", memory);
    }

    public String nameplate() {
        return name + " [" + title.display() + "]";
    }

    public Player getOwnerPlayer() {
        return Bukkit.getPlayer(ownerId);
    }

    public void saveTo(ConfigurationSection section) {
        section.set("id", id.toString());
        section.set("name", name);
        section.set("title", title.name());
        section.set("skin", skin);
        section.set("owner", ownerId.toString());
        section.set("status", status.name());
        section.set("current-order", currentOrder);
        section.set("citizens-npc-id", citizensNpcId);
        writeLocation(section, "home", home);
        writeLocation(section, "last-location", lastLocation);
    }

    public static CrewBot loadFrom(ConfigurationSection section) {
        UUID id = UUID.fromString(Objects.requireNonNull(section.getString("id")));
        String name = section.getString("name", "Bot");
        BotTitle title = BotTitle.parse(section.getString("title", "BUILDER")).orElse(BotTitle.BUILDER);
        String skin = section.getString("skin", "Steve");
        UUID owner = UUID.fromString(Objects.requireNonNull(section.getString("owner")));
        CrewBot bot = new CrewBot(id, name, title, skin, owner);
        try {
            bot.status = BotStatus.valueOf(section.getString("status", "IDLE"));
        } catch (IllegalArgumentException e) {
            bot.status = BotStatus.IDLE;
        }
        if (bot.status == BotStatus.DISMISSED) {
            bot.status = BotStatus.IDLE;
        }
        bot.currentOrder = section.getString("current-order");
        if (section.contains("citizens-npc-id")) {
            bot.citizensNpcId = section.getInt("citizens-npc-id");
        }
        bot.home = readLocation(section, "home");
        bot.lastLocation = readLocation(section, "last-location");
        return bot;
    }

    private static void writeLocation(ConfigurationSection section, String path, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            section.set(path, null);
            return;
        }
        section.set(path + ".world", loc.getWorld().getName());
        section.set(path + ".x", loc.getX());
        section.set(path + ".y", loc.getY());
        section.set(path + ".z", loc.getZ());
        section.set(path + ".yaw", loc.getYaw());
        section.set(path + ".pitch", loc.getPitch());
    }

    private static Location readLocation(ConfigurationSection section, String path) {
        if (!section.isConfigurationSection(path)) {
            return null;
        }
        ConfigurationSection s = section.getConfigurationSection(path);
        if (s == null) {
            return null;
        }
        String worldName = s.getString("world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(
                world,
                s.getDouble("x"),
                s.getDouble("y"),
                s.getDouble("z"),
                (float) s.getDouble("yaw"),
                (float) s.getDouble("pitch")
        );
    }

    /** Helper for unit-style dumps. */
    public YamlConfiguration toYamlRoot() {
        YamlConfiguration yaml = new YamlConfiguration();
        saveTo(yaml);
        return yaml;
    }
}
