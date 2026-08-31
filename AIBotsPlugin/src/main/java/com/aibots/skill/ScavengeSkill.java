package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.CrewBot;
import com.aibots.crew.RadiusService;
import com.aibots.learn.LearningService;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcLocations;
import com.aibots.npc.NpcService;
import com.aibots.npc.VillagerHandle;
import com.aibots.storage.ChestNetwork;
import com.aibots.storage.ProtectedZones;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gather engine for the Gatherer title — ore, wood, and general resources
 * ({@link GatherFocus}).
 */
public class ScavengeSkill {

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private final ChestNetwork chests;
    private final LearningService learning;
    private final RadiusService radiusService;
    private final ProtectedZones protectedZones;
    private final MinerTools minerTools;
    private final RailHaulHelper railHaul;
    private final TunnelDigger tunnelDigger;
    private final Map<UUID, Material> focusMaterial = new ConcurrentHashMap<>();
    private final Map<UUID, OrderFocus> orderFocus = new ConcurrentHashMap<>();
    /** Sticky nav + stuck detection per bot */
    private final Map<UUID, Nav> navByBot = new ConcurrentHashMap<>();
    /** Recently failed targets (can't path) — skip for a while */
    private final Map<UUID, Set<String>> skipBlocks = new ConcurrentHashMap<>();

    private static final class Nav {
        Location approach;      // feet position to walk to
        String targetKey;       // block we're after
        Location lastPos;
        int stuckTicks;
        int repathCooldown;
        boolean announcedReturn;
    }

    public ScavengeSkill(JavaPlugin plugin, NpcService npcService, ChestNetwork chests, LearningService learning) {
        this(plugin, npcService, chests, learning, new RadiusService(plugin), null);
    }

    public ScavengeSkill(JavaPlugin plugin, NpcService npcService, ChestNetwork chests,
                         LearningService learning, RadiusService radiusService, ProtectedZones protectedZones) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.chests = chests;
        this.learning = learning;
        this.radiusService = radiusService != null ? radiusService : new RadiusService(plugin);
        this.protectedZones = protectedZones;
        this.minerTools = new MinerTools(plugin, chests, learning);
        this.railHaul = new RailHaulHelper(plugin, chests);
        this.tunnelDigger = new TunnelDigger(plugin);
    }

    public MinerTools minerTools() {
        return minerTools;
    }

    /**
     * Parse order, survey nearby resources, optionally start work or wait for player choice.
     */
    public OrderPlanner.PlanResult planOrder(CrewBot bot, String order, Location origin) {
        if (order == null || order.isBlank()) {
            focusMaterial.remove(bot.getId());
            orderFocus.remove(bot.getId());
            return new OrderPlanner.PlanResult(
                    new OrderFocus(OrderFocus.Category.GENERIC, null, false, order, false),
                    false, List.of());
        }
        OrderFocus focus = OrderPlanner.parse(order);
        Location from = origin;
        if (from == null) {
            NpcHandle body = npcService.get(bot.getId());
            from = body != null && body.isValid() ? body.getLocation() : bot.getHome();
        }
        OrderPlanner.PlanResult result = OrderPlanner.plan(plugin, bot, focus, from);

        skipBlocks.remove(bot.getId());
        navByBot.remove(bot.getId());

        if (!result.startWork) {
            // Vague order — surveyed and asked what to gather instead of guessing.
            // Stay idle (not busy-doing-nothing) so idle-liveliness keeps the bot
            // visibly alive while it waits for a real order, same as if it had never
            // been given one. No order is recorded, so the next /crew assign is a
            // normal fresh order — no special "pending question" state to manage.
            focusMaterial.remove(bot.getId());
            orderFocus.remove(bot.getId());
            bot.setCurrentOrder(null);
            bot.setStatus(BotStatus.IDLE);
            learning.observe(bot, "plan", "Asked what to gather", true, order);
            return result;
        }

        applyFocus(bot, focus, order);
        bot.setStatus(BotStatus.BUSY);
        return result;
    }

    /** @deprecated use {@link #planOrder} */
    public void parseOrder(CrewBot bot, String order) {
        Location loc = null;
        NpcHandle body = npcService.get(bot.getId());
        if (body != null && body.isValid()) {
            loc = body.getLocation();
        }
        planOrder(bot, order, loc != null ? loc : bot.getHome());
    }

    private void applyFocus(CrewBot bot, OrderFocus focus, String order) {
        orderFocus.put(bot.getId(), focus);
        if (focus.isSpecific() && focus.specific() != null) {
            focusMaterial.put(bot.getId(), focus.specific());
            learning.observe(bot, "plan", "Focus specific " + focus.specific().name(), true, order);
        } else {
            // Generic category — any matching type of that category (nearest wood, etc.)
            focusMaterial.remove(bot.getId());
            learning.observe(bot, "plan", "Focus category " + focus.category().name(), true, order);
        }
        bot.setCurrentOrder(order);
    }

    public void tick(CrewBot bot) {
        if (bot.getTitle() == null || !bot.getTitle().isGatherer()) {
            return;
        }
        if (bot.getStatus() == BotStatus.STOPPED || bot.getStatus() == BotStatus.DISMISSED) {
            return;
        }

        NpcHandle body = npcService.ensureBody(bot);
        if (body == null || !body.isValid()) {
            return;
        }
        Location loc = body.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }

        Location home = bot.getHome() != null ? bot.getHome() : loc;
        String tkey = GatherFocus.configKey(bot.getTitle());

        String order = bot.getCurrentOrder();
        boolean auto = plugin.getConfig().getBoolean("titles." + tkey + ".auto-when-idle", false);
        // Any non-blank order on a gatherer means work — not only keyword-matched phrases
        boolean ordered = order != null && !order.isBlank();
        boolean gatherWords = looksLikeGather(order);
        if (ordered && bot.getStatus() != BotStatus.STOPPED && bot.getStatus() != BotStatus.DISMISSED) {
            // Non-gather phrases still allowed if player assigned them to a gatherer
            if (gatherWords || bot.getStatus() == BotStatus.BUSY || bot.getStatus() == BotStatus.IDLE) {
                bot.setStatus(BotStatus.BUSY);
            }
        }
        boolean shouldGather = (ordered && (gatherWords || bot.getStatus() == BotStatus.BUSY))
                || (auto && (bot.getStatus() == BotStatus.IDLE || bot.getStatus() == BotStatus.BUSY));

        if (!shouldGather || bot.getStatus() == BotStatus.STOPPED) {
            return;
        }

        chests.ensureStorageNear(home);

        int carried = bot.getLoot().totalItems();
        // 0 → 64 (an armful, like a player). Negative → wait until no empty slots.
        int depositThreshold = plugin.getConfig().getInt("titles." + tkey + ".deposit-threshold", 64);
        int titleRadius = plugin.getConfig().getInt("titles." + tkey + ".gather-radius", 0);
        int global = radiusService.effective();
        // Title can raise the floor; global work-radius is the main knob (/crew radius)
        int radius = titleRadius > 0 ? Math.max(titleRadius, global) : global;
        if (!ordered) {
            // Idle auto-scavenge uses at least title radius but not beyond effective global
            radius = titleRadius > 0 ? Math.min(Math.max(titleRadius, 16), global) : Math.min(global, 32);
        }
        radius = radiusService.clamp(radius);

        Nav nav = navByBot.computeIfAbsent(bot.getId(), id -> new Nav());
        boolean timeToStash = bot.getLoot().shouldDeposit(depositThreshold);
        if (timeToStash) {
            announceReturn(bot, nav, carried);
            deposit(bot, body, loc, home, nav);
            return;
        }

        updateStuck(nav, loc);

        Material focus = focusMaterial.get(bot.getId());
        Block target = resolveTarget(bot, loc, radius, focus, nav);

        // Pickaxe work (ore/stone target): craft / repair tools first
        if (target != null && GatherFocus.isPickaxeBlock(target.getType())) {
            Material needFor = target != null ? target.getType() : null;
            // If prep says gather a prereq, temporarily prefer that
            MinerTools.PrepResult prep = minerTools.ensureReady(bot, body, loc, home, needFor);
            if (prep.action != MinerTools.PrepAction.READY) {
                if (prep.message != null && !minerTools.shouldThrottleMessage(bot.getId())) {
                    bot.remember(prep.message);
                    org.bukkit.entity.Player owner = bot.getOwnerPlayer();
                    if (owner != null && owner.isOnline()) {
                        owner.sendMessage(org.bukkit.ChatColor.GOLD + bot.getName()
                                + org.bukkit.ChatColor.GRAY + ": " + prep.message);
                    }
                }
                if (prep.action == MinerTools.PrepAction.NEED_MATERIALS && prep.gatherHint != null) {
                    // Dig prerequisite (wood/stone/iron/coal) with whatever we can
                    Block prereq = findNearestMaterial(bot, loc, radius, prep.gatherHint);
                    if (prereq != null) {
                        target = prereq;
                    } else if (target != null && !canHarvestWithCurrentTools(bot, target.getType())) {
                        // Can't harvest target and no prereq nearby
                        return;
                    }
                } else if (prep.action == MinerTools.PrepAction.WALKING
                        || prep.action == MinerTools.PrepAction.CRAFTED
                        || prep.action == MinerTools.PrepAction.PLACED_STATION
                        || prep.action == MinerTools.PrepAction.REPAIRED) {
                    return; // spend this tick on crafting/walking to station
                }
            }
        }

        if (target == null) {
            learning.observe(bot, "scavenge", "No valued blocks within " + radius, false, locBlockKey(loc));
            // Expand once to full effective radius if title radius was smaller
            int full = radiusService.effective();
            if (radius < full) {
                target = resolveTarget(bot, loc, full, focus, nav);
                radius = full;
            }
            if (target == null) {
                // Nothing reachable via existing air pockets — try carving a short,
                // safety-checked corridor to the nearest buried ore/stone instead of
                // giving up outright. Surface materials (wood, sand, etc.) are always
                // already exposed, so this only applies to pickaxe-tier blocks.
                Block buried = tunnelDigger.activeTarget(bot);
                if (buried == null && searchCooldownElapsed(bot)) {
                    List<Material> tunnelValued = GatherFocus.materialsFor(plugin, bot.getTitle());
                    OrderFocus tunnelOf = orderFocus.get(bot.getId());
                    buried = tunnelDigger.findBuriedTarget(loc, full, b ->
                            GatherFocus.isPickaxeBlock(b.getType())
                                    && matchesBot(bot, b.getType(), focus, tunnelValued, tunnelOf)
                                    && !tooCloseToStorage(bot, b)
                                    && !isProtected(b));
                    if (buried == null) {
                        nextTunnelSearchMs.put(bot.getId(), System.currentTimeMillis() + 10_000L);
                    }
                }
                if (buried != null) {
                    boolean progressed = tunnelDigger.tick(bot, body, buried);
                    if (progressed) {
                        return;
                    }
                    markSkip(bot, buried);
                    tunnelDigger.clear(bot);
                }
                if (carried > 0) {
                    announceReturn(bot, nav, carried);
                    deposit(bot, body, loc, home, nav);
                    return;
                }
                maybeAnnounce(bot, "No matching blocks within " + full
                        + " — walking around home. "
                        + "Try /crew radius " + Math.min(full + 32, radiusService.hardMax())
                        + " or move me nearer resources. /crew stop " + bot.getName() + " to halt.");
                // Patrol out from home so we discover new chunks/trees
                Location roam = roamPoint(home, loc, nav, full);
                if (roam != null && loc.distanceSquared(roam) > 2.25) {
                    stepToward(body, roam, nav);
                } else {
                    Location homeFeet = approachNear(home.getBlock(), loc);
                    if (homeFeet != null && loc.distanceSquared(homeFeet) > 4.0) {
                        stepToward(body, homeFeet, nav);
                    } else {
                        body.stopWalking();
                    }
                }
                return;
            }
        }

        // Soft gate: don't waste time on ore we can't harvest yet
        if (GatherFocus.isPickaxeBlock(target.getType()) && !canHarvestWithCurrentTools(bot, target.getType())) {
            MinerTools.Tier need = MinerTools.requiredTier(target.getType());
            if (!minerTools.shouldThrottleMessage(bot.getId())) {
                org.bukkit.entity.Player owner = bot.getOwnerPlayer();
                if (owner != null && owner.isOnline()) {
                    owner.sendMessage(org.bukkit.ChatColor.GOLD + bot.getName()
                            + org.bukkit.ChatColor.GRAY + ": Need a "
                            + need.name().toLowerCase(Locale.ROOT)
                            + " pickaxe (or better) for "
                            + target.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                            + ". Crafting/repairing…");
                }
            }
            // Skip this target until we have the tool
            markSkip(bot, target);
            nav.targetKey = null;
            return;
        }

        // Stand next to the resource (never path into the solid log). approachNear()
        // scores candidates partly by distance from the bot's CURRENT position, so
        // recomputing it every tick as the bot moves can flip the chosen tile between
        // two similarly-scored spots — feeding the navigator a different destination
        // each tick and making the bot visibly oscillate/circle instead of committing
        // to one path. Stick with the same approach tile for as long as this target
        // is unchanged; only recompute if we don't have one yet.
        Location approach = sameTarget(nav, target) ? nav.approach : null;
        if (approach == null) {
            approach = approachNear(target, loc);
            if (approach == null) {
                markSkip(bot, target);
                nav.targetKey = null;
                nav.approach = null;
                return;
            }
            nav.targetKey = blockKey(target);
            nav.approach = approach;
        }

        double distSq = loc.distanceSquared(approach);
        // Within ~2.5 blocks of approach tile OR close enough to hit the resource
        double hitDistSq = loc.distanceSquared(target.getLocation().add(0.5, 0.5, 0.5));
        if (distSq > 6.25 && hitDistSq > 9.0) {
            // Stuck: nudge then abandon — but never teleport while the real
            // pathfinder is actively following a path. A legitimate door transition
            // (approach, open, step through, close behind) barely moves position for
            // a tick or two; teleporting mid-transition cancels the in-progress path
            // and the door's open state along with it, producing an endless
            // open/close loop right at the doorway instead of ever passing through.
            if (nav.stuckTicks >= 3 && !body.isWalking()) {
                nudgeToward(body, approach);
            }
            if (nav.stuckTicks >= 8) {
                markSkip(bot, target);
                body.stopWalking();
                nav.stuckTicks = 0;
                nav.targetKey = null;
                nav.approach = null;
                maybeAnnounce(bot, "Can't path to that block — picking another.");
                return;
            }
            stepToward(body, approach, nav);
            return;
        }

        // Close enough — stop and mine
        body.stopWalking();
        nav.stuckTicks = 0;

        Material type = target.getType();
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL
                || type == Material.BEDROCK || type == Material.SPAWNER || type.isAir()) {
            nav.targetKey = null;
            return;
        }

        Entity ent = body.getEntity();
        Location aim = target.getLocation().add(0.5, 0.6, 0.5);
        body.lookAt(aim);
        body.swingMainHand();

        ItemStack tool = toolForBlock(bot, type);
        Collection<ItemStack> drops = target.getDrops(tool != null ? tool : new ItemStack(Material.IRON_AXE));
        Location dropAt = target.getLocation().add(0.5, 0.5, 0.5);
        target.setType(Material.AIR);
        if (tool != null && MinerTools.isPickaxe(tool.getType())) {
            minerTools.wearPick(bot.getLoot(), tool);
        }
        int added = 0;
        for (ItemStack drop : drops) {
            ItemStack leftover = bot.getLoot().add(drop);
            added += drop.getAmount() - (leftover == null ? 0 : leftover.getAmount());
            if (leftover != null) {
                target.getWorld().dropItemNaturally(dropAt, leftover);
            }
        }
        if (ent instanceof LivingEntity living2) {
            living2.swingMainHand();
        }
        bot.setStatus(BotStatus.BUSY);
        bot.remember("Gathered " + type.name() + " x" + Math.max(1, added));
        learning.observe(bot, "scavenge", "Gathered " + type.name(), true, locBlockKey(dropAt));
        nav.targetKey = null;
        nav.approach = null;

        if (bot.getLoot().totalItems() >= 3) {
            learning.teach(bot, "Good resource near base: " + type.name(), "experience", true);
        }
    }

    private Block resolveTarget(CrewBot bot, Location loc, int radius, Material focus, Nav nav) {
        List<Material> valued = GatherFocus.materialsFor(plugin, bot.getTitle());
        OrderFocus of = orderFocus.get(bot.getId());
        // Keep current target if still valid
        if (nav.targetKey != null) {
            Block cur = blockFromKey(loc.getWorld(), nav.targetKey);
            if (cur != null
                    && matchesBot(bot, cur.getType(), focus, valued, of)
                    && !isSkipped(bot.getId(), cur)
                    && !tooCloseToStorage(bot, cur)
                    && !isProtected(cur)
                    && cur.getLocation().distanceSquared(loc) <= (radius + 4) * (radius + 4.0)) {
                return cur;
            }
        }
        // Always re-evaluate nearest for this order + title awareness
        return findNearest(bot, loc, radius, focus, valued, of,
                skipBlocks.getOrDefault(bot.getId(), Set.of()));
    }

    private boolean matchesBot(CrewBot bot, Material type, Material legacyFocus,
                               List<Material> valued, OrderFocus of) {
        if (of != null) {
            if (of.category() == OrderFocus.Category.GENERIC) {
                return GatherFocus.matches(bot.getTitle(), type, null, valued);
            }
            return of.accepts(type);
        }
        return GatherFocus.matches(bot.getTitle(), type, legacyFocus, valued);
    }

    private void updateStuck(Nav nav, Location loc) {
        if (nav.lastPos == null) {
            nav.lastPos = loc.clone();
            nav.stuckTicks = 0;
            return;
        }
        double moved = nav.lastPos.distanceSquared(loc);
        if (moved < 0.04) { // barely moved (~0.2 blocks)
            nav.stuckTicks++;
        } else {
            nav.stuckTicks = Math.max(0, nav.stuckTicks - 1);
        }
        nav.lastPos = loc.clone();
    }

    private boolean sameTarget(Nav nav, Block target) {
        return nav.approach != null && nav.targetKey != null && nav.targetKey.equals(blockKey(target));
    }

    private void markSkip(CrewBot bot, Block b) {
        skipBlocks.computeIfAbsent(bot.getId(), id -> ConcurrentHashMap.newKeySet()).add(blockKey(b));
        learning.observe(bot, "path", "Skipped unreachable " + b.getType(), false, blockKey(b));
    }

    private boolean isSkipped(UUID botId, Block b) {
        Set<String> s = skipBlocks.get(botId);
        return s != null && s.contains(blockKey(b));
    }

    private void announceReturn(CrewBot bot, Nav nav, int carried) {
        if (nav.announcedReturn) {
            return;
        }
        nav.announcedReturn = true;
        org.bukkit.entity.Player owner = bot.getOwnerPlayer();
        if (owner != null && owner.isOnline()) {
            org.bukkit.Bukkit.broadcastMessage("§f<" + bot.getName() + "> heading back to drop this off ("
                    + carried + ")");
        }
    }

    private void deposit(CrewBot bot, NpcHandle body, Location loc, Location home, Nav nav) {
        // Always stash at home/storage hub — not a random chest in the woods
        Location chestLoc = chests.nearestChestWithSpace(home != null ? home : loc);
        if (chestLoc == null) {
            chestLoc = chests.ensureStorageNear(home != null ? home : loc);
        }
        if (chestLoc == null) {
            learning.observe(bot, "deposit", "No chest available", false, null);
            return;
        }
        // Far haul: lay rails + chest minecart when stocked
        if (railHaul.tryHaulDeposit(bot, body, loc, chestLoc)) {
            return;
        }
        Location chestApproach = approachNear(chestLoc.getBlock(), loc);
        if (chestApproach == null) {
            chestApproach = chestLoc.clone().add(0.5, 0, 0.5);
        }
        if (loc.distanceSquared(chestApproach) > 6.0) {
            stepToward(body, chestApproach, nav);
            return;
        }

        body.stopWalking();
        if (bot.getLoot().isEmpty()) {
            return;
        }

        int freeBefore = chests.freeSlots();
        int depositedTotal = 0;
        ItemStack[] contents = bot.getLoot().getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            int before = stack.getAmount();
            // depositStack fills empty slots / partial stacks; expands double-chest if full
            int left = chests.depositStack(stack);
            int deposited = before - left;
            depositedTotal += deposited;
            if (left <= 0) {
                bot.getLoot().getInventory().setItem(i, null);
            } else {
                stack.setAmount(left);
                bot.getLoot().getInventory().setItem(i, stack);
            }
        }

        // Still holding items → need more storage; expand double-chest near network (indoor-aware)
        if (!bot.getLoot().isEmpty()) {
            boolean expanded = chests.expandChest(chestLoc);
            if (expanded) {
                learning.observe(bot, "expand_chest", "Placed new double chest near storage", true, null);
                learning.teach(bot,
                        "When storage is full I place another double chest beside the network on the same floor",
                        "experience", true);
                // Retry deposit into the new space
                contents = bot.getLoot().getInventory().getContents();
                for (int i = 0; i < contents.length; i++) {
                    ItemStack stack = contents[i];
                    if (stack == null || stack.getType().isAir()) {
                        continue;
                    }
                    int before = stack.getAmount();
                    int left = chests.depositStack(stack);
                    depositedTotal += before - left;
                    if (left <= 0) {
                        bot.getLoot().getInventory().setItem(i, null);
                    } else {
                        stack.setAmount(left);
                        bot.getLoot().getInventory().setItem(i, stack);
                    }
                }
                org.bukkit.entity.Player owner = bot.getOwnerPlayer();
                if (owner != null && owner.isOnline()) {
                    owner.sendMessage(org.bukkit.ChatColor.GOLD + bot.getName()
                            + org.bukkit.ChatColor.GRAY + ": Storage was full — placed another double chest nearby"
                            + " (free slots now: " + chests.freeSlots() + ").");
                }
            } else if (depositedTotal == 0) {
                learning.observe(bot, "expand_chest", "Could not place more chests (no space?)", false, null);
            }
        }

        if (depositedTotal > 0) {
            nav.announcedReturn = false;
            bot.remember("Deposited " + depositedTotal + " items (storage free slots: "
                    + chests.freeSlots() + ", was " + freeBefore + ")");
            learning.observe(bot, "deposit", "Deposited " + depositedTotal + " items", true, null);
            learning.teach(bot, "Storage network accepts gathered materials into empty chest slots", "experience", true);
            org.bukkit.entity.Player owner = bot.getOwnerPlayer();
            if (owner != null && owner.isOnline()) {
                org.bukkit.Bukkit.broadcastMessage("§f<" + bot.getName() + "> stashed "
                        + depositedTotal + " — heading back out");
            }
        }

        if (looksLikeGather(bot.getCurrentOrder()) || bot.getStatus() == BotStatus.BUSY) {
            bot.setStatus(BotStatus.BUSY);
        } else if (bot.getLoot().isEmpty()) {
            bot.setStatus(BotStatus.IDLE);
        }
    }

    /**
     * Find a standable block next to {@code resource} (prefer same floor as bot).
     * Never returns the resource block itself (pathing into solids causes circles).
     */
    private Location approachNear(Block resource, Location from) {
        if (resource == null || resource.getWorld() == null) {
            return null;
        }
        World world = resource.getWorld();
        int preferY = from != null ? from.getBlockY() : resource.getY();
        BlockFace[] faces = {
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
                BlockFace.NORTH_EAST, BlockFace.NORTH_WEST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST
        };
        Location best = null;
        double bestD = Double.MAX_VALUE;

        for (int yOff : new int[]{0, -1, 1, -2, 2}) {
            for (BlockFace face : faces) {
                Block n = resource.getRelative(face.getModX(), yOff, face.getModZ());
                int footY = n.getY();
                // Prefer standable at n's column near preferY
                for (int dy = 0; dy <= 2; dy++) {
                    for (int sign : new int[]{0, 1, -1}) {
                        if (dy == 0 && sign != 0) {
                            continue;
                        }
                        int fy = preferY + sign * dy;
                        if (!NpcLocations.canStandAt(world, n.getX(), fy, n.getZ())) {
                            continue;
                        }
                        // Must be able to reach-hit resource from here (within 3.5 blocks of resource center)
                        Location feet = new Location(world, n.getX() + 0.5, fy, n.getZ() + 0.5);
                        double toRes = feet.distanceSquared(resource.getLocation().add(0.5, 0.5, 0.5));
                        if (toRes > 16.0) {
                            continue;
                        }
                        double fromBot = from == null ? 0 : feet.distanceSquared(from);
                        // Prefer closer to bot and similar Y
                        double score = fromBot + Math.abs(fy - preferY) * 4.0 + Math.abs(footY - preferY);
                        if (score < bestD) {
                            bestD = score;
                            best = feet;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Nearest block this title is aware of (true distance, approachable only).
     */
    private Block findNearest(CrewBot bot, Location origin, int radius, Material focus,
                              List<Material> valued, OrderFocus of, Set<String> skip) {
        Block best = null;
        double bestD = Double.MAX_VALUE;
        // Honor requested radius (was hard-capped at 32 — ignored /crew radius)
        int r = Math.min(Math.max(8, radius), radiusService.hardMax());
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }

        // Union of ore-depth (mining) and canopy-height (woodcutting) ranges — a
        // generalist gatherer needs both. Costs more per-tick scan volume than a
        // single-role bot would, but this only runs when there's no cached target.
        int yMin = -24;
        int yMax = 16;

        for (int x = -r; x <= r; x++) {
            for (int y = yMin; y <= yMax; y++) {
                for (int z = -r; z <= r; z++) {
                    int by = oy + y;
                    if (by < world.getMinHeight() || by >= world.getMaxHeight()) {
                        continue;
                    }
                    Block b = world.getBlockAt(ox + x, by, oz + z);
                    Material t = b.getType();
                    if (t.isAir() || !matchesBot(bot, t, focus, valued, of)) {
                        continue;
                    }
                    if (skip.contains(blockKey(b))) {
                        continue;
                    }
                    if (tooCloseToStorage(bot, b)) {
                        continue;
                    }
                    if (isProtected(b)) {
                        continue;
                    }
                    if (approachNear(b, origin) == null) {
                        continue;
                    }
                    double d = b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(origin);
                    if (t.name().endsWith("_LEAVES")) {
                        d += 2.0;
                    }
                    if (t.name().endsWith("_LOG") && y > 3) {
                        d += 1.5;
                    }
                    if (d < bestD) {
                        bestD = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }

    private boolean canHarvestWithCurrentTools(CrewBot bot, Material block) {
        MinerTools.Tier need = MinerTools.requiredTier(block);
        if (need == MinerTools.Tier.NONE || need == MinerTools.Tier.WOOD) {
            return true; // hand or wood is fine / soft
        }
        ItemStack pick = minerTools.bestPick(bot.getLoot());
        if (pick == null) {
            return false;
        }
        return MinerTools.tierOfPick(pick.getType()).atLeast(need);
    }

    private ItemStack toolForBlock(CrewBot bot, Material block) {
        if (GatherFocus.isPickaxeBlock(block)) {
            ItemStack pick = minerTools.bestPick(bot.getLoot());
            if (pick != null) {
                return pick;
            }
        }
        if (GatherFocus.isWoodsmanBlock(block)) {
            ItemStack axe = bot.getLoot().findFirst(i -> i.getType().name().endsWith("_AXE"));
            if (axe != null) {
                return axe;
            }
            return new ItemStack(Material.IRON_AXE);
        }
        return new ItemStack(Material.IRON_PICKAXE);
    }

    private boolean isProtected(Block b) {
        return b != null && protectedZones != null
                && protectedZones.isProtected(b.getLocation().add(0.5, 0.5, 0.5));
    }

    private boolean tooCloseToStorage(CrewBot bot, Block b) {
        if (b == null) {
            return false;
        }
        // storage-keepout exists to stop mining pits/tunnels from opening up next to
        // the base — chopping a tree near the house is normal player behavior, not
        // the thing it was meant to prevent, so wood gets its own (default: no)
        // keepout instead of inheriting the wider ore/stone radius.
        double r = GatherFocus.isWoodsmanBlock(b.getType())
                ? plugin.getConfig().getDouble("crew.storage-keepout-wood", 0.0)
                : plugin.getConfig().getDouble("crew.storage-keepout", 8.0);
        if (r <= 0) {
            return false;
        }
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        Location home = bot.getHome();
        if (home != null && home.getWorld() != null && at.getWorld() != null
                && home.getWorld().equals(at.getWorld())
                && at.distanceSquared(home) <= r * r) {
            return true;
        }
        return chests.isNearAny(at, r);
    }

    private Block findNearestMaterial(CrewBot bot, Location origin, int radius, Material want) {
        if (origin == null || origin.getWorld() == null || want == null) {
            return null;
        }
        World world = origin.getWorld();
        int r = Math.min(Math.max(8, radius), radiusService.hardMax());
        Block best = null;
        double bestD = Double.MAX_VALUE;
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        for (int x = -r; x <= r; x++) {
            for (int y = -16; y <= 12; y++) {
                for (int z = -r; z <= r; z++) {
                    int by = oy + y;
                    if (by < world.getMinHeight() || by >= world.getMaxHeight()) {
                        continue;
                    }
                    Block b = world.getBlockAt(ox + x, by, oz + z);
                    Material t = b.getType();
                    boolean ok = t == want
                            || (want == Material.OAK_LOG && t.name().endsWith("_LOG"))
                            || (want == Material.STONE && (t == Material.STONE || t == Material.COBBLESTONE
                            || t == Material.DEEPSLATE))
                            || (want == Material.IRON_ORE && (t == Material.IRON_ORE
                            || t == Material.DEEPSLATE_IRON_ORE))
                            || (want == Material.COAL_ORE && (t == Material.COAL_ORE
                            || t == Material.DEEPSLATE_COAL_ORE))
                            || (want == Material.IRON_INGOT && (t == Material.IRON_ORE
                            || t == Material.DEEPSLATE_IRON_ORE));
                    if (!ok) {
                        continue;
                    }
                    if (bot != null && tooCloseToStorage(bot, b)) {
                        continue;
                    }
                    if (approachNear(b, origin) == null) {
                        continue;
                    }
                    double d = b.getLocation().distanceSquared(origin);
                    if (d < bestD) {
                        bestD = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }

    private final Map<UUID, Long> lastAnnounceMs = new ConcurrentHashMap<>();
    /** Buried-target search is a full-volume block scan — throttle repeated failures
     *  (nothing to tunnel to) instead of re-scanning every crew tick. */
    private final Map<UUID, Long> nextTunnelSearchMs = new ConcurrentHashMap<>();

    private boolean searchCooldownElapsed(CrewBot bot) {
        Long next = nextTunnelSearchMs.get(bot.getId());
        return next == null || System.currentTimeMillis() >= next;
    }

    private void maybeAnnounce(CrewBot bot, String msg) {
        if (bot == null || msg == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long prev = lastAnnounceMs.get(bot.getId());
        if (prev != null && now - prev < 12_000L) {
            return;
        }
        lastAnnounceMs.put(bot.getId(), now);
        bot.remember(msg);
        org.bukkit.entity.Player owner = bot.getOwnerPlayer();
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(org.bukkit.ChatColor.GOLD + bot.getName()
                    + org.bukkit.ChatColor.GRAY + ": " + msg);
        }
    }

    /** When stuck, teleport a short step toward the goal so work continues. */
    private void nudgeToward(NpcHandle body, Location target) {
        if (body == null || target == null || !body.isValid()) {
            return;
        }
        Location from = body.getLocation();
        if (from == null || from.getWorld() == null || !from.getWorld().equals(target.getWorld())) {
            return;
        }
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.3) {
            return;
        }
        double step = Math.min(1.4, dist);
        Location mid = from.clone().add(dx / dist * step, 0, dz / dist * step);
        mid = NpcLocations.snapToStand(mid);
        if (mid != null) {
            body.teleport(mid);
            body.walkTo(target, plugin.getConfig().getDouble("crew.walk-speed", 1.05));
        }
    }

    private Location roamPoint(Location home, Location from, Nav nav, int workRadius) {
        if (home == null || home.getWorld() == null) {
            return null;
        }
        // Cycle compass points out toward work radius (not stuck in 12-block circle)
        int phase = (nav.stuckTicks + (int) (System.currentTimeMillis() / 5000L)) & 7;
        double angle = phase * (Math.PI / 4.0);
        double maxR = Math.max(12, Math.min(workRadius * 0.6, workRadius - 4));
        double r = 8 + (phase % 4) * (maxR / 4.0);
        Location p = home.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
        return NpcLocations.snapToStand(p);
    }

    private void stepToward(NpcHandle body, Location target, Nav nav) {
        if (body == null || target == null || body.getLocation() == null) {
            return;
        }
        // Cooldown between re-paths for this bot
        if (nav.repathCooldown > 0) {
            nav.repathCooldown--;
            if (body.isWalking()) {
                return;
            }
        }
        double speed = plugin.getConfig().getDouble("crew.walk-speed", 1.05);
        boolean started = body.walkTo(target, speed);
        if (started) {
            nav.repathCooldown = 2; // skip next 2 crew ticks if path sticks
        }
    }

    public static boolean looksLikeGather(String order) {
        if (order == null || order.isBlank()) {
            return false;
        }
        String l = order.toLowerCase(Locale.ROOT);
        return l.contains("gather") || l.contains("scavenge") || l.contains("collect")
                || l.contains("mine") || l.contains("wood") || l.contains("fill")
                || l.contains("loot") || l.contains("everything")
                || l.contains("log") || l.contains("timber") || l.contains("lumber")
                || l.contains("fetch") || l.contains("bring") || l.contains("get ")
                || l.contains("go get") || l.contains("chop") || l.contains("cut tree")
                || l.contains("cobble") || l.contains("iron") || l.contains("coal")
                || l.contains("sand") || l.contains("gravel") || l.contains("harvest")
                || l.contains("leaf") || l.contains("flower") || l.contains("stone")
                || l.contains("ore") || l.contains("diamond") || l.contains("work")
                || l.contains("dig") || l.contains("clear");
    }

    private static String locBlockKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private static String blockKey(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private static Block blockFromKey(World world, String key) {
        if (world == null || key == null) {
            return null;
        }
        try {
            String[] p = key.split(":");
            String[] xyz = p[p.length - 1].split(",");
            int x = Integer.parseInt(xyz[0]);
            int y = Integer.parseInt(xyz[1]);
            int z = Integer.parseInt(xyz[2]);
            return world.getBlockAt(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }
}
