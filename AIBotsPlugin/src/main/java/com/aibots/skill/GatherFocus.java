package com.aibots.skill;

import com.aibots.crew.BotTitle;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What each gather title notices in the world (nearest matching block).
 */
public final class GatherFocus {

    private GatherFocus() {
    }

    public static String configKey(BotTitle title) {
        if (title == null) {
            return "scavenger";
        }
        return title.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Materials this title will harvest, from config + built-in awareness rules.
     */
    public static List<Material> materialsFor(JavaPlugin plugin, BotTitle title) {
        String key = configKey(title);
        List<String> names = plugin.getConfig().getStringList("titles." + key + ".valued-materials");
        List<Material> list = new ArrayList<>();
        if (names != null) {
            for (String n : names) {
                try {
                    list.add(Material.valueOf(n.toUpperCase(Locale.ROOT)));
                } catch (Exception ignored) {
                }
            }
        }
        if (!list.isEmpty()) {
            return list;
        }
        return new ArrayList<>(defaultsFor(title));
    }

    public static Set<Material> defaultsFor(BotTitle title) {
        if (title == null) {
            title = BotTitle.SCAVENGER;
        }
        return switch (title) {
            case MINER -> minerDefaults();
            case WOODSMAN -> woodsmanDefaults();
            case SCAVENGER -> scavengerDefaults();
            default -> scavengerDefaults();
        };
    }

    /**
     * True if this material is something the title cares about
     * (explicit list, family match, or order focus).
     */
    public static boolean matches(BotTitle title, Material type, Material orderFocus, List<Material> valued) {
        if (type == null || type.isAir()) {
            return false;
        }
        // Explicit order focus always wins when set (e.g. "get iron")
        if (orderFocus != null) {
            if (type == orderFocus) {
                return true;
            }
            // Log family / ore family
            if (orderFocus.name().endsWith("_LOG") && type.name().endsWith("_LOG")) {
                return true;
            }
            if (orderFocus.name().endsWith("_ORE") && type.name().endsWith("_ORE")) {
                return true;
            }
            if (orderFocus == Material.COBBLESTONE && (type == Material.STONE || type == Material.COBBLESTONE)) {
                return true;
            }
            // If order focus is set, only that focus (don't harvest other title materials)
            return false;
        }
        if (valued.contains(type)) {
            return true;
        }
        // Soft awareness: title family patterns even if config list is incomplete
        return switch (title) {
            case MINER -> isPickaxeBlock(type);
            case WOODSMAN -> isWoodsmanBlock(type);
            case SCAVENGER -> isPickaxeBlock(type) || isWoodsmanBlock(type)
                    || type == Material.SAND || type == Material.GRAVEL || type == Material.CLAY
                    || type == Material.DIRT || type == Material.COARSE_DIRT;
            default -> false;
        };
    }

    /** Blocks a miner would use a pickaxe on. */
    public static boolean isPickaxeBlock(Material t) {
        if (t == null) {
            return false;
        }
        String n = t.name();
        if (n.endsWith("_ORE") || n.contains("DEEPSLATE") && n.endsWith("_ORE")) {
            return true;
        }
        if (n.equals("ANCIENT_DEBRIS") || n.equals("RAW_IRON_BLOCK") || n.equals("RAW_COPPER_BLOCK")
                || n.equals("RAW_GOLD_BLOCK") || n.equals("AMETHYST_BLOCK") || n.equals("BUDDING_AMETHYST")
                || n.contains("AMETHYST_CLUSTER") || n.contains("AMETHYST_BUD")) {
            return true;
        }
        return switch (t) {
            case STONE, COBBLESTONE, MOSSY_COBBLESTONE, STONE_BRICKS, MOSSY_STONE_BRICKS,
                    ANDESITE, DIORITE, GRANITE, TUFF, CALCITE, DRIPSTONE_BLOCK,
                    DEEPSLATE, COBBLED_DEEPSLATE, BLACKSTONE, BASALT, SMOOTH_BASALT,
                    NETHERRACK, END_STONE, OBSIDIAN, CRYING_OBSIDIAN,
                    TERRACOTTA, SANDSTONE, RED_SANDSTONE, PRISMARINE, DARK_PRISMARINE,
                    ICE, PACKED_ICE, BLUE_ICE -> true;
            default -> n.endsWith("_TERRACOTTA") || n.endsWith("CONCRETE")
                    || (n.contains("COPPER") && !n.contains("ORE") && t.isBlock()
                    && (n.contains("BLOCK") || n.contains("ORE")));
        };
    }

    /** Trees, leaves, flowers, saplings, mushrooms, etc. */
    public static boolean isWoodsmanBlock(Material t) {
        if (t == null) {
            return false;
        }
        String n = t.name();
        if (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM") || n.endsWith("_HYPHAE")) {
            return true;
        }
        if (n.endsWith("_LEAVES") || n.endsWith("_SAPLING") || n.contains("PROPAGULE")) {
            return true;
        }
        if (n.endsWith("_FLOWER") || n.contains("TULIP") || n.contains("ORCHID") || n.contains("LILY")
                || n.contains("DAISY") || n.contains("POPPY") || n.contains("DANDELION")
                || n.contains("CORNFLOWER") || n.contains("ALLIUM") || n.contains("AZURE")
                || n.contains("WITHER_ROSE") || n.contains("TORCHFLOWER") || n.contains("PITCHER")
                || n.contains("SUNFLOWER") || n.contains("LILAC") || n.contains("PEONY")
                || n.contains("ROSE_BUSH") || n.equals("FLOWERING_AZALEA") || n.equals("AZALEA")
                || n.equals("SPORE_BLOSSOM") || n.equals("PINK_PETALS") || n.equals("WILDFLOWERS")) {
            return true;
        }
        if (n.contains("MUSHROOM") || n.contains("FUNGUS") || n.contains("ROOTS")
                || n.equals("VINE") || n.equals("GLOW_LICHEN") || n.equals("MOSS_BLOCK")
                || n.equals("MOSS_CARPET") || n.equals("HANGING_ROOTS") || n.equals("BIG_DRIPLEAF")
                || n.equals("SMALL_DRIPLEAF") || n.equals("CAVE_VINES") || n.contains("CAVE_VINES")
                || n.equals("SWEET_BERRY_BUSH") || n.equals("COCOA") || n.equals("BAMBOO")
                || n.equals("BAMBOO_SAPLING") || n.equals("SUGAR_CANE") || n.equals("CACTUS")
                || n.equals("KELP") || n.equals("KELP_PLANT") || n.equals("SEA_PICKLE")
                || n.equals("DEAD_BUSH") || n.equals("FERN") || n.equals("LARGE_FERN")
                || n.equals("SHORT_GRASS") || n.equals("TALL_GRASS")
                || n.equals("SEAGRASS") || n.equals("TALL_SEAGRASS")) {
            return true;
        }
        return false;
    }

    private static Set<Material> scavengerDefaults() {
        Set<Material> s = EnumSet.noneOf(Material.class);
        s.addAll(minerDefaults());
        s.addAll(woodsmanDefaults());
        s.add(Material.SAND);
        s.add(Material.RED_SAND);
        s.add(Material.GRAVEL);
        s.add(Material.CLAY);
        s.add(Material.DIRT);
        s.add(Material.COARSE_DIRT);
        return s;
    }

    private static Set<Material> minerDefaults() {
        Set<Material> s = EnumSet.noneOf(Material.class);
        for (Material m : Material.values()) {
            if (m.isBlock() && isPickaxeBlock(m)) {
                // Skip decorative pure copper blocks spam; keep ores + stone family
                String n = m.name();
                if (n.contains("COPPER") && !n.contains("ORE") && !n.startsWith("RAW_")) {
                    continue;
                }
                if (n.endsWith("CONCRETE") || n.endsWith("_TERRACOTTA")) {
                    continue;
                }
                s.add(m);
            }
        }
        // Ensure common ones even if enum quirks
        s.add(Material.STONE);
        s.add(Material.COBBLESTONE);
        s.add(Material.COAL_ORE);
        s.add(Material.DEEPSLATE_COAL_ORE);
        s.add(Material.IRON_ORE);
        s.add(Material.DEEPSLATE_IRON_ORE);
        s.add(Material.COPPER_ORE);
        s.add(Material.DEEPSLATE_COPPER_ORE);
        s.add(Material.GOLD_ORE);
        s.add(Material.DEEPSLATE_GOLD_ORE);
        s.add(Material.DIAMOND_ORE);
        s.add(Material.DEEPSLATE_DIAMOND_ORE);
        s.add(Material.LAPIS_ORE);
        s.add(Material.REDSTONE_ORE);
        s.add(Material.EMERALD_ORE);
        return s;
    }

    private static Set<Material> woodsmanDefaults() {
        Set<Material> s = EnumSet.noneOf(Material.class);
        for (Material m : Material.values()) {
            if (m.isBlock() && isWoodsmanBlock(m)) {
                String n = m.name();
                // Skip pure planks as harvest targets by default (player-built)
                if (n.endsWith("_PLANKS")) {
                    continue;
                }
                s.add(m);
            }
        }
        return s;
    }
}
