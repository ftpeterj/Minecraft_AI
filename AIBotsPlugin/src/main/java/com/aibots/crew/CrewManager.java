package com.aibots.crew;

import com.aibots.learn.LearningService;
import com.aibots.llm.LLMContext;
import com.aibots.llm.LLMProvider;
import com.aibots.llm.LMStudioClient;
import com.aibots.llm.RolePrompts;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcService;
import com.aibots.skill.BuilderSkill;
import com.aibots.skill.CombatSkill;
import com.aibots.skill.FarmerSkill;
import com.aibots.skill.HunterSkill;
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
    private final LLMProvider llm;
    private final LearningService learning;
    private final ChestNetwork chestNetwork;
    private final ScavengeSkill scavengeSkill;
    private final CombatSkill combatSkill;
    private final HunterSkill hunterSkill;
    private final FarmerSkill farmerSkill;
    private final BuilderSkill builderSkill;
    private final CrewMessenger messenger;
    private final Map<UUID, CrewBot> botsById = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final File botsFile;
    private BukkitTask tickTask;
    private int tickCounter;

    public CrewManager(JavaPlugin plugin, NpcService npcService, LLMProvider llm) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.llm = llm;
        this.learning = new LearningService(plugin);
        this.chestNetwork = new ChestNetwork(plugin);
        this.scavengeSkill = new ScavengeSkill(plugin, npcService, chestNetwork, learning);
        this.combatSkill = new CombatSkill(plugin, npcService, learning);
        this.hunterSkill = new HunterSkill(plugin, npcService, chestNetwork, learning);
        this.farmerSkill = new FarmerSkill(plugin, npcService, chestNetwork, learning);
        this.builderSkill = new BuilderSkill(plugin, npcService, chestNetwork, learning);
        this.messenger = new CrewMessenger(
                plugin,
                id -> Optional.ofNullable(botsById.get(id)),
                this::findByName,
                this::botsOwnedBy,
                learning,
                chestNetwork
        );
        this.builderSkill.setMessenger(messenger);
        this.botsFile = new File(plugin.getDataFolder(), "bots.yml");
    }

    /** Backward-compatible ctor. */
    public CrewManager(JavaPlugin plugin, NpcService npcService, LMStudioClient llm) {
        this(plugin, npcService, (LLMProvider) llm);
    }

    public LearningService getLearning() {
        return learning;
    }

    public ChestNetwork getChestNetwork() {
        return chestNetwork;
    }

    public CrewMessenger getMessenger() {
        return messenger;
    }

    public BuilderSkill getBuilderSkill() {
        return builderSkill;
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

        // Delayed orphan sweeps: ONLY untracked ghosts — never wipe live summoned bots.
        // (Previously clear-on-load used removeAllLikelyCrewBodies here and deleted Rusty after summon.)
        for (long delay : new long[]{40L, 100L, 200L, 600L, 1200L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int n = sweepUntrackedBodies();
                if (n > 0) {
                    plugin.getLogger().info("Post-load orphan sweep removed " + n + " untracked bod(ies).");
                }
            }, delay);
        }

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

    /**
     * Remove leftover crew-like bodies that are NOT a live bot body.
     * Never removes entities currently tracked by NpcService.
     */
    public int sweepWorldOrphans() {
        return sweepUntrackedBodies();
    }

    /** Remove villager/armorstand crew ghosts not owned by a live registry bot. */
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
                // Never kill a body we are actively driving
                if (npcService.isTrackedEntity(entity)) {
                    continue;
                }
                boolean bodyType = entity instanceof org.bukkit.entity.Villager
                        || entity instanceof org.bukkit.entity.ArmorStand;
                if (!bodyType && !entity.getScoreboardTags().contains(com.aibots.npc.EntityCleanup.TAG)) {
                    continue;
                }
                String cn = com.aibots.npc.EntityCleanup.resolveName(entity);
                if (cn == null || cn.isBlank()) {
                    if (entity.getScoreboardTags().contains(com.aibots.npc.EntityCleanup.TAG)) {
                        entity.remove();
                        removed++;
                    }
                    continue;
                }
                String bare = com.aibots.npc.EntityCleanup.bareName(cn);
                boolean crewish = entity.getScoreboardTags().contains(com.aibots.npc.EntityCleanup.TAG)
                        || com.aibots.npc.EntityCleanup.looksLikeCrewName(cn);
                if (!crewish) {
                    continue;
                }
                // Keep if a live bot claims this name
                if (findByName(bare).isPresent()) {
                    continue;
                }
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Ensure the bot has a valid world body; respawn at last/home/owner if missing.
     * Fixes "talks but invisible" after orphan sweeps or chunk issues.
     */
    public NpcHandle ensureBody(CrewBot bot) {
        if (bot == null) {
            return null;
        }
        return npcService.ensureBody(bot);
    }

    /** Bring bot body to a location (e.g. owner). */
    public void bringHere(CrewBot bot, Location where) {
        NpcHandle body = ensureBody(bot);
        if (body == null || where == null) {
            return;
        }
        Location dest = com.aibots.npc.NpcLocations.findSafeFeet(
                where.getWorld(), where.getX(), where.getBlockY(), where.getZ(), where.getBlockY());
        if (dest == null) {
            dest = where.clone();
        }
        body.stopWalking();
        body.teleport(dest);
        bot.setLastLocation(dest);
        save();
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
        npcService.shutdownPhysics();
        // LLM lifecycle owned by AIBotsPlugin (router); do not close here
    }

    private void tick() {
        npcService.tickSyncLocations(botsById);
        tickCounter++;
        for (CrewBot bot : botsById.values()) {
            try {
                // Keep body alive so work + walking can happen
                if (bot.getStatus() != BotStatus.DISMISSED) {
                    ensureBody(bot);
                }
                if (bot.getTitle() == null) {
                    continue;
                }
                // Inter-bot inbox (delegation / material requests)
                messenger.processInbox(bot, this::assign);
                if (bot.getTitle().isGatherer()) {
                    scavengeSkill.tick(bot);
                } else if (bot.getTitle().isCombat()) {
                    combatSkill.tick(bot);
                } else if (bot.getTitle().isHunter()) {
                    hunterSkill.tick(bot);
                } else if (bot.getTitle().isFarmer()) {
                    farmerSkill.tick(bot);
                } else if (bot.getTitle() == BotTitle.BUILDER) {
                    builderSkill.tick(bot);
                }
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

        CrewBot bot = new CrewBot(UUID.randomUUID(), clean, title, skin, owner.getUniqueId(), plugin);
        bot.setStatus(BotStatus.IDLE);
        // Horizontal in-front spawn on the player's floor (looking down used to bury bots)
        Location spawnAt = com.aibots.npc.NpcLocations.safeSummonInFront(owner, plugin);
        if (spawnAt == null) {
            spawnAt = owner.getLocation().clone().add(0, 0.1, 0);
        }
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
     * Remove world/Citizens leftovers for a name that is no longer in bots.yml.
     * Used by /crew dismiss when the registry entry is already gone.
     *
     * @return number of entities / NPCs removed
     */
    public int dismissOrphanByName(String name) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        String clean = name.trim();
        int n = 0;
        n += com.aibots.npc.EntityCleanup.removeCrewBodiesNamed(clean);
        n += com.aibots.npc.CitizensHandle.destroyByName(clean);
        // Also match title-plate variants if bare name missed something
        if (n == 0) {
            n += com.aibots.npc.EntityCleanup.removeAllLikelyCrewBodiesMatching(clean);
        }
        if (n > 0) {
            plugin.getLogger().info("Orphan dismiss removed " + n + " for name=" + clean);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "citizens save");
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /**
     * Remove every crew bot and wipe leftover Citizens NPCs + world villager bodies.
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
        // Catch leftovers not in registry (the Rusty-after-clear-on-load case)
        n += com.aibots.npc.EntityCleanup.removeAllLikelyCrewBodies();
        n += com.aibots.npc.EntityCleanup.removeCrewBodiesNamed("Rusty");
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
        if (bot.getTitle() != null && bot.getTitle().isGatherer()) {
            // Hub at home floor — placement logic finds free adjacent floor tiles
            chestNetwork.setHub(home);
            // Place storage now so it goes next to you, not later when bot is outside
            chestNetwork.ensureStorageNear(home);
        }
        save();
        learning.save();
    }

    /**
     * Assign an order. For gather titles, surveys nearby resources and may wait for a choice
     * if a specific material is far (e.g. spruce when only oak is close).
     *
     * @return messages from the bot (distance / alternatives / choices); empty if none
     */
    public java.util.List<String> assign(CrewBot bot, String order) {
        bot.remember("Order: " + order);
        learning.observe(bot, "assign", order, true, bot.getTitle().name());
        learning.learnFromPlayerChat(bot, "owner", "Your order: " + order);

        java.util.List<String> botLines = new java.util.ArrayList<>();
        BotTitle title = bot.getTitle();
        if (title != null && title.isGatherer()) {
            Location from = null;
            NpcHandle body = bodyOf(bot);
            if (body != null && body.isValid()) {
                from = body.getLocation();
            }
            if (from == null) {
                from = bot.getHome();
            }
            var plan = scavengeSkill.planOrder(bot, order, from);
            botLines.addAll(plan.messages);
            if (plan.startWork && title == BotTitle.MINER) {
                String lower = order.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("recipe") || lower.contains("craft") || lower.contains("how")
                        || lower.contains("tool") || lower.contains("pick") || lower.contains("anvil")
                        || lower.contains("repair")) {
                    for (String line : scavengeSkill.minerTools().recipeHelpLines()) {
                        botLines.add(org.bukkit.ChatColor.GRAY + "  " + line);
                    }
                } else {
                    botLines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.GRAY
                            + ": I'll use the right pickaxe tier for the job, craft a table if needed, "
                            + "and prefer anvil repair over crafting new tools when picks wear out.");
                }
            }
            if (plan.startWork) {
                bot.setCurrentOrder(order);
                bot.setStatus(BotStatus.BUSY);
            }
        } else if (title != null && title.isCombat()) {
            bot.setCurrentOrder(order);
            bot.setStatus(BotStatus.BUSY);
            botLines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.WHITE
                    + ": Guarding — I'll fight hostiles near you/home until stopped.");
        } else if (title != null && title.isHunter()) {
            bot.setCurrentOrder(order);
            bot.setStatus(BotStatus.BUSY);
            botLines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.WHITE
                    + ": Hunting nearby animals for food. I'll fill my bag and deposit.");
        } else if (title != null && title.isFarmer()) {
            bot.setCurrentOrder(order);
            bot.setStatus(BotStatus.BUSY);
            botLines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.WHITE
                    + ": Working the fields — harvest mature crops and replant.");
        } else if (title == BotTitle.BUILDER) {
            Location from = null;
            NpcHandle body = bodyOf(bot);
            if (body != null && body.isValid()) {
                from = body.getLocation();
            }
            if (from == null) {
                from = bot.getHome();
            }
            if (from == null) {
                Player owner = Bukkit.getPlayer(bot.getOwnerId());
                if (owner != null) {
                    from = owner.getLocation();
                }
            }
            botLines.addAll(builderSkill.startJob(bot, order, from));
        } else {
            bot.setCurrentOrder(order);
            bot.setStatus(BotStatus.BUSY);
        }
        save();
        return botLines;
    }

    public void stop(CrewBot bot) {
        bot.setCurrentOrder(null);
        bot.setStatus(BotStatus.STOPPED);
        builderSkill.clear(bot);
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
        LLMContext.Complexity complexity = looksComplex(playerMessage)
                ? LLMContext.Complexity.COMPLEX
                : LLMContext.Complexity.SIMPLE;
        LLMContext.TaskType taskType = taskTypeFor(bot.getTitle());
        LLMContext ctx = LLMContext.builder()
                .botName(bot.getName())
                .botId(bot.getId())
                .title(bot.getTitle())
                .taskType(taskType)
                .complexity(complexity)
                .build();
        llm.generateResponseAsync(system, playerMessage, ctx).thenAccept(reply ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String line = ChatColor.AQUA + "[" + bot.getName() + "] " + ChatColor.WHITE + reply;
                    Bukkit.broadcastMessage(line);
                    bot.remember("Player: " + playerMessage);
                    bot.remember("Me: " + reply);
                    learning.observe(bot, "chat", "Replied to player", true, truncate(reply, 80));
                    // Detect "ask X for …" / "tell Y to …" for inter-bot messaging
                    maybeRelayToTeammate(bot, playerMessage, reply);
                    if (replyTo != null && !(replyTo instanceof Player)) {
                        replyTo.sendMessage(line);
                    }
                })
        );
    }

    private void maybeRelayToTeammate(CrewBot from, String playerMessage, String reply) {
        if (playerMessage == null) {
            return;
        }
        String lower = playerMessage.toLowerCase(Locale.ROOT);
        // "ask MinerBob to gather oak" / "tell Rusty to mine iron"
        if (!(lower.contains("ask ") || lower.contains("tell ") || lower.contains("send "))) {
            return;
        }
        for (CrewBot other : botsOwnedBy(from.getOwnerId())) {
            if (other.getId().equals(from.getId())) {
                continue;
            }
            String n = other.getName().toLowerCase(Locale.ROOT);
            if (lower.contains(n) || lower.contains(other.getTitle().display().toLowerCase(Locale.ROOT))) {
                String body = playerMessage;
                int idx = lower.indexOf(n);
                if (idx >= 0) {
                    // try to take text after the name
                    int after = idx + n.length();
                    if (after < playerMessage.length()) {
                        body = playerMessage.substring(after).replaceFirst("^(?i)\\s*(to|,)\\s*", "").trim();
                    }
                }
                if (body.isBlank()) {
                    body = playerMessage;
                }
                messenger.send(from, other, BotMessage.Kind.DELEGATE, body);
                return;
            }
        }
    }

    private static boolean looksComplex(String msg) {
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(Locale.ROOT);
        return m.length() > 120
                || m.contains("plan")
                || m.contains("design")
                || m.contains("blueprint")
                || m.contains("architecture")
                || m.contains("how should we")
                || m.contains("coordinate");
    }

    private static LLMContext.TaskType taskTypeFor(BotTitle title) {
        if (title == null) {
            return LLMContext.TaskType.CHAT;
        }
        return switch (title.kind()) {
            case BUILD -> LLMContext.TaskType.BUILD;
            case COMBAT -> LLMContext.TaskType.COMBAT;
            case GATHER, HUNT, FARM -> LLMContext.TaskType.GATHER;
        };
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
