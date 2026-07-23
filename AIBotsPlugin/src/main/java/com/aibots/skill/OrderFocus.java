package com.aibots.skill;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Parsed gather order: generic category vs specific material (spruce, iron, …).
 */
public final class OrderFocus {

    public enum Category {
        WOOD,          // any log/wood
        LEAVES,
        FLOWER,
        PICKAXE,       // any pickaxe block (generic mine)
        STONE,         // stone-like (not ores)
        ORE,           // any ore
        SAND,
        GRAVEL,
        CLAY,
        DIRT,
        GENERIC,       // title default awareness
        SPECIFIC       // single material / tight family
    }

    private final Category category;
    private final Material specific;   // null if generic category
    private final boolean force;       // skip distance warnings
    private final String rawOrder;
    private final boolean specificRequest;

    public OrderFocus(Category category, Material specific, boolean force, String rawOrder, boolean specificRequest) {
        this.category = category;
        this.specific = specific;
        this.force = force;
        this.rawOrder = rawOrder == null ? "" : rawOrder;
        this.specificRequest = specificRequest;
    }

    public Category category() {
        return category;
    }

    public Material specific() {
        return specific;
    }

    public boolean force() {
        return force;
    }

    public String rawOrder() {
        return rawOrder;
    }

    public boolean isSpecific() {
        return specificRequest && specific != null;
    }

    public String label() {
        if (specific != null) {
            return friendly(specific);
        }
        return switch (category) {
            case WOOD -> "any wood/logs";
            case LEAVES -> "leaves";
            case FLOWER -> "flowers";
            case PICKAXE -> "stone/ores (pickaxe)";
            case STONE -> "stone/cobble";
            case ORE -> "ores";
            case SAND -> "sand";
            case GRAVEL -> "gravel";
            case CLAY -> "clay";
            case DIRT -> "dirt";
            case GENERIC -> "whatever is nearest";
            case SPECIFIC -> specific != null ? friendly(specific) : "that resource";
        };
    }

    public static String friendly(Material m) {
        if (m == null) {
            return "?";
        }
        String n = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        // spruce log -> spruce
        n = n.replace(" deepslate ", " ");
        if (n.endsWith(" log")) {
            n = n.substring(0, n.length() - 4);
        }
        if (n.endsWith(" ore")) {
            // keep "iron ore"
        }
        return n;
    }

    /** Does this block satisfy the order? */
    public boolean accepts(Material type) {
        if (type == null || type.isAir()) {
            return false;
        }
        String n = type.name();
        if (isSpecific() && specific != null) {
            return acceptsSpecific(type, specific);
        }
        return switch (category) {
            // Tree wood + leaves (chop trees down) — never kelp/vines
            case WOOD -> isTreeWood(type) || n.endsWith("_LEAVES");
            case LEAVES -> n.endsWith("_LEAVES");
            case FLOWER -> isFlowerPlant(type);
            case PICKAXE -> GatherFocus.isPickaxeBlock(type);
            case STONE -> isStoneFamily(type);
            case ORE -> n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS");
            case SAND -> type == Material.SAND || type == Material.RED_SAND;
            case GRAVEL -> type == Material.GRAVEL;
            case CLAY -> type == Material.CLAY;
            case DIRT -> type == Material.DIRT || type == Material.COARSE_DIRT || type == Material.ROOTED_DIRT;
            case GENERIC, SPECIFIC -> false;
        };
    }

    /** Logs / stripped wood / nether stems — not kelp, bamboo, vines. */
    public static boolean isTreeWood(Material type) {
        if (type == null) {
            return false;
        }
        String n = type.name();
        if (n.contains("KELP") || n.contains("VINE") || n.equals("BAMBOO") || n.equals("BAMBOO_SAPLING")
                || n.contains("SUGAR_CANE") || n.contains("CACTUS")) {
            return false;
        }
        return n.endsWith("_LOG")
                || n.endsWith("_WOOD")   // stripped oak wood, etc.
                || n.equals("CRIMSON_STEM") || n.equals("WARPED_STEM")
                || n.equals("STRIPPED_CRIMSON_STEM") || n.equals("STRIPPED_WARPED_STEM")
                || n.equals("CRIMSON_HYPHAE") || n.equals("WARPED_HYPHAE")
                || n.equals("STRIPPED_CRIMSON_HYPHAE") || n.equals("STRIPPED_WARPED_HYPHAE");
    }

    private static boolean isFlowerPlant(Material type) {
        String n = type.name();
        return n.contains("FLOWER") || n.contains("TULIP") || n.contains("ORCHID")
                || n.contains("LILY") || n.contains("DAISY") || n.contains("POPPY")
                || n.contains("DANDELION") || n.contains("CORNFLOWER") || n.contains("ALLIUM")
                || n.contains("AZURE") || n.contains("SUNFLOWER") || n.contains("LILAC")
                || n.contains("PEONY") || n.contains("ROSE") || n.contains("PETALS")
                || n.contains("TORCHFLOWER") || n.contains("WITHER_ROSE") || n.equals("SPORE_BLOSSOM");
    }

    private static boolean acceptsSpecific(Material type, Material want) {
        if (type == want) {
            return true;
        }
        String tn = type.name();
        String wn = want.name();
        // spruce log <-> spruce wood/leaves (whole tree)
        if (wn.endsWith("_LOG")) {
            String prefix = wn.substring(0, wn.length() - 4);
            return tn.equals(prefix + "_LOG") || tn.equals(prefix + "_WOOD")
                    || tn.equals(prefix + "_LEAVES");
        }
        if (wn.endsWith("_ORE")) {
            // iron ore <-> deepslate iron ore
            String base = wn.replace("DEEPSLATE_", "");
            return tn.equals(base) || tn.equals("DEEPSLATE_" + base.replace("DEEPSLATE_", ""));
        }
        if (want == Material.COBBLESTONE) {
            return type == Material.COBBLESTONE || type == Material.STONE;
        }
        if (want == Material.STONE) {
            return isStoneFamily(type);
        }
        return false;
    }

    private static boolean isStoneFamily(Material t) {
        return switch (t) {
            case STONE, COBBLESTONE, MOSSY_COBBLESTONE, ANDESITE, DIORITE, GRANITE,
                    DEEPSLATE, COBBLED_DEEPSLATE, TUFF, BLACKSTONE, BASALT, SMOOTH_BASALT,
                    NETHERRACK, END_STONE, SANDSTONE, RED_SANDSTONE -> true;
            default -> false;
        };
    }

    /**
     * Same survey category for nearest/alternatives — must stay tight to the order.
     * "wood" must NOT include kelp/flowers via woodsman plant list.
     */
    public boolean sameCategory(Material type) {
        if (type == null || type.isAir()) {
            return false;
        }
        if (isSpecific()) {
            if (specific != null && (specific.name().endsWith("_LOG") || specific.name().endsWith("_WOOD")
                    || specific.name().endsWith("_STEM"))) {
                return isTreeWood(type);
            }
            if (specific != null && (specific.name().endsWith("_ORE") || specific == Material.COBBLESTONE
                    || specific == Material.STONE)) {
                return GatherFocus.isPickaxeBlock(type);
            }
            return accepts(type);
        }
        // Generic category survey = only what accepts() means for that category
        return accepts(type);
    }
}
