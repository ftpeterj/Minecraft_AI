package com.botinterop;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * A separate trust list from the mineflayer bot's own chat-based friends
 * system (that one lives in friends.json on a different machine — there's no
 * shared file to read here). This one gates who can see/open a bot account's
 * inventory in-game (right-click, /botinv, /botarmor).
 */
public class BotFriendService {

    private final File file;
    private final Set<String> friends = new HashSet<>();
    private String owner;

    public BotFriendService(BotInteropPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "friends.yml");
        this.owner = plugin.getConfig().getString("owner", "KingOfThisHouse").toLowerCase();
        load();
    }

    public void reloadOwner(String ownerName) {
        this.owner = ownerName.toLowerCase();
    }

    public boolean isTrusted(String playerName) {
        String n = playerName.toLowerCase();
        return n.equals(owner) || friends.contains(n);
    }

    public boolean isOwner(String playerName) {
        return playerName.equalsIgnoreCase(owner);
    }

    public void add(String playerName) {
        friends.add(playerName.toLowerCase());
        save();
    }

    public void remove(String playerName) {
        friends.remove(playerName.toLowerCase());
        save();
    }

    public Set<String> list() {
        return friends;
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> saved = yaml.getStringList("friends");
        for (String name : saved) {
            friends.add(name.toLowerCase());
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("friends", List.copyOf(friends));
        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().warning("[BotInterop] Failed to save friends.yml: " + e.getMessage());
        }
    }
}
