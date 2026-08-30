package com.aibots.storage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit no-go zones — gatherers never mine/harvest inside a registered box,
 * regardless of default awareness or an explicit order. Same pos1/pos2 /fill-style
 * UX as {@link ChestNetwork}'s storage room registration, but this stores the box
 * bounds themselves (for true containment checks) rather than scanned member points.
 */
public final class ProtectedZones {

    private final JavaPlugin plugin;
    private final File file;
    private final List<Zone> zones = new ArrayList<>();
    private final long maxVolume;

    public static final class Zone {
        public final World world;
        public final int x1, y1, z1, x2, y2, z2;
        public final String label;

        Zone(World world, int x1, int y1, int z1, int x2, int y2, int z2, String label) {
            this.world = world;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
            this.label = label == null ? "" : label;
        }

        public boolean contains(Location loc) {
            if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(world)) {
                return false;
            }
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }

        public long volume() {
            return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        }

        public String sizeLabel() {
            return (x2 - x1 + 1) + "×" + (y2 - y1 + 1) + "×" + (z2 - z1 + 1);
        }
    }

    public static final class RegisterResult {
        public String error;
        public Zone zone;

        public boolean ok() {
            return error == null;
        }
    }

    public ProtectedZones(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "protect.yml");
        this.maxVolume = plugin.getConfig().getLong("storage.protect-max-volume",
                plugin.getConfig().getLong("storage.register-max-volume", 200_000));
    }

    public void load() {
        zones.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cs = yaml.getConfigurationSection("zones");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                ConfigurationSection z = cs.getConfigurationSection(k);
                if (z == null) {
                    continue;
                }
                World world = Bukkit.getWorld(String.valueOf(z.getString("world")));
                if (world == null) {
                    continue;
                }
                zones.add(new Zone(world, z.getInt("x1"), z.getInt("y1"), z.getInt("z1"),
                        z.getInt("x2"), z.getInt("y2"), z.getInt("z2"), z.getString("label", "")));
            }
        }
        plugin.getLogger().info("Protected zones loaded: " + zones.size() + " zone(s).");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        int i = 0;
        for (Zone z : zones) {
            ConfigurationSection s = yaml.createSection("zones." + i++);
            s.set("world", z.world.getName());
            s.set("x1", z.x1);
            s.set("y1", z.y1);
            s.set("z1", z.z1);
            s.set("x2", z.x2);
            s.set("y2", z.y2);
            s.set("z2", z.z2);
            s.set("label", z.label);
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save protect.yml: " + e.getMessage());
        }
    }

    public RegisterResult register(Location cornerA, Location cornerB, String label) {
        RegisterResult result = new RegisterResult();
        if (cornerA == null || cornerB == null || cornerA.getWorld() == null || cornerB.getWorld() == null) {
            result.error = "Invalid corners.";
            return result;
        }
        if (!cornerA.getWorld().equals(cornerB.getWorld())) {
            result.error = "Both corners must be in the same world.";
            return result;
        }
        int x1 = Math.min(cornerA.getBlockX(), cornerB.getBlockX());
        int x2 = Math.max(cornerA.getBlockX(), cornerB.getBlockX());
        int y1 = Math.min(cornerA.getBlockY(), cornerB.getBlockY());
        int y2 = Math.max(cornerA.getBlockY(), cornerB.getBlockY());
        int z1 = Math.min(cornerA.getBlockZ(), cornerB.getBlockZ());
        int z2 = Math.max(cornerA.getBlockZ(), cornerB.getBlockZ());
        long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (volume > maxVolume) {
            result.error = "Zone too large (" + volume + " blocks, max " + maxVolume + ").";
            return result;
        }
        Zone zone = new Zone(cornerA.getWorld(), x1, y1, z1, x2, y2, z2, label);
        zones.add(zone);
        save();
        result.zone = zone;
        return result;
    }

    public boolean isProtected(Location loc) {
        for (Zone z : zones) {
            if (z.contains(loc)) {
                return true;
            }
        }
        return false;
    }

    public List<Zone> list() {
        return List.copyOf(zones);
    }

    public boolean remove(int index) {
        if (index < 0 || index >= zones.size()) {
            return false;
        }
        zones.remove(index);
        save();
        return true;
    }

    public int clear() {
        int n = zones.size();
        zones.clear();
        save();
        return n;
    }
}
