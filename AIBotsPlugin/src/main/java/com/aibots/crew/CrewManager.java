package com.aibots.crew;

import com.aibots.learn.LearningService;
import com.aibots.llm.LMStudioClient;
import com.aibots.llm.RolePrompts;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcService;
import com.aibots.skill.ScavengeSkill;
import com.aibots.storage.ChestNetwork;
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
    private final LearningService learning;
    private final ChestNetwork chestNetwork;
    private final ScavengeSkill scavengeSkill;
    private final Map<UUID, CrewBot> botsById = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final File botsFile;
    private BukkitTask tickTask;
    private int tickCounter;

    public CrewManager(JavaPlugin plugin, NpcService npcService, LMStudioClient llm) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.llm = llm;
        this.learning = new LearningService(plugin);
        this.chestNetwork = new ChestNetwork(plugin);
        this.scavengeSkill = new ScavengeSkill(plugin, npcService, chestNetwork, learning);
        this.botsFile = new File(plugin.getDataFolder(), "bots.yml");
    }

    public LearningService getLearning() {
        return learning;
    }

    public ChestNetwork getChestNetwork() {
        return chestNetwork;
    }

    public void start() {
        learning.load();
        chestNetwork.load();

        boolean clearOnLoad = plugin.getConfig().getBoolean("crew.clear-on-load", true);
        if (clearOnLoad) {
            clearAllCrewOnLoad();
        } else {
            load();
        }

        // Worlds finish loading entities a moment later — sweep stragglers
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int n = com.aibots.npc.EntityCleanup.removeAllLikelyCrewBodies();
            if (clearOnLoad) {
                n += com.aibots.npc.EntityCleanup.removeAllTaggedCrew();
                if (com.aibots.npc.CitizensHandle.isCitizensPresent()) {
                    n += com.aibots.npc.CitizensHandle.destroyAllCrewMarked();
                    for (int id = 0; id <= 64; id++) {
                        if (com.aibots.npc.CitizensHandle.destroyById(id)) {
                            n++;
                        }
                    }
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "citizens save");
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                // Even when persisting bots, kill orphan bodies that aren't in our registry
                n += sweepUntrackedBodies();
            }
            if (n > 0) {
                plugin.getLogger().info("Post-load crew body sweep removed " + n + " entit(y/ies).");
            }
        }, 60L);

        int interval = Math.max(10, plugin.getConfig().getInt("crew.tick-interval", 20));
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    /**
     * Wipe saved crew + any in-world crew bodies. Fresh summons after each server start.
     */
    public void clearAllCrewOnLoad() {
        plugin.getLogger().info("clear-on-load=true — wiping saved crew and world bodies...");
        // Drop registry without trying to despawn missing handles
        botsById.clear();
        nameIndex.clear();
        if (botsFile.exists() && !botsFile.delete()) {
            // overwrite with empty
            save();
        } else {
            save();
        }

        int removed = com.aibots.npc.EntityCleanup.removeAllTaggedCrew();
        removed += com.aibots.npc.EntityCleanup.removeAllLikelyCrewBodies();
        if (com.aibots.npc.CitizensHandle.isCitizensPresent()) {
            removed += com.aibots.npc.CitizensHandle.destroyAllCrewMarked();
            for (int id = 0; id <= 64; id++) {
                if (com.aibots.npc.CitizensHandle.destroyById(id)) {
                    removed++;
                }
            }
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "citizens save");
            } catch (Throwable ignored) {
            }
        }
        plugin.getLogger().info("Cleared old crew on load (entities removed≈" + removed + "). Summon with /crew summon.");
    }

    /** Remove villager/armorstand crew names that are not in botsById. */
    private int sweepUntrackedBodies() {
        int removed = 0;
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    continue;
                }
                if (!(entity instanceof org.bukkit.entity.LivingEntity)) {
                    continue;
                }
                boolean bodyType = entity instanceof org.bukkit.entity.Villager
                        || entity instanceof org.bukkit.entity.ArmorStand;
                if (!bodyType && !entity.getScoreboardTags().contains(com.aibots.npc.EntityCleanup.TAG)) {
                    continue;
                }
                String cn = entity.getCustomName();
                if (cn == null) {
                    if (entity.getScoreboardTags().contains(com.aibots.npc.EntityCleanup.TAG)) {
                        entity.remove();
                        removed++;
                    }
                    continue;
                }
                String bare = com.aibots.npc.EntityCleanup.bareName(cn);
                if (findByName(bare).isEmpty()
                        && (entity.getScoreboardTags().contains(com.aibots.npc.EntityCleanup.TAG)
                        || com.aibots.npc.EntityCleanup.looksLikeCrewName(cn))) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        boolean clearOnLoad = plugin.getConfig().getBoolean("crew.clear-on-load", true);
        if (clearOnLoad) {
            // Don't re-persist crew across restarts when clear-on-load is enabled
            npcService.despawnAll();
            botsById.clear();
            nameIndex.clear();
            if (botsFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                botsFile.delete();
            }
            save();
        } else {
            npcService.tickSyncLocations(botsById);
            save();
            npcService.despawnAll();
        }
        learning.save();
        chestNetwork.save();
        if (llm != null) {
            llm.close();
        }
    }

    private void tick() {
        npcService.tickSyncLocations(botsById);
        tickCounter++;
        for (CrewBot bot : botsById.values()) {
            try {
                if (bot.getTitle() == BotTitle.SCAVENGER) {
                    scavengeSkill.tick(bot);
                }
                // Phase 3/4: warrior/builder/farmer skills
            } catch (Exception e) {
                plugin.getLogger().warning("Tick error for " + bot.getName() + ": " + e.getMessage());
                learning.observe(bot, "error", e.getMessage(), false, bot.getTitle().name());
            }
        }
        // Persist learning periodically
        if (tickCounter % 60 == 0) {
            learning.save();
            chestNetwork.save();
        }
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

        // Prefer owner's real skin so avatars look correct (Steve/Alex often fail Mojang lookup)
        String configuredDefault = plugin.getConfig().getString("crew.default-skin", "owner");
        String skin;
        if (skinOrNull != null && !skinOrNull.isBlank()) {
            skin = skinOrNull.trim();
        } else if (configuredDefault == null || configuredDefault.isBlank()
                || configuredDefault.equalsIgnoreCase("owner")
                || configuredDefault.equalsIgnoreCase("self")) {
            skin = owner.getName();
        } else {
            skin = configuredDefault.trim();
        }

        CrewBot bot = new CrewBot(UUID.randomUUID(), clean, title, skin, owner.getUniqueId());
        bot.setStatus(BotStatus.IDLE);
        Location spawnAt = owner.getLocation().add(owner.getLocation().getDirection().normalize().multiply(2)).add(0, 0.1, 0);
        bot.setHome(owner.getLocation());
        bot.setLastLocation(spawnAt);

        botsById.put(bot.getId(), bot);
        nameIndex.put(clean.toLowerCase(Locale.ROOT), bot.getId());

        learning.ensureBrain(bot);
        learning.shareAllSharedTo(bot);
        learning.teach(bot, "My owner is " + owner.getName(), owner.getName(), false);
        learning.teach(bot, "My starting title is " + title.display(), "system", false);
        learning.observe(bot, "summon", "Summoned into the world", true, title.name());

        npcService.spawnFor(bot, spawnAt);
        // Do NOT auto-place chests or start scavenging on summon — wait for /crew assign
        save();
        learning.save();

        bot.remember("Summoned by " + owner.getName() + " as " + title.display());
        return bot;
    }

    public boolean dismiss(CrewBot bot) {
        learning.observe(bot, "dismiss", "Dismissed from world", true, null);
        learning.save();
        String botName = bot.getName();
        Integer citizensId = bot.getCitizensNpcId();
        npcService.despawn(bot.getId());
        // Triple cleanup: id, exact name, and any marked crew ghosts with that name
        if (citizensId != null) {
            com.aibots.npc.CitizensHandle.destroyById(citizensId);
        }
        com.aibots.npc.CitizensHandle.destroyByName(botName);
        // Also remove any world villager/armorstand still named like this bot
        int worldRemoved = com.aibots.npc.EntityCleanup.removeCrewBodiesNamed(botName);
        if (worldRemoved > 0) {
            plugin.getLogger().info("Dismiss also removed " + worldRemoved + " world bod(ies) for " + botName);
        }
        botsById.remove(bot.getId());
        nameIndex.remove(botName.toLowerCase(Locale.ROOT));
        bot.setCitizensNpcId(null);
        bot.setStatus(BotStatus.DISMISSED);
        save();
        // Ask Citizens to persist removals
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "citizens save");
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * Remove every crew bot and wipe leftover Citizens NPCs (by name, mark, and id sweep).
     */
    public int purgeAll() {
        int n = 0;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (CrewBot bot : List.copyOf(botsById.values())) {
            names.add(bot.getName());
            dismiss(bot);
            n++;
        }
        // Any remaining marked AIBots NPCs
        n += com.aibots.npc.CitizensHandle.destroyAllCrewMarked();
        // Name sweep for known + common leftovers
        for (String name : names) {
            n += com.aibots.npc.CitizensHandle.destroyByName(name);
        }
        n += com.aibots.npc.CitizensHandle.destroyByName("Rusty");
        n += com.aibots.npc.CitizensHandle.destroyByName("BuilderBot");
        for (int id = 0; id <= 64; id++) {
            if (com.aibots.npc.CitizensHandle.destroyById(id)) {
                n++;
            }
        }
        n += com.aibots.npc.EntityCleanup.removeAllTaggedCrew();
        for (String name : names) {
            n += com.aibots.npc.EntityCleanup.removeCrewBodiesNamed(name);
        }
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "citizens save");
        } catch (Throwable ignored) {
        }
        save();
        return n;
    }

    public void setTitle(CrewBot bot, BotTitle title) {
        BotTitle old = bot.getTitle();
        bot.setTitle(title);
        bot.remember("Title changed to " + title.display());
        learning.teach(bot, "My title changed from " + old.display() + " to " + title.display(), "system", false);
        learning.observe(bot, "retitle", old.display() + " -> " + title.display(), true, null);
        npcService.refreshNameplate(bot);
        save();
        learning.save();
    }

    public void setSkin(CrewBot bot, String skin) {
        bot.setSkin(skin);
        npcService.applySkin(bot);
        bot.remember("Skin set to " + skin);
        learning.teach(bot, "My preferred skin is " + skin, "owner", false);
        save();
    }

    public void setHome(CrewBot bot, Location home) {
        bot.setHome(home);
        bot.remember("Home set");
        learning.teach(bot,
                "Home is at " + home.getBlockX() + "," + home.getBlockY() + "," + home.getBlockZ()
                        + " in " + (home.getWorld() == null ? "?" : home.getWorld().getName()),
                "owner", true);
        if (bot.getTitle() == BotTitle.SCAVENGER) {
            chestNetwork.setHub(home.clone().add(2, 0, 0));
            chestNetwork.ensureStorageNear(home);
        }
        save();
        learning.save();
    }

    public void assign(CrewBot bot, String order) {
        bot.setCurrentOrder(order);
        bot.setStatus(BotStatus.BUSY);
        bot.remember("Order: " + order);
        learning.observe(bot, "assign", order, true, bot.getTitle().name());
        learning.learnFromPlayerChat(bot, "owner", "Your order: " + order);
        if (bot.getTitle() == BotTitle.SCAVENGER) {
            scavengeSkill.parseOrder(bot, order);
        }
        save();
    }

    public void stop(CrewBot bot) {
        bot.setCurrentOrder(null);
        bot.setStatus(BotStatus.STOPPED);
        bot.remember("Stopped");
        learning.observe(bot, "stop", "Stopped by owner", true, null);
        save();
    }

    public void talk(CrewBot bot, String playerMessage, CommandSender replyTo) {
        String playerName = replyTo instanceof Player p ? p.getName() : "someone";
        learning.learnFromPlayerChat(bot, playerName, playerMessage);

        String system = RolePrompts.systemPrompt(
                bot,
                plugin.getConfig(),
                rosterSummary(),
                learning.promptContext(bot)
        );
        llm.chatAsync(system, playerMessage).thenAccept(reply ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String line = ChatColor.AQUA + "[" + bot.getName() + "] " + ChatColor.WHITE + reply;
                    Bukkit.broadcastMessage(line);
                    bot.remember("Player: " + playerMessage);
                    bot.remember("Me: " + reply);
                    learning.observe(bot, "chat", "Replied to player", true, truncate(reply, 80));
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

    public void teach(CrewBot bot, String fact, String source, boolean share) {
        var learned = learning.teach(bot, fact, source, share);
        if (share) {
            for (CrewBot other : botsById.values()) {
                if (!other.getId().equals(bot.getId())) {
                    learning.brain(other).absorb(learned);
                    learning.observe(other, "learn_from_teammate",
                            "Learned from " + bot.getName() + ": " + learned.getText(), true, learned.getKey());
                }
            }
        }
        learning.save();
    }

    public void shareKnowledge(CrewBot from, CrewBot to, String topic) {
        learning.shareFact(from, to, topic);
        learning.save();
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
                learning.ensureBrain(bot);
                learning.shareAllSharedTo(bot);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load bot " + key + ": " + e.getMessage());
            }
        }
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

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public NpcHandle bodyOf(CrewBot bot) {
        return npcService.get(bot.getId());
    }

    public NpcService getNpcService() {
        return npcService;
    }
}
