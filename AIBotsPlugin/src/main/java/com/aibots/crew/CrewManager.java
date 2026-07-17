package com.aibots.crew;

import com.aibots.llm.LMStudioClient;
import com.aibots.llm.RolePrompts;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class CrewManager {

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private final LMStudioClient llm;
    private final Map<UUID, CrewBot> botsById = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final File botsFile;
    private BukkitTask tickTask;

    public CrewManager(JavaPlugin plugin, NpcService npcService, LMStudioClient llm) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.llm = llm;
        this.botsFile = new File(plugin.getDataFolder(), "bots.yml");
    }

    public void start() {
        load();
        int interval = Math.max(10, plugin.getConfig().getInt("crew.tick-interval", 20));
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        // Snapshot locations then despawn visuals; keep save data
        npcService.tickSyncLocations(botsById);
        save();
        npcService.despawnAll();
        if (llm != null) {
            llm.close();
        }
    }

    private void tick() {
        npcService.tickSyncLocations(botsById);
        // Phase 1: no role skills yet — reserved for scavenger/warrior/builder/farmer loops
    }

    public Collection<CrewBot> allBots() {
        return List.copyOf(botsById.values());
    }

    public Optional<CrewBot> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        UUID id = nameIndex.get(name.toLowerCase(Locale.ROOT));
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(botsById.get(id));
    }

    public List<CrewBot> botsOwnedBy(UUID playerId) {
        return botsById.values().stream()
                .filter(b -> b.getOwnerId().equals(playerId))
                .collect(Collectors.toList());
    }

    public String rosterSummary() {
        return botsById.values().stream()
                .map(b -> b.getName() + "[" + b.getTitle().display() + "]")
                .collect(Collectors.joining(", "));
    }

    public CrewBot summon(Player owner, String name, BotTitle title, String skinOrNull) {
        String clean = sanitizeName(name);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Invalid name.");
        }
        if (findByName(clean).isPresent()) {
            throw new IllegalArgumentException("A bot named '" + clean + "' already exists.");
        }
        int max = plugin.getConfig().getInt("crew.max-bots-per-player", 6);
        if (botsOwnedBy(owner.getUniqueId()).size() >= max) {
            throw new IllegalArgumentException("You already have " + max + " bots (max).");
        }

        String skin = (skinOrNull == null || skinOrNull.isBlank())
                ? plugin.getConfig().getString("crew.default-skin", "Steve")
                : skinOrNull.trim();

        CrewBot bot = new CrewBot(UUID.randomUUID(), clean, title, skin, owner.getUniqueId());
        Location spawnAt = owner.getLocation().add(owner.getLocation().getDirection().normalize().multiply(2)).add(0, 0.1, 0);
        bot.setHome(owner.getLocation());
        bot.setLastLocation(spawnAt);

        botsById.put(bot.getId(), bot);
        nameIndex.put(clean.toLowerCase(Locale.ROOT), bot.getId());

        npcService.spawnFor(bot, spawnAt);
        save();

        bot.remember("Summoned by " + owner.getName() + " as " + title.display());
        return bot;
    }

    public boolean dismiss(CrewBot bot) {
        npcService.despawn(bot.getId());
        botsById.remove(bot.getId());
        nameIndex.remove(bot.getName().toLowerCase(Locale.ROOT));
        bot.setStatus(BotStatus.DISMISSED);
        save();
        return true;
    }

    public void setTitle(CrewBot bot, BotTitle title) {
        bot.setTitle(title);
        bot.remember("Title changed to " + title.display());
        npcService.refreshNameplate(bot);
        save();
    }

    public void setSkin(CrewBot bot, String skin) {
        bot.setSkin(skin);
        npcService.applySkin(bot);
        bot.remember("Skin set to " + skin);
        save();
    }

    public void setHome(CrewBot bot, Location home) {
        bot.setHome(home);
        bot.remember("Home set");
        save();
    }

    public void assign(CrewBot bot, String order) {
        bot.setCurrentOrder(order);
        bot.setStatus(BotStatus.BUSY);
        bot.remember("Order: " + order);
        // Phase 1 stub: acknowledge only; real skills in later phases
        save();
    }

    public void stop(CrewBot bot) {
        bot.setCurrentOrder(null);
        bot.setStatus(BotStatus.STOPPED);
        bot.remember("Stopped");
        save();
    }

    public void talk(CrewBot bot, String playerMessage, CommandSender replyTo) {
        String system = RolePrompts.systemPrompt(bot, plugin.getConfig(), rosterSummary());
        llm.chatAsync(system, playerMessage).thenAccept(reply ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String line = ChatColor.AQUA + "[" + bot.getName() + "] " + ChatColor.WHITE + reply;
                    Bukkit.broadcastMessage(line);
                    bot.remember("Player: " + playerMessage);
                    bot.remember("Me: " + reply);
                    if (replyTo != null && !(replyTo instanceof Player)) {
                        replyTo.sendMessage(line);
                    }
                })
        );
    }

    public void broadcastToOwned(Player owner, String message) {
        for (CrewBot bot : botsOwnedBy(owner.getUniqueId())) {
            talk(bot, message, owner);
        }
    }

    public Optional<CrewBot> matchMention(String chatMessage) {
        String lower = chatMessage.toLowerCase(Locale.ROOT);
        CrewBot best = null;
        int bestLen = 0;
        for (CrewBot bot : botsById.values()) {
            String n = bot.getName().toLowerCase(Locale.ROOT);
            if (lower.contains(n) && n.length() > bestLen) {
                best = bot;
                bestLen = n.length();
            }
        }
        return Optional.ofNullable(best);
    }

    public void load() {
        botsById.clear();
        nameIndex.clear();
        if (!botsFile.exists()) {
            return;
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(botsFile);
        ConfigurationSection root = yaml.getConfigurationSection("bots");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            try {
                CrewBot bot = CrewBot.loadFrom(sec);
                botsById.put(bot.getId(), bot);
                nameIndex.put(bot.getName().toLowerCase(Locale.ROOT), bot.getId());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load bot " + key + ": " + e.getMessage());
            }
        }
        // Respawn after worlds load — slight delay
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (CrewBot bot : botsById.values()) {
                try {
                    npcService.respawnFromSave(bot);
                } catch (Exception e) {
                    plugin.getLogger().warning("Respawn failed for " + bot.getName() + ": " + e.getMessage());
                }
            }
            plugin.getLogger().info("Respawned " + botsById.size() + " crew bot(s).");
        }, 40L);
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        int i = 0;
        for (CrewBot bot : botsById.values()) {
            ConfigurationSection sec = yaml.createSection("bots." + i++);
            bot.saveTo(sec);
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create data folder");
            }
            yaml.save(botsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save bots.yml: " + e.getMessage());
        }
    }

    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9_\\-]", "");
        if (cleaned.length() > 16) {
            cleaned = cleaned.substring(0, 16);
        }
        return cleaned;
    }

    public NpcHandle bodyOf(CrewBot bot) {
        return npcService.get(bot.getId());
    }

    public NpcService getNpcService() {
        return npcService;
    }
}
