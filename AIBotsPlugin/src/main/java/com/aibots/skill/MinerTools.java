package com.aibots.skill;

import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import com.aibots.crew.CrewLootHolder;
import com.aibots.learn.LearningService;
import com.aibots.npc.NpcHandle;
import com.aibots.storage.ChestNetwork;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Miner knowledge: pickaxe tiers, crafting recipes, anvil repair vs craft-new.
 * <p>
 * Progression: logs → planks → crafting table → sticks → wooden pick → stone → …
 * Iron tools need ingots (virtual smelt from ore + coal when possible).
 */
public final class MinerTools {

    public enum Tier {
        NONE(0), WOOD(1), STONE(2), IRON(3), DIAMOND(4), NETHERITE(5);

        final int level;

        Tier(int level) {
            this.level = level;
        }

        boolean atLeast(Tier other) {
            return this.level >= other.level;
        }
    }

    public enum PrepAction {
        READY,           // have correct pick
        CRAFTED,         // just crafted something — tick again
        REPAIRED,        // anvil repair done
        NEED_MATERIALS,  // can't craft/repair yet — gather prerequisites
        PLACED_STATION,  // placed crafting table / furnace / anvil
        WALKING          // walking to station / materials
    }

    public static final class PrepResult {
        public final PrepAction action;
        public final String message;
        public final Material gatherHint; // what to dig next if NEED_MATERIALS

        PrepResult(PrepAction action, String message, Material gatherHint) {
            this.action = action;
            this.message = message;
            this.gatherHint = gatherHint;
        }

        static PrepResult ready() {
            return new PrepResult(PrepAction.READY, null, null);
        }
    }

    private final JavaPlugin plugin;
    private final ChestNetwork chests;
    private final LearningService learning;
    /** Remember stations we placed per bot home key */
    private final Map<UUID, Location> craftTables = new ConcurrentHashMap<>();
    private final Map<UUID, Location> furnaces = new ConcurrentHashMap<>();
    private final Map<UUID, Location> anvils = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPlayerMsg = new ConcurrentHashMap<>();

    public MinerTools(JavaPlugin plugin, ChestNetwork chests, LearningService learning) {
        this.plugin = plugin;
        this.chests = chests;
        this.learning = learning;
    }

    public static boolean isMinerRole(BotTitle title) {
        return title == BotTitle.MINER
                || (title == BotTitle.SCAVENGER); // scavengers may mine stone/ore too
    }

    public static boolean isPickaxe(Material m) {
        if (m == null) {
            return false;
        }
        String n = m.name();
        return n.endsWith("_PICKAXE");
    }

    public static Tier tierOfPick(Material m) {
        if (m == null) {
            return Tier.NONE;
        }
        return switch (m) {
            case WOODEN_PICKAXE, GOLDEN_PICKAXE -> Tier.WOOD;
            case STONE_PICKAXE -> Tier.STONE;
            case IRON_PICKAXE -> Tier.IRON;
            case DIAMOND_PICKAXE -> Tier.DIAMOND;
            case NETHERITE_PICKAXE -> Tier.NETHERITE;
            default -> Tier.NONE;
        };
    }

    public static Material pickForTier(Tier t) {
        return switch (t) {
            case WOOD -> Material.WOODEN_PICKAXE;
            case STONE -> Material.STONE_PICKAXE;
            case IRON -> Material.IRON_PICKAXE;
            case DIAMOND -> Material.DIAMOND_PICKAXE;
            case NETHERITE -> Material.NETHERITE_PICKAXE;
            default -> Material.WOODEN_PICKAXE;
        };
    }

    /** Minimum pick tier to properly harvest this block (vanilla-ish). */
    public static Tier requiredTier(Material block) {
        if (block == null) {
            return Tier.NONE;
        }
        String n = block.name();
        if (n.equals("OBSIDIAN") || n.equals("CRYING_OBSIDIAN") || n.equals("ANCIENT_DEBRIS")
                || n.equals("RESPAWN_ANCHOR") || n.equals("NETHERITE_BLOCK")) {
            return Tier.DIAMOND;
        }
        if (n.contains("DIAMOND") || n.contains("EMERALD") || n.contains("GOLD_ORE")
                || n.contains("REDSTONE_ORE") || n.equals("RAW_GOLD_BLOCK")) {
            return Tier.IRON;
        }
        if (n.contains("IRON_ORE") || n.contains("COPPER_ORE") || n.contains("LAPIS")
                || n.equals("RAW_IRON_BLOCK") || n.equals("RAW_COPPER_BLOCK")) {
            return Tier.STONE;
        }
        // stone, coal, netherrack, deepslate base, etc.
        if (GatherFocus.isPickaxeBlock(block)) {
            return Tier.WOOD;
        }
        return Tier.NONE;
    }

    public ItemStack bestPick(CrewLootHolder loot) {
        ItemStack best = null;
        Tier bestT = Tier.NONE;
        for (ItemStack s : loot.findAll(i -> isPickaxe(i.getType()))) {
            Tier t = tierOfPick(s.getType());
            if (t.level > bestT.level) {
                bestT = t;
                best = s;
            } else if (t == bestT && best != null && durabilityLeft(s) > durabilityLeft(best)) {
                best = s;
            }
        }
        return best;
    }

    public static int durabilityLeft(ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof Damageable d && stack.getType().getMaxDurability() > 0) {
            return stack.getType().getMaxDurability() - d.getDamage();
        }
        return stack.getType().getMaxDurability() > 0 ? stack.getType().getMaxDurability() : 100;
    }

    public static boolean isNearBroken(ItemStack pick) {
        if (pick == null) {
            return true;
        }
        int max = pick.getType().getMaxDurability();
        if (max <= 0) {
            return false;
        }
        return durabilityLeft(pick) < Math.max(10, max / 10);
    }

    public static boolean isDamaged(ItemStack pick) {
        if (pick == null) {
            return false;
        }
        ItemMeta meta = pick.getItemMeta();
        return meta instanceof Damageable d && d.getDamage() > 0;
    }

    /**
     * Ensure miner has a usable pick for {@code targetBlock} (or general mining if null).
     * Crafts stations/tools or repairs via "anvil" rules.
     */
    public PrepResult ensureReady(CrewBot bot, NpcHandle body, Location loc, Location home,
                                  Material targetBlock) {
        CrewLootHolder loot = bot.getLoot();
        Tier need = targetBlock != null ? requiredTier(targetBlock) : Tier.STONE;
        if (need == Tier.NONE) {
            need = Tier.WOOD;
        }

        ItemStack pick = bestPick(loot);
        Tier have = pick != null ? tierOfPick(pick.getType()) : Tier.NONE;

        // Prefer repair when we have a good-enough pick that's worn
        if (pick != null && have.atLeast(need) && isNearBroken(pick)) {
            PrepResult rep = tryAnvilRepair(bot, body, loc, home, pick);
            if (rep.action == PrepAction.REPAIRED || rep.action == PrepAction.PLACED_STATION
                    || rep.action == PrepAction.WALKING || rep.action == PrepAction.NEED_MATERIALS) {
                return rep;
            }
            // repair failed materials — fall through to craft new if possible
        }

        if (pick != null && have.atLeast(need) && !isNearBroken(pick)) {
            return PrepResult.ready();
        }

        // Need a better (or any) pick — craft progression
        if (have.level < need.level || pick == null) {
            return craftTowardTier(bot, body, loc, home, need);
        }

        return PrepResult.ready();
    }

    private PrepResult craftTowardTier(CrewBot bot, NpcHandle body, Location loc, Location home, Tier need) {
        CrewLootHolder loot = bot.getLoot();

        // Step chain: ensure we can craft the highest tier we can with current mats
        Tier goal = need;
        // Downgrade goal to what materials allow, but still progress
        if (goal.atLeast(Tier.IRON) && countIngots(loot, Material.IRON_INGOT) < 3
                && loot.count(Material.IRON_ORE) + loot.count(Material.DEEPSLATE_IRON_ORE)
                + loot.count(Material.RAW_IRON) < 3) {
            // Can we smelt?
            if (canSmeltIron(loot)) {
                return smeltIron(bot, body, loc, home);
            }
            if (goal == Tier.IRON || goal.atLeast(Tier.DIAMOND)) {
                // dig iron first
                return new PrepResult(PrepAction.NEED_MATERIALS,
                        "I need iron for a better pick — mining iron ore first.",
                        Material.IRON_ORE);
            }
        }

        if (goal.atLeast(Tier.DIAMOND) && loot.count(Material.DIAMOND) < 3) {
            if (countIngots(loot, Material.IRON_INGOT) >= 3 || bestPick(loot) != null
                    && tierOfPick(bestPick(loot).getType()).atLeast(Tier.IRON)) {
                return new PrepResult(PrepAction.NEED_MATERIALS,
                        "I need diamonds for that pick — searching for diamond ore.",
                        Material.DIAMOND_ORE);
            }
            goal = Tier.IRON;
        }

        if (goal.atLeast(Tier.STONE) && loot.count(Material.COBBLESTONE) < 3
                && loot.count(Material.COBBLED_DEEPSLATE) < 3) {
            // Need wooden pick first to get cobble
            ItemStack p = bestPick(loot);
            if (p == null || !tierOfPick(p.getType()).atLeast(Tier.WOOD)) {
                goal = Tier.WOOD;
            } else {
                return new PrepResult(PrepAction.NEED_MATERIALS,
                        "I need cobblestone for a stone pick — mining stone.",
                        Material.STONE);
            }
        }

        if (goal == Tier.WOOD || goal.atLeast(Tier.WOOD)) {
            // Planks / sticks / table
            ensurePlanks(loot);
            if (countAnyPlanks(loot) < 2 && countAnyLog(loot) < 1) {
                return new PrepResult(PrepAction.NEED_MATERIALS,
                        "I need wood to craft a pick and crafting table — gathering logs.",
                        Material.OAK_LOG);
            }
            ensurePlanks(loot);
            // Crafting table in world (place once)
            if (!hasNearbyStation(loc, home, Material.CRAFTING_TABLE)
                    && loot.count(Material.CRAFTING_TABLE) < 1) {
                if (countAnyPlanks(loot) >= 4) {
                    removeAnyPlanks(loot, 4);
                    loot.add(new ItemStack(Material.CRAFTING_TABLE));
                    learning.observe(bot, "craft", "Crafted crafting table (4 planks)", true, null);
                    return new PrepResult(PrepAction.CRAFTED, "Crafted a crafting table.", null);
                }
            }
            if (loot.count(Material.CRAFTING_TABLE) >= 1
                    && !hasNearbyStation(loc, home, Material.CRAFTING_TABLE)) {
                PrepResult placed = placeStation(bot, body, loc, home, Material.CRAFTING_TABLE, craftTables);
                if (placed != null) {
                    return placed;
                }
            }

            // Sticks
            if (loot.count(Material.STICK) < 2 && countAnyPlanks(loot) >= 2) {
                removeAnyPlanks(loot, 2);
                loot.add(new ItemStack(Material.STICK, 4));
                learning.observe(bot, "craft", "Crafted sticks", true, null);
                return new PrepResult(PrepAction.CRAFTED, "Crafted sticks.", null);
            }

            if (goal == Tier.WOOD || bestPick(loot) == null) {
                if (loot.count(Material.STICK) >= 2 && countAnyPlanks(loot) >= 3) {
                    loot.remove(Material.STICK, 2);
                    removeAnyPlanks(loot, 3);
                    loot.add(new ItemStack(Material.WOODEN_PICKAXE));
                    learning.observe(bot, "craft", "Crafted wooden pickaxe", true, null);
                    learning.teach(bot, "Recipe: wooden pickaxe = 3 planks + 2 sticks", "craft", true);
                    return new PrepResult(PrepAction.CRAFTED, "Crafted a wooden pickaxe.", null);
                }
                if (countAnyLog(loot) < 1 && countAnyPlanks(loot) < 3) {
                    return new PrepResult(PrepAction.NEED_MATERIALS,
                            "Need more wood for a wooden pickaxe (3 planks + 2 sticks).",
                            Material.OAK_LOG);
                }
            }
        }

        // Stone pick
        if (goal.atLeast(Tier.STONE)) {
            ensureSticks(loot);
            int cobble = loot.count(Material.COBBLESTONE) + loot.count(Material.COBBLED_DEEPSLATE);
            if (loot.count(Material.STICK) >= 2 && cobble >= 3) {
                loot.remove(Material.STICK, 2);
                if (loot.count(Material.COBBLESTONE) >= 3) {
                    loot.remove(Material.COBBLESTONE, 3);
                } else {
                    loot.remove(Material.COBBLED_DEEPSLATE, 3);
                }
                loot.add(new ItemStack(Material.STONE_PICKAXE));
                learning.observe(bot, "craft", "Crafted stone pickaxe", true, null);
                learning.teach(bot, "Recipe: stone pickaxe = 3 cobblestone + 2 sticks", "craft", true);
                return new PrepResult(PrepAction.CRAFTED, "Crafted a stone pickaxe.", null);
            }
        }

        // Iron pick
        if (goal.atLeast(Tier.IRON)) {
            if (countIngots(loot, Material.IRON_INGOT) < 3 && canSmeltIron(loot)) {
                return smeltIron(bot, body, loc, home);
            }
            ensureSticks(loot);
            if (loot.count(Material.STICK) >= 2 && countIngots(loot, Material.IRON_INGOT) >= 3) {
                loot.remove(Material.STICK, 2);
                loot.remove(Material.IRON_INGOT, 3);
                loot.add(new ItemStack(Material.IRON_PICKAXE));
                learning.observe(bot, "craft", "Crafted iron pickaxe", true, null);
                learning.teach(bot, "Recipe: iron pickaxe = 3 iron ingots + 2 sticks", "craft", true);
                return new PrepResult(PrepAction.CRAFTED, "Crafted an iron pickaxe.", null);
            }
        }

        // Diamond pick
        if (goal.atLeast(Tier.DIAMOND)) {
            ensureSticks(loot);
            if (loot.count(Material.STICK) >= 2 && loot.count(Material.DIAMOND) >= 3) {
                loot.remove(Material.STICK, 2);
                loot.remove(Material.DIAMOND, 3);
                loot.add(new ItemStack(Material.DIAMOND_PICKAXE));
                learning.observe(bot, "craft", "Crafted diamond pickaxe", true, null);
                learning.teach(bot, "Recipe: diamond pickaxe = 3 diamonds + 2 sticks", "craft", true);
                return new PrepResult(PrepAction.CRAFTED, "Crafted a diamond pickaxe.", null);
            }
        }

        ItemStack have = bestPick(loot);
        if (have != null && tierOfPick(have.getType()).atLeast(Tier.WOOD)) {
            return PrepResult.ready(); // use what we have; may be suboptimal
        }
        return new PrepResult(PrepAction.NEED_MATERIALS,
                "I understand the recipes, but I'm short on materials for a "
                        + goal.name().toLowerCase(Locale.ROOT) + " pickaxe.",
                goal.atLeast(Tier.IRON) ? Material.IRON_ORE : Material.OAK_LOG);
    }

    /**
     * Anvil repair: prefer repairing existing pick with material of same tier
     * rather than crafting a brand-new pick when nearly broken.
     */
    private PrepResult tryAnvilRepair(CrewBot bot, NpcHandle body, Location loc, Location home, ItemStack pick) {
        CrewLootHolder loot = bot.getLoot();
        Material mat = repairMaterial(pick.getType());
        if (mat == null) {
            return new PrepResult(PrepAction.NEED_MATERIALS, "Can't repair this pick type.", null);
        }
        // Need 1 repair unit (ingot/plank/cobble/diamond)
        if (!hasRepairUnit(loot, mat)) {
            return new PrepResult(PrepAction.NEED_MATERIALS,
                    "Pickaxe is nearly broken — I prefer anvil repair over crafting new. Need "
                            + friendly(mat) + " to repair.",
                    mat == Material.IRON_INGOT ? Material.IRON_ORE
                            : mat == Material.COBBLESTONE ? Material.STONE : Material.OAK_LOG);
        }

        // Ensure anvil station exists (place if we have iron+iron block simplified: craft anvil needs 3 iron + 4 iron blocks - hard)
        // Soft rule: if no anvil, "virtual anvil" at home once crafting table exists, OR place anvil if we have one
        if (!hasNearbyStation(loc, home, Material.ANVIL)
                && !hasNearbyStation(loc, home, Material.CHIPPED_ANVIL)
                && !hasNearbyStation(loc, home, Material.DAMAGED_ANVIL)) {
            if (loot.count(Material.ANVIL) >= 1) {
                PrepResult p = placeStation(bot, body, loc, home, Material.ANVIL, anvils);
                if (p != null) {
                    return p;
                }
            } else if (countIngots(loot, Material.IRON_INGOT) >= 31) {
                // Full anvil recipe: 3 iron blocks (27 ingots) + 4 ingots = 31
                loot.remove(Material.IRON_INGOT, 31);
                loot.add(new ItemStack(Material.ANVIL));
                learning.observe(bot, "craft", "Crafted anvil for repairs", true, null);
                learning.teach(bot, "Anvil repairs tools cheaper than crafting new when damaged", "craft", true);
                return new PrepResult(PrepAction.CRAFTED, "Crafted an anvil for repairs.", null);
            } else {
                // Virtual repair at crafting table / home — still teach preference
                learning.teach(bot,
                        "Prefer anvil repair for damaged picks when materials allow; crafting new wastes resources",
                        "craft", true);
            }
        }

        // Walk near anvil if placed far
        Location anvilLoc = anvils.get(bot.getId());
        if (anvilLoc != null && loc.distanceSquared(anvilLoc) > 16) {
            body.walkTo(anvilLoc.clone().add(0.5, 0, 0.5), 1.0);
            return new PrepResult(PrepAction.WALKING, "Walking to the anvil to repair.", null);
        }

        consumeRepairUnit(loot, mat);
        // Restore most durability
        ItemMeta meta = pick.getItemMeta();
        if (meta instanceof Damageable d) {
            int max = pick.getType().getMaxDurability();
            d.setDamage(Math.max(0, max / 20)); // nearly full
            pick.setItemMeta(meta);
        }
        learning.observe(bot, "repair", "Anvil-repaired " + pick.getType().name() + " with " + mat.name(), true, null);
        learning.teach(bot, "Repaired " + friendly(pick.getType()) + " on anvil using " + friendly(mat), "craft", true);
        return new PrepResult(PrepAction.REPAIRED,
                "Repaired my " + friendly(pick.getType()) + " on the anvil (better than crafting a new one).", null);
    }

    private static Material repairMaterial(Material pick) {
        return switch (pick) {
            case WOODEN_PICKAXE -> Material.OAK_PLANKS;
            case STONE_PICKAXE -> Material.COBBLESTONE;
            case IRON_PICKAXE -> Material.IRON_INGOT;
            case GOLDEN_PICKAXE -> Material.GOLD_INGOT;
            case DIAMOND_PICKAXE -> Material.DIAMOND;
            case NETHERITE_PICKAXE -> Material.NETHERITE_INGOT;
            default -> null;
        };
    }

    private boolean hasRepairUnit(CrewLootHolder loot, Material mat) {
        if (mat == Material.OAK_PLANKS) {
            return countAnyPlanks(loot) >= 1;
        }
        return loot.count(mat) >= 1;
    }

    private void consumeRepairUnit(CrewLootHolder loot, Material mat) {
        if (mat == Material.OAK_PLANKS) {
            removeAnyPlanks(loot, 1);
        } else {
            loot.remove(mat, 1);
        }
    }

    private PrepResult smeltIron(CrewBot bot, NpcHandle body, Location loc, Location home) {
        CrewLootHolder loot = bot.getLoot();
        if (!hasNearbyStation(loc, home, Material.FURNACE)
                && !hasNearbyStation(loc, home, Material.BLAST_FURNACE)) {
            if (loot.count(Material.FURNACE) < 1) {
                // Furnace = 8 cobble
                if (loot.count(Material.COBBLESTONE) >= 8) {
                    loot.remove(Material.COBBLESTONE, 8);
                    loot.add(new ItemStack(Material.FURNACE));
                    learning.observe(bot, "craft", "Crafted furnace", true, null);
                    return new PrepResult(PrepAction.CRAFTED, "Crafted a furnace to smelt iron.", null);
                }
                return new PrepResult(PrepAction.NEED_MATERIALS,
                        "Need cobblestone to craft a furnace for smelting iron.", Material.STONE);
            }
            PrepResult p = placeStation(bot, body, loc, home, Material.FURNACE, furnaces);
            if (p != null) {
                return p;
            }
        }
        // Virtual smelt: 1 ore/raw + 1 coal → 1 ingot
        if (loot.count(Material.COAL) < 1 && loot.count(Material.CHARCOAL) < 1) {
            return new PrepResult(PrepAction.NEED_MATERIALS,
                    "Need coal/charcoal as furnace fuel to smelt iron.", Material.COAL_ORE);
        }
        boolean fuelCoal = loot.count(Material.COAL) >= 1;
        int smelted = 0;
        for (int i = 0; i < 3 && smelted < 3; i++) {
            if (loot.count(Material.IRON_ORE) >= 1) {
                loot.remove(Material.IRON_ORE, 1);
            } else if (loot.count(Material.DEEPSLATE_IRON_ORE) >= 1) {
                loot.remove(Material.DEEPSLATE_IRON_ORE, 1);
            } else if (loot.count(Material.RAW_IRON) >= 1) {
                loot.remove(Material.RAW_IRON, 1);
            } else {
                break;
            }
            if (fuelCoal) {
                loot.remove(Material.COAL, 1);
            } else {
                loot.remove(Material.CHARCOAL, 1);
            }
            loot.add(new ItemStack(Material.IRON_INGOT));
            smelted++;
            fuelCoal = loot.count(Material.COAL) >= 1;
            if (!fuelCoal && loot.count(Material.CHARCOAL) < 1) {
                break;
            }
        }
        if (smelted > 0) {
            learning.observe(bot, "smelt", "Smelted " + smelted + " iron ingot(s)", true, null);
            learning.teach(bot, "Smelt iron ore with coal in a furnace to make iron ingots for tools", "craft", true);
            return new PrepResult(PrepAction.CRAFTED, "Smelted " + smelted + " iron ingot(s).", null);
        }
        return new PrepResult(PrepAction.NEED_MATERIALS, "Need iron ore to smelt.", Material.IRON_ORE);
    }

    private boolean canSmeltIron(CrewLootHolder loot) {
        int ore = loot.count(Material.IRON_ORE) + loot.count(Material.DEEPSLATE_IRON_ORE)
                + loot.count(Material.RAW_IRON);
        int fuel = loot.count(Material.COAL) + loot.count(Material.CHARCOAL);
        return ore >= 1 && fuel >= 1;
    }

    private PrepResult placeStation(CrewBot bot, NpcHandle body, Location loc, Location home,
                                    Material station, Map<UUID, Location> map) {
        Location base = home != null ? home : loc;
        if (base == null || base.getWorld() == null) {
            return null;
        }
        // Walk home first if far
        if (loc.distanceSquared(base) > 36) {
            body.walkTo(base.clone().add(0.5, 0, 0.5), 1.0);
            return new PrepResult(PrepAction.WALKING, "Heading home to set up " + friendly(station) + ".", null);
        }
        Block spot = findPlaceSpot(base);
        if (spot == null) {
            return new PrepResult(PrepAction.NEED_MATERIALS, "No space to place " + friendly(station) + ".", null);
        }
        bot.getLoot().remove(station, 1);
        spot.setType(station);
        map.put(bot.getId(), spot.getLocation());
        learning.observe(bot, "place", "Placed " + station.name(), true, null);
        return new PrepResult(PrepAction.PLACED_STATION, "Placed a " + friendly(station) + " at base.", null);
    }

    private Block findPlaceSpot(Location home) {
        int hx = home.getBlockX();
        int hy = home.getBlockY();
        int hz = home.getBlockZ();
        var world = home.getWorld();
        int[][] offs = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {3, 0}, {0, 3}};
        for (int[] o : offs) {
            Block b = world.getBlockAt(hx + o[0], hy, hz + o[1]);
            Block below = b.getRelative(BlockFace.DOWN);
            if (b.getType().isAir() && below.getType().isSolid()) {
                return b;
            }
        }
        return null;
    }

    private boolean hasNearbyStation(Location loc, Location home, Material type) {
        Location c = loc != null ? loc : home;
        if (c == null || c.getWorld() == null) {
            return false;
        }
        int r = 6;
        for (int x = -r; x <= r; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    if (c.getWorld().getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z)
                            .getType() == type) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void ensurePlanks(CrewLootHolder loot) {
        // Convert one log → 4 planks if short on planks
        while (countAnyPlanks(loot) < 4 && countAnyLog(loot) >= 1) {
            Material log = firstLog(loot);
            if (log == null) {
                break;
            }
            loot.remove(log, 1);
            Material planks = planksForLog(log);
            loot.add(new ItemStack(planks, 4));
        }
    }

    private void ensureSticks(CrewLootHolder loot) {
        ensurePlanks(loot);
        if (loot.count(Material.STICK) < 2 && countAnyPlanks(loot) >= 2) {
            removeAnyPlanks(loot, 2);
            loot.add(new ItemStack(Material.STICK, 4));
        }
    }

    private static Material firstLog(CrewLootHolder loot) {
        for (ItemStack s : loot.getInventory().getContents()) {
            if (s != null && s.getType().name().endsWith("_LOG")) {
                return s.getType();
            }
        }
        return null;
    }

    private static int countAnyLog(CrewLootHolder loot) {
        int n = 0;
        for (ItemStack s : loot.getInventory().getContents()) {
            if (s != null && (s.getType().name().endsWith("_LOG") || s.getType().name().endsWith("_STEM"))) {
                n += s.getAmount();
            }
        }
        return n;
    }

    private static int countAnyPlanks(CrewLootHolder loot) {
        int n = 0;
        for (ItemStack s : loot.getInventory().getContents()) {
            if (s != null && s.getType().name().endsWith("_PLANKS")) {
                n += s.getAmount();
            }
        }
        return n;
    }

    private static void removeAnyPlanks(CrewLootHolder loot, int amount) {
        int need = amount;
        for (ItemStack s : loot.getInventory().getContents()) {
            if (need <= 0) {
                break;
            }
            if (s != null && s.getType().name().endsWith("_PLANKS")) {
                need -= loot.remove(s.getType(), need);
            }
        }
    }

    private static Material planksForLog(Material log) {
        String n = log.name();
        if (n.endsWith("_LOG")) {
            try {
                return Material.valueOf(n.substring(0, n.length() - 4) + "_PLANKS");
            } catch (Exception ignored) {
            }
        }
        if (n.equals("CRIMSON_STEM")) {
            return Material.CRIMSON_PLANKS;
        }
        if (n.equals("WARPED_STEM")) {
            return Material.WARPED_PLANKS;
        }
        return Material.OAK_PLANKS;
    }

    private static int countIngots(CrewLootHolder loot, Material ingot) {
        return loot.count(ingot);
    }

    private static String friendly(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Damage pick slightly when mining. */
    public void wearPick(CrewLootHolder loot, ItemStack pick) {
        if (pick == null) {
            return;
        }
        ItemMeta meta = pick.getItemMeta();
        if (meta instanceof Damageable d) {
            int max = pick.getType().getMaxDurability();
            d.setDamage(Math.min(max - 1, d.getDamage() + 1));
            pick.setItemMeta(meta);
        }
    }

    public boolean shouldThrottleMessage(UUID botId) {
        long now = System.currentTimeMillis();
        Long last = lastPlayerMsg.get(botId);
        if (last != null && now - last < 8000L) {
            return true;
        }
        lastPlayerMsg.put(botId, now);
        return false;
    }

    public List<String> recipeHelpLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Recipes I know:");
        lines.add(" • Crafting table = 4 planks (from any log)");
        lines.add(" • Sticks = 2 planks");
        lines.add(" • Wooden pick = 3 planks + 2 sticks");
        lines.add(" • Stone pick = 3 cobble + 2 sticks (mines iron ore)");
        lines.add(" • Furnace = 8 cobble; smelt ore + coal → ingots");
        lines.add(" • Iron pick = 3 iron ingots + 2 sticks (mines diamonds)");
        lines.add(" • Diamond pick = 3 diamonds + 2 sticks");
        lines.add(" • Anvil repair uses 1 material of that tier — prefer repair over crafting new when damaged");
        return lines;
    }
}
