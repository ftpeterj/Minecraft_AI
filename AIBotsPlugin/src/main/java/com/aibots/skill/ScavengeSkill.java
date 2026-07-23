package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import com.aibots.learn.LearningService;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcLocations;
import com.aibots.npc.NpcService;
import com.aibots.npc.VillagerHandle;
import com.aibots.storage.ChestNetwork;
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
 * Gather engine for scavenger / miner / woodsman.
 * Each title is aware of different nearest resources ({@link GatherFocus}).
 */
public class ScavengeSkill {

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private final ChestNetwork chests;
    private final LearningService learning;
    private final MinerTools minerTools;
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
    }

    public ScavengeSkill(JavaPlugin plugin, NpcService npcService, ChestNetwork chests, LearningService learning) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.chests = chests;
        this.learning = learning;
        this.minerTools = new MinerTools(plugin, chests, learning);
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

        if (result.startWork) {
            applyFocus(bot, focus, order);
            bot.setStatus(BotStatus.BUSY);
        } else {
            // Hold work until player picks an option (force / alternative / stop)
            orderFocus.put(bot.getId(), focus);
            if (focus.specific() != null) {
                focusMaterial.put(bot.getId(), focus.specific());
            }
            bot.setStatus(BotStatus.IDLE);
            bot.setCurrentOrder(null);
            learning.observe(bot, "plan", "Awaiting choice for " + focus.label(), false, order);
        }
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
        boolean auto = plugin.getConfig().getBoolean("titles." + tkey + ".auto-when-idle",
                plugin.getConfig().getBoolean("titles.scavenger.auto-when-idle", false));
        boolean ordered = order != null && looksLikeGather(order);
        if (ordered && bot.getStatus() != BotStatus.STOPPED && bot.getStatus() != BotStatus.DISMISSED) {
            bot.setStatus(BotStatus.BUSY);
        }
        boolean shouldGather = ordered || (auto && bot.getStatus() == BotStatus.IDLE);

        if (!shouldGather || bot.getStatus() == BotStatus.STOPPED) {
            return;
        }

        chests.ensureStorageNear(home);

        int carried = bot.getLoot().totalItems();
        // 0 or negative = deposit only when bag is full (keep gathering otherwise)
        int depositThreshold = plugin.getConfig().getInt("titles." + tkey + ".deposit-threshold",
                plugin.getConfig().getInt("titles.scavenger.deposit-threshold", 0));
        int radius = plugin.getConfig().getInt("titles." + tkey + ".gather-radius",
                plugin.getConfig().getInt("titles.scavenger.gather-radius", 24));

        boolean bagFull = bot.getLoot().getInventory().firstEmpty() == -1;
        boolean hitSoftCap = depositThreshold > 0 && carried >= depositThreshold;
        // Only leave the field to deposit when full (or soft-cap if configured).
        // Broken tools are handled in miner prep — bot keeps the gather order after.
        if (bagFull || hitSoftCap) {
            navByBot.remove(bot.getId());
            deposit(bot, body, loc);
            return;
        }

        Nav nav = navByBot.computeIfAbsent(bot.getId(), id -> new Nav());
        updateStuck(nav, loc);

        Material focus = focusMaterial.get(bot.getId());
        Block target = resolveTarget(bot, loc, radius, focus, nav);

        // Miners (and scavengers doing pickaxe work): craft / repair tools first
        if (bot.getTitle() == BotTitle.MINER
                || (bot.getTitle() == BotTitle.SCAVENGER && target != null
                && GatherFocus.isPickaxeBlock(target.getType()))) {
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
                    Block prereq = findNearestMaterial(loc, radius, prep.gatherHint);
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
            // Idle near home — not frantic re-path
            Location homeFeet = approachNear(home.getBlock(), loc);
            if (homeFeet != null && loc.distanceSquared(homeFeet) > 4.0) {
                stepToward(body, homeFeet, nav);
            } else {
                body.stopWalking();
            }
            return;
        }

        // Soft gate: don't waste time on ore we can't harvest yet (miner)
        if (bot.getTitle() == BotTitle.MINER && !canHarvestWithCurrentTools(bot, target.getType())) {
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

        // Stand next to the resource (never path into the solid log)
        Location approach = approachNear(target, loc);
        if (approach == null) {
            markSkip(bot, target);
            nav.targetKey = null;
            nav.approach = null;
            return;
        }

        nav.targetKey = blockKey(target);
        nav.approach = approach;

        double distSq = loc.distanceSquared(approach);
        // Within ~2.5 blocks of approach tile OR close enough to hit the resource
        double hitDistSq = loc.distanceSquared(target.getLocation().add(0.5, 0.5, 0.5));
        if (distSq > 6.25 && hitDistSq > 9.0) {
            // Stuck circling? abandon this tree
            if (nav.stuckTicks >= 5) {
                markSkip(bot, target);
                body.stopWalking();
                nav.stuckTicks = 0;
                nav.targetKey = null;
                nav.approach = null;
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
        if (body instanceof VillagerHandle vh) {
            vh.lookAt(aim);
        } else if (ent instanceof Mob mob) {
            try {
                mob.lookAt(aim);
            } catch (Throwable ignored) {
            }
        }
        if (ent instanceof LivingEntity living) {
            living.swingMainHand();
        }

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

    private void markSkip(CrewBot bot, Block b) {
        skipBlocks.computeIfAbsent(bot.getId(), id -> ConcurrentHashMap.newKeySet()).add(blockKey(b));
        learning.observe(bot, "path", "Skipped unreachable " + b.getType(), false, blockKey(b));
    }

    private boolean isSkipped(UUID botId, Block b) {
        Set<String> s = skipBlocks.get(botId);
        return s != null && s.contains(blockKey(b));
    }

    private void deposit(CrewBot bot, NpcHandle body, Location loc) {
        // Prefer a chest that still has empty slots
        Location chestLoc = chests.nearestChestWithSpace(loc);
        if (chestLoc == null) {
            chestLoc = chests.ensureStorageNear(bot.getHome() != null ? bot.getHome() : loc);
        }
        if (chestLoc == null) {
            learning.observe(bot, "deposit", "No chest available", false, null);
            return;
        }
        Location chestApproach = approachNear(chestLoc.getBlock(), loc);
        if (chestApproach == null) {
            chestApproach = chestLoc.clone().add(0.5, 0, 0.5);
        }
        if (loc.distanceSquared(chestApproach) > 6.0) {
            Nav nav = navByBot.computeIfAbsent(bot.getId(), id -> new Nav());
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
            bot.remember("Deposited " + depositedTotal + " items (storage free slots: "
                    + chests.freeSlots() + ", was " + freeBefore + ")");
            learning.observe(bot, "deposit", "Deposited " + depositedTotal + " items", true, null);
            learning.teach(bot, "Storage network accepts gathered materials into empty chest slots", "experience", true);
        }

        // Keep working until /crew stop — deposit is only a pit stop when bag is full
        if (looksLikeGather(bot.getCurrentOrder())) {
            bot.setStatus(BotStatus.BUSY);
            if (depositedTotal > 0 && bot.getLoot().isEmpty()) {
                org.bukkit.entity.Player owner = bot.getOwnerPlayer();
                if (owner != null && owner.isOnline()) {
                    owner.sendMessage(org.bukkit.ChatColor.GOLD + bot.getName()
                            + org.bukkit.ChatColor.GRAY + ": Deposited haul — bag empty, resuming work. "
                            + org.bukkit.ChatColor.DARK_GRAY + "/crew stop " + bot.getName() + " to halt.");
                }
            }
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
        BotTitle title = bot.getTitle();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        int r = Math.min(radius, 32);
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }

        int yMin = title == BotTitle.MINER ? -24 : -6;
        int yMax = title == BotTitle.WOODSMAN ? 16 : (title == BotTitle.MINER ? 8 : 10);

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
                    if (approachNear(b, origin) == null) {
                        continue;
                    }
                    double d = b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(origin);
                    if (title == BotTitle.WOODSMAN && t.name().endsWith("_LEAVES")) {
                        d += 2.0;
                    }
                    if (title == BotTitle.WOODSMAN && t.name().endsWith("_LOG") && y > 3) {
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
        if (GatherFocus.isPickaxeBlock(block) || bot.getTitle() == BotTitle.MINER) {
            ItemStack pick = minerTools.bestPick(bot.getLoot());
            if (pick != null) {
                return pick;
            }
        }
        if (GatherFocus.isWoodsmanBlock(block) || bot.getTitle() == BotTitle.WOODSMAN) {
            ItemStack axe = bot.getLoot().findFirst(i -> i.getType().name().endsWith("_AXE"));
            if (axe != null) {
                return axe;
            }
            return new ItemStack(Material.IRON_AXE);
        }
        return new ItemStack(Material.IRON_PICKAXE);
    }

    private Block findNearestMaterial(Location origin, int radius, Material want) {
        if (origin == null || origin.getWorld() == null || want == null) {
            return null;
        }
        World world = origin.getWorld();
        int r = Math.min(radius, 32);
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
