package com.aibots.crew;

import com.aibots.learn.LearningService;
import com.aibots.llm.LLMContext;
import com.aibots.llm.LLMProvider;
import com.aibots.llm.LMStudioClient;
import com.aibots.llm.RolePrompts;
import com.aibots.npc.IdleLiveliness;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcService;
import com.aibots.skill.BuilderCrewSkill;
import com.aibots.skill.BuilderSkill;
import com.aibots.skill.CombatCrewSkill;
import com.aibots.skill.CombatSkill;
import com.aibots.skill.FarmerCrewSkill;
import com.aibots.skill.FarmerSkill;
import com.aibots.skill.FishingCrewSkill;
import com.aibots.skill.FishingSkill;
import com.aibots.skill.GatherCrewSkill;
import com.aibots.skill.HunterCrewSkill;
import com.aibots.skill.HunterSkill;
import com.aibots.skill.ScavengeSkill;
import com.aibots.skill.SkillRegistry;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class CrewManager {

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private volatile LLMProvider llm;
    private final LearningService learning;
    private final ChestNetwork chestNetwork;
    private final ScavengeSkill scavengeSkill;
    private final BuilderSkill builderSkill;
    private final SkillRegistry skills;
    private final CrewJobBoard jobBoard;
    private final CrewMessenger messenger;
    private final RadiusService radiusService;
    private final IdleLiveliness idleLiveliness;
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
        this.radiusService = new RadiusService(plugin);
        this.scavengeSkill = new ScavengeSkill(plugin, npcService, chestNetwork, learning, radiusService);
        CombatSkill combatSkill = new CombatSkill(plugin, npcService, learning);
        HunterSkill hunterSkill = new HunterSkill(plugin, npcService, chestNetwork, learning);
        FarmerSkill farmerSkill = new FarmerSkill(plugin, npcService, chestNetwork, learning);
        FishingSkill fishingSkill = new FishingSkill(plugin, npcService, chestNetwork, learning);
        this.builderSkill = new BuilderSkill(plugin, npcService, chestNetwork, learning);
        this.skills = new SkillRegistry();
        // Registration order matters: for a given title, the first-registered skill
        // that appliesTo() it becomes the idle/auto-when-idle default (SkillRegistry
        // .forTitle()) — Gather first for GATHERER (mixed auto-scavenge), Combat first
        // for DEFENDER (auto-patrol/guard).
        this.skills.register(new GatherCrewSkill(scavengeSkill));
        this.skills.register(new CombatCrewSkill(combatSkill));
        this.skills.register(new HunterCrewSkill(hunterSkill));
        this.skills.register(new FarmerCrewSkill(farmerSkill));
        this.skills.register(new FishingCrewSkill(fishingSkill));
        this.skills.register(new BuilderCrewSkill(builderSkill));
        this.idleLiveliness = new IdleLiveliness(plugin, npcService);
        this.jobBoard = new CrewJobBoard(plugin);
        this.messenger = new CrewMessenger(
                plugin,
                id -> Optional.ofNullable(botsById.get(id)),
                this::findByName,
                this::botsOwnedBy,
                learning,
                chestNetwork
        );
        this.builderSkill.setMessenger(messenger);
        this.messenger.setJobBoard(jobBoard);
        this.botsFile = new File(plugin.getDataFolder(), "bots.yml");
    }

    /** Backward-compatible ctor. */
    public CrewManager(JavaPlugin plugin, NpcService npcService, LMStudioClient llm) {
        this(plugin, npcService, (LLMProvider) llm);
    }

    public LLMProvider getLlm() {
        return llm;
    }

    public void setLlm(LLMProvider llm) {
        this.llm = llm;
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

    public CrewJobBoard getJobBoard() {
        return jobBoard;
    }

    public RadiusService getRadiusService() {
        return radiusService;
    }

    /**
     * Immediately dump bot loot into the chest network (no walking required).
     *
     * @return items moved
     */
    public int forceDeposit(CrewBot bot) {
        if (bot == null) {
            return 0;
        }
        if (bot.getLoot().isEmpty()) {
            return 0;
        }
        // Ensure network exists if home known
        if (chestNetwork.getChests().isEmpty() && bot.getHome() != null) {
            chestNetwork.ensureStorageNear(bot.getHome());
        }
        int before = bot.getLoot().totalItems();
        int moved = chestNetwork.depositAll(bot.getLoot().getInventory());
        // Retry once with expand if leftovers
        if (!bot.getLoot().isEmpty()) {
            Location anchor = chestNetwork.nearestChest(
                    bot.getHome() != null ? bot.getHome() : bot.getLastLocation());
            if (anchor != null || chestNetwork.getHub() != null) {
                chestNetwork.expandChest(anchor != null ? anchor : chestNetwork.getHub());
                moved += chestNetwork.depositAll(bot.getLoot().getInventory());
            }
        }
        int after = bot.getLoot().totalItems();
        int real = Math.max(moved, before - after);
        if (real > 0) {
            bot.remember("Force-deposited " + real + " items to storage network");
            learning.observe(bot, "deposit", "Force-deposited " + real + " items", true, null);
            save();
            chestNetwork.save();
        }
        return real;
    }

    public SkillRegistry getSkills() {
        return skills;
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
        idleLiveliness.tick(botsById);
        tickCounter++;
        if (tickCounter % 30 == 0) {
            jobBoard.reclaimTimedOut();
        }
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
                // Idle bots claim open jobs from the board
                maybeClaimJob(bot);
                // Complete job when bot returns to IDLE after work
                maybeCompleteJob(bot);
                skills.tick(bot);
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

    private void maybeClaimJob(CrewBot bot) {
        if (bot.getStatus() != BotStatus.IDLE && bot.getStatus() != BotStatus.WAITING_HELP) {
            return;
        }
        if (bot.getCurrentOrder() != null && !bot.getCurrentOrder().isBlank()) {
            return;
        }
        Optional<CrewJob> claimed = jobBoard.tryClaim(bot);
        if (claimed.isEmpty()) {
            return;
        }
        CrewJob job = claimed.get();
        List<String> lines = assign(bot, job.description());
        Player owner = Bukkit.getPlayer(bot.getOwnerId());
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(ChatColor.WHITE + "<" + bot.getName() + "> I'll take that — " + job.description());
            for (String line : lines) {
                owner.sendMessage(line);
            }
        }
        // Release only if the skill did not take the order at all
        if (bot.getCurrentOrder() == null || bot.getCurrentOrder().isBlank()) {
            jobBoard.releaseBot(bot.getId());
        }
    }

    private void maybeCompleteJob(CrewBot bot) {
        Optional<CrewJob> active = jobBoard.activeFor(bot.getId());
        if (active.isEmpty()) {
            return;
        }
        // Job done when bot clears order and is idle after work
        if (bot.getStatus() == BotStatus.IDLE
                && (bot.getCurrentOrder() == null || bot.getCurrentOrder().isBlank())) {
            jobBoard.completeForBot(bot.getId(), "idle");
            learning.observe(bot, "job_done", active.get().description(), true, active.get().shortId());
        } else if (bot.getStatus() == BotStatus.STOPPED || bot.getStatus() == BotStatus.DISMISSED) {
            jobBoard.releaseBot(bot.getId());
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
        Location spawnAt = com.aibots.npc.NpcLocations.safeSummonInFront(owner, plugin);
        if (spawnAt == null) {
            spawnAt = owner.getLocation().clone().add(0, 0.1, 0);
        }
        return summonAt(owner.getUniqueId(), owner.getName(), owner.getLocation(), spawnAt,
                name, title, skinOrNull);
    }

    /**
     * Summon at an explicit location (console / automation friendly).
     */
    /**
     * Pick a skin from {@code crew.skin-pool} not already worn by a currently active bot,
     * so freshly summoned crew look distinct from each other and from the owner.
     * Falls back to a random pool entry once the pool is exhausted, and to "Steve" if
     * the pool is empty.
     */
    private String pickPoolSkin() {
        List<String> pool = plugin.getConfig().getStringList("crew.skin-pool");
        if (pool.isEmpty()) {
            return "Steve";
        }
        Set<String> used = botsById.values().stream()
                .map(CrewBot::getSkin)
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (String candidate : pool) {
            if (!used.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return pool.get(new Random().nextInt(pool.size()));
    }

    public CrewBot summonAt(UUID ownerId, String ownerName, Location home, Location spawnAt,
                            String name, BotTitle title, String skinOrNull) {
        String clean = sanitizeName(name);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Invalid name.");
        }
        if (findByName(clean).isPresent()) {
            throw new IllegalArgumentException("A bot named '" + clean + "' already exists.");
        }
        if (ownerId == null) {
            ownerId = new UUID(0L, 1L);
        }
        if (ownerName == null || ownerName.isBlank()) {
            ownerName = "Console";
        }
        int max = plugin.getConfig().getInt("crew.max-bots-per-player", 6);
        if (botsOwnedBy(ownerId).size() >= max) {
            throw new IllegalArgumentException("Owner already has " + max + " bots (max).");
        }

        String configuredDefault = plugin.getConfig().getString("crew.default-skin", "owner");
        String skin;
        if (skinOrNull != null && !skinOrNull.isBlank()) {
            skin = skinOrNull.trim();
        } else if (configuredDefault == null || configuredDefault.isBlank()
                || configuredDefault.equalsIgnoreCase("owner")
                || configuredDefault.equalsIgnoreCase("self")) {
            skin = ownerName;
        } else if (configuredDefault.equalsIgnoreCase("pool")) {
            skin = pickPoolSkin();
        } else {
            skin = configuredDefault.trim();
        }

        if (spawnAt == null || spawnAt.getWorld() == null) {
            throw new IllegalArgumentException("Invalid spawn location.");
        }
        if (home == null) {
            home = spawnAt.clone();
        }

        CrewBot bot = new CrewBot(UUID.randomUUID(), clean, title, skin, ownerId, plugin);
        bot.setStatus(BotStatus.IDLE);
        bot.setHome(home);
        bot.setLastLocation(spawnAt);

        botsById.put(bot.getId(), bot);
        nameIndex.put(clean.toLowerCase(Locale.ROOT), bot.getId());

        learning.ensureBrain(bot);
        learning.shareAllSharedTo(bot);
        learning.teach(bot, "My owner is " + ownerName, ownerName, false);
        learning.teach(bot, "My starting title is " + title.display(), "system", false);
        learning.observe(bot, "summon", "Summoned into the world", true, title.name());

        npcService.spawnFor(bot, spawnAt);
        save();
        learning.save();

        bot.remember("Summoned by " + ownerName + " as " + title.display());
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
     * Assign an order via {@link SkillRegistry}. Gather titles may survey and wait for a choice.
     *
     * @return messages from the bot (distance / alternatives / choices); empty if none
     */
    public java.util.List<String> assign(CrewBot bot, String order) {
        bot.remember("Order: " + order);
        learning.observe(bot, "assign", order, true, bot.getTitle() == null ? null : bot.getTitle().name());
        learning.learnFromPlayerChat(bot, "owner", "Your order: " + order);

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

        java.util.List<String> botLines = new java.util.ArrayList<>(skills.accept(bot, order, from));
        if (botLines.isEmpty() && bot.getStatus() != BotStatus.BUSY) {
            // No skill handled it — still mark busy so status reflects the order
            bot.setCurrentOrder(order);
            bot.setStatus(BotStatus.BUSY);
            botLines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.GRAY
                    + ": Order noted (" + (bot.getTitle() == null ? "?" : bot.getTitle().display()) + ").");
        }
        save();
        return botLines;
    }

    /**
     * Post a job on the crew board (idle matching bots will claim on tick).
     */
    public CrewJob postJob(Player owner, BotTitle preferredTitle, String description, int priority) {
        return jobBoard.post(owner.getUniqueId(), null, preferredTitle, description, priority);
    }

    public CrewJob postJobFromBot(CrewBot from, BotTitle preferredTitle, String description, int priority) {
        CrewJob job = jobBoard.post(from.getOwnerId(), from.getId(), preferredTitle, description, priority);
        learning.observe(from, "job_post", description, true,
                preferredTitle == null ? "any" : preferredTitle.name());
        return job;
    }

    public void stop(CrewBot bot) {
        bot.setCurrentOrder(null);
        bot.setStatus(BotStatus.STOPPED);
        skills.stop(bot);
        jobBoard.releaseBot(bot.getId());
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
        LLMContext.TaskType taskType = taskTypeFor(bot);
        LLMContext ctx = LLMContext.builder()
                .botName(bot.getName())
                .botId(bot.getId())
                .title(bot.getTitle())
                .taskType(taskType)
                .complexity(complexity)
                .build();
        llm.generateResponseAsync(system, playerMessage, ctx).thenAccept(reply ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    NpcHandle body = npcService.get(bot.getId());
                    if (body != null && replyTo instanceof Player speaker) {
                        body.lookAt(speaker.getEyeLocation());
                    }
                    String line = ChatColor.WHITE + "<" + bot.getName() + "> " + reply;
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

    /**
     * A title can now cover several skills at once (Defender = build+hunt+combat),
     * so this classifies by what the bot is actually doing (current order) rather
     * than the title alone.
     */
    private static LLMContext.TaskType taskTypeFor(CrewBot bot) {
        BotTitle title = bot.getTitle();
        if (title == null) {
            return LLMContext.TaskType.CHAT;
        }
        if (title == BotTitle.DEFENDER) {
            String order = bot.getCurrentOrder();
            if (BuilderSkill.looksLikeBuild(order)) {
                return LLMContext.TaskType.BUILD;
            }
            return LLMContext.TaskType.COMBAT;
        }
        return LLMContext.TaskType.GATHER;
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
