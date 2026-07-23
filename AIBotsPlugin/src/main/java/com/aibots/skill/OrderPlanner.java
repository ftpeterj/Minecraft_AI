package com.aibots.skill;

import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parse gather orders and survey nearby resources for smart choices.
 */
public final class OrderPlanner {

    private OrderPlanner() {
    }

    public static final class SurveyHit {
        public final Material material;
        public final double distance;
        public final Block block;

        SurveyHit(Material material, double distance, Block block) {
            this.material = material;
            this.distance = distance;
            this.block = block;
        }
    }

    public static final class PlanResult {
        public final OrderFocus focus;
        /** Start gathering immediately */
        public final boolean startWork;
        /** Messages to send as the bot speaking / system choices */
        public final List<String> messages;

        PlanResult(OrderFocus focus, boolean startWork, List<String> messages) {
            this.focus = focus;
            this.startWork = startWork;
            this.messages = messages;
        }
    }

    public static OrderFocus parse(String order) {
        if (order == null || order.isBlank()) {
            return new OrderFocus(OrderFocus.Category.GENERIC, null, false, order, false);
        }
        String lower = order.toLowerCase(Locale.ROOT);
        boolean force = lower.contains("force") || lower.contains("anyway") || lower.contains("regardless")
                || lower.contains("just do it") || lower.contains("confirm") || lower.startsWith("yes ")
                || lower.equals("yes") || lower.contains("proceed")
                || lower.contains("any wood") || lower.contains("all wood") || lower.contains("all types")
                || lower.contains("mixed") || lower.contains("whatever") || lower.contains("any type")
                || lower.matches(".*\\bany\\b.*") && (lower.contains("wood") || lower.contains("log"));

        // Strip force words for matching
        String m = lower
                .replace("force", " ")
                .replace("anyway", " ")
                .replace("regardless", " ")
                .replace("confirm", " ")
                .replace("proceed", " ");

        // Specific woods first
        Material wood = matchWood(m);
        if (wood != null) {
            boolean specific = isSpecificWoodRequest(m);
            if (specific) {
                return new OrderFocus(OrderFocus.Category.SPECIFIC, wood, force, order, true);
            }
            return new OrderFocus(OrderFocus.Category.WOOD, null, force, order, false);
        }

        if (m.contains("leaf") || m.contains("leaves")) {
            Material leaf = matchPrefix(m, "_LEAVES");
            if (leaf != null) {
                return new OrderFocus(OrderFocus.Category.SPECIFIC, leaf, force, order, true);
            }
            return new OrderFocus(OrderFocus.Category.LEAVES, null, force, order, false);
        }
        if (m.contains("flower")) {
            return new OrderFocus(OrderFocus.Category.FLOWER, null, force, order, false);
        }

        // Specific ores / stone
        if (m.contains("diamond")) {
            return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.DIAMOND_ORE, force, order, true);
        }
        if (m.contains("emerald")) {
            return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.EMERALD_ORE, force, order, true);
        }
        if (m.contains("gold") && !m.contains("golden")) {
            return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.GOLD_ORE, force, order, true);
        }
        if (m.contains("iron")) {
            return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.IRON_ORE, force, order, true);
        }
        if (m.contains("copper")) {
            return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.COPPER_ORE, force, order, true);
        }
        if (m.contains("coal")) {
            return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.COAL_ORE, force, order, true);
        }
        if (m.contains("lapis")) {
            return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.LAPIS_ORE, force, order, true);
        }
        if (m.contains("redstone")) {
            return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.REDSTONE_ORE, force, order, true);
        }
        if (m.contains("cobble")) {
            return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.COBBLESTONE, force, order, true);
        }
        if (m.contains("netherrack")) {
            return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.NETHERRACK, force, order, true);
        }
        if (m.contains("deepslate") && !m.contains("ore")) {
            return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.DEEPSLATE, force, order, true);
        }
        if (m.contains("sand") && !m.contains("sandstone") && !m.contains("soul")) {
            boolean specific = m.contains("red sand");
            if (specific) {
                return new OrderFocus(OrderFocus.Category.SPECIFIC, Material.RED_SAND, force, order, true);
            }
            return new OrderFocus(OrderFocus.Category.SAND, null, force, order, false);
        }
        if (m.contains("gravel")) {
            return new OrderFocus(OrderFocus.Category.GRAVEL, null, force, order, false);
        }
        if (m.contains("clay")) {
            return new OrderFocus(OrderFocus.Category.CLAY, null, force, order, false);
        }
        if (m.contains("stone") && !m.contains("redstone") && !m.contains("sandstone")
                && !m.contains("cobble") && !m.contains("bricks")) {
            // "stone" alone = stone family generic-ish but often meant cobble/stone nearby
            return new OrderFocus(OrderFocus.Category.STONE, null, force, order, false);
        }
        if (m.contains("ore") || m.contains("mine") || m.contains("dig")) {
            return new OrderFocus(OrderFocus.Category.PICKAXE, null, force, order, false);
        }
        if (m.contains("wood") || m.contains("log") || m.contains("timber")
                || m.contains("lumber") || m.contains("tree") || m.contains("chop")) {
            return new OrderFocus(OrderFocus.Category.WOOD, null, force, order, false);
        }

        // Fallback: try material name
        for (Material mat : Material.values()) {
            if (!mat.isBlock()) {
                continue;
            }
            String key = mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            if (key.length() >= 4 && m.contains(key)) {
                return new OrderFocus(OrderFocus.Category.SPECIFIC, mat, force, order, true);
            }
        }
        return new OrderFocus(OrderFocus.Category.GENERIC, null, force, order, false);
    }

    private static boolean isSpecificWoodRequest(String m) {
        return m.contains("oak") || m.contains("spruce") || m.contains("birch")
                || m.contains("jungle") || m.contains("acacia") || m.contains("dark oak")
                || m.contains("dark_oak") || m.contains("cherry") || m.contains("mangrove")
                || m.contains("crimson") || m.contains("warped");
    }

    private static Material matchWood(String m) {
        if (m.contains("dark oak") || m.contains("dark_oak")) {
            return Material.DARK_OAK_LOG;
        }
        if (m.contains("spruce")) {
            return Material.SPRUCE_LOG;
        }
        if (m.contains("birch")) {
            return Material.BIRCH_LOG;
        }
        if (m.contains("jungle")) {
            return Material.JUNGLE_LOG;
        }
        if (m.contains("acacia")) {
            return Material.ACACIA_LOG;
        }
        if (m.contains("cherry")) {
            return Material.CHERRY_LOG;
        }
        if (m.contains("mangrove")) {
            return Material.MANGROVE_LOG;
        }
        if (m.contains("crimson")) {
            return Material.CRIMSON_STEM;
        }
        if (m.contains("warped")) {
            return Material.WARPED_STEM;
        }
        if (m.contains("oak") && !m.contains("dark")) {
            return Material.OAK_LOG;
        }
        if (m.contains("wood") || m.contains("log") || m.contains("timber")
                || m.contains("lumber") || m.contains("tree") || m.contains("chop")) {
            return Material.OAK_LOG; // marker for generic wood category handled above
        }
        return null;
    }

    private static Material matchPrefix(String m, String suffix) {
        for (Material mat : Material.values()) {
            if (!mat.name().endsWith(suffix)) {
                continue;
            }
            String prefix = mat.name().substring(0, mat.name().length() - suffix.length())
                    .toLowerCase(Locale.ROOT).replace('_', ' ');
            if (m.contains(prefix)) {
                return mat;
            }
        }
        return null;
    }

    /**
     * Survey area: nearest of each material family matching category / alternatives.
     */
    public static Map<Material, SurveyHit> survey(Location origin, OrderFocus focus, int radius) {
        Map<Material, SurveyHit> nearest = new LinkedHashMap<>();
        if (origin == null || origin.getWorld() == null) {
            return nearest;
        }
        World world = origin.getWorld();
        int r = Math.min(Math.max(radius, 16), 48);
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int yMin = -20;
        int yMax = 20;

        for (int x = -r; x <= r; x++) {
            for (int y = yMin; y <= yMax; y++) {
                for (int z = -r; z <= r; z++) {
                    int by = oy + y;
                    if (by < world.getMinHeight() || by >= world.getMaxHeight()) {
                        continue;
                    }
                    Block b = world.getBlockAt(ox + x, by, oz + z);
                    Material t = b.getType();
                    if (t.isAir() || !focus.sameCategory(t)) {
                        continue;
                    }
                    // Group deepslate iron with iron for display? keep separate but both show
                    double d = Math.sqrt(b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(origin));
                    SurveyHit prev = nearest.get(t);
                    if (prev == null || d < prev.distance) {
                        nearest.put(t, new SurveyHit(t, d, b));
                    }
                }
            }
        }
        return nearest;
    }

    public static PlanResult plan(JavaPlugin plugin, CrewBot bot, OrderFocus focus, Location origin) {
        List<String> msgs = new ArrayList<>();
        String name = bot.getName();
        int surveyR = plugin.getConfig().getInt("crew.survey-radius", 40);
        double warnDist = plugin.getConfig().getDouble("crew.far-resource-blocks", 28);

        Map<Material, SurveyHit> hits = survey(origin, focus, surveyR);
        List<SurveyHit> sorted = new ArrayList<>(hits.values());
        sorted.sort(Comparator.comparingDouble(h -> h.distance));

        // Generic order (e.g. "get wood") → true category only; ask type if several woods nearby
        if (!focus.isSpecific()) {
            List<SurveyHit> scan = sorted;
            if (scan.isEmpty()) {
                int farR = Math.min(Math.max(surveyR * 3, 96), 128);
                Map<Material, SurveyHit> farHits = survey(origin, focus, farR);
                scan = new ArrayList<>(farHits.values());
                scan.sort(Comparator.comparingDouble(h -> h.distance));
            }

            if (scan.isEmpty()) {
                msgs.add(ChatColor.GOLD + name + ChatColor.WHITE + ": I don't see any "
                        + focus.label() + " nearby. I'll keep searching as I walk — "
                        + "or set home nearer trees.");
                return new PlanResult(focus, true, msgs);
            }

            // Wood: if multiple species visible, ask single type vs any/mixed (unless force/any)
            if (focus.category() == OrderFocus.Category.WOOD && !focus.force()) {
                List<SpeciesHit> species = woodSpecies(scan);
                if (species.size() >= 2) {
                    msgs.add(ChatColor.GOLD + name + ChatColor.YELLOW
                            + ": I see several kinds of wood near me. Do you want "
                            + ChatColor.WHITE + "one type" + ChatColor.YELLOW + " or "
                            + ChatColor.WHITE + "any/mixed" + ChatColor.YELLOW + "?");
                    msgs.add(ChatColor.GRAY + "  Nearby:");
                    for (SpeciesHit s : species) {
                        if (s.rank >= 5) {
                            break;
                        }
                        msgs.add(ChatColor.WHITE + "  • " + s.name
                                + ChatColor.GRAY + " (nearest ~" + Math.round(s.distance) + " blocks) — "
                                + ChatColor.AQUA + "/crew assign " + name + " get " + s.name);
                    }
                    msgs.add(ChatColor.AQUA + "  • Any/mixed wood (fill bag, keep going): "
                            + "/crew assign " + name + " gather any wood");
                    msgs.add(ChatColor.DARK_GRAY + "  I'll wait — pick one option. "
                            + "/crew stop " + name + " to cancel.");
                    return new PlanResult(focus, false, msgs);
                }
            }

            SurveyHit n = scan.get(0);
            boolean far = n.distance > surveyR;
            msgs.add(ChatColor.GOLD + name + ChatColor.WHITE + ": On it — nearest "
                    + OrderFocus.friendly(n.material)
                    + (focus.category() == OrderFocus.Category.WOOD ? " (wood)" : "")
                    + " is about " + Math.round(n.distance) + " blocks"
                    + (far ? " (a bit of a walk)" : "") + ".");
            msgs.add(ChatColor.GRAY + "  I'll fill my bag, deposit, and keep going until "
                    + ChatColor.AQUA + "/crew stop " + name + ChatColor.GRAY + ".");
            if (scan.size() > 1 && focus.category() == OrderFocus.Category.WOOD) {
                msgs.add(ChatColor.DARK_GRAY + "  (Only one wood type nearby — taking that.)");
            }
            return new PlanResult(focus, true, msgs);
        }

        // Specific material
        SurveyHit wanted = nearestAccepting(sorted, focus);
        List<SurveyHit> alts = alternatives(sorted, focus);

        if (focus.force()) {
            if (wanted == null) {
                msgs.add(ChatColor.GOLD + name + ChatColor.WHITE + ": You said force it — "
                        + "I still don't see " + focus.label() + " nearby, but I'll search farther.");
            } else {
                msgs.add(ChatColor.GOLD + name + ChatColor.WHITE + ": Forcing "
                        + focus.label() + " (~" + Math.round(wanted.distance) + " blocks). Going.");
            }
            return new PlanResult(focus, true, msgs);
        }

        if (wanted == null) {
            msgs.add(ChatColor.GOLD + name + ChatColor.YELLOW + ": I can't find "
                    + ChatColor.WHITE + focus.label() + ChatColor.YELLOW
                    + " within ~" + surveyR + " blocks from here.");
            if (alts.isEmpty()) {
                msgs.add(ChatColor.GRAY + "  Nothing similar nearby either. Move me or "
                        + ChatColor.AQUA + "/crew assign " + name + " force " + shortCmd(focus));
            } else {
                msgs.add(ChatColor.GRAY + "  Nearby instead:");
                appendChoices(msgs, bot, alts, 5);
                msgs.add(ChatColor.GRAY + "  Or search farther: "
                        + ChatColor.AQUA + "/crew assign " + name + " force " + shortCmd(focus));
            }
            return new PlanResult(focus, false, msgs);
        }

        // Found specific — is it far compared to alternatives?
        boolean far = wanted.distance >= warnDist;
        SurveyHit nearestAlt = alts.isEmpty() ? null : alts.get(0);
        boolean altMuchCloser = nearestAlt != null
                && nearestAlt.distance + 8 < wanted.distance
                && !focus.accepts(nearestAlt.material);

        if (!far && !altMuchCloser) {
            msgs.add(ChatColor.GOLD + name + ChatColor.WHITE + ": "
                    + capitalize(focus.label()) + " is close (~"
                    + Math.round(wanted.distance) + " blocks). Heading there.");
            return new PlanResult(focus, true, msgs);
        }

        // Warn + choices
        msgs.add(ChatColor.GOLD + name + ChatColor.YELLOW + ": "
                + capitalize(focus.label()) + " is about "
                + ChatColor.WHITE + Math.round(wanted.distance) + " blocks"
                + ChatColor.YELLOW + " away — that'll take longer"
                + (origin.getWorld() != null && isLikelyDesert(origin) && isStoneish(focus)
                ? " (we're in sandy terrain)" : "") + ".");

        if (!alts.isEmpty()) {
            msgs.add(ChatColor.GRAY + "  Closer options near me:");
            appendChoices(msgs, bot, alts, 5);
        }
        msgs.add(ChatColor.GRAY + "  Choices:");
        msgs.add(ChatColor.AQUA + "  • Proceed anyway: /crew assign " + name + " force " + shortCmd(focus));
        if (nearestAlt != null) {
            msgs.add(ChatColor.AQUA + "  • Switch to nearest: /crew assign " + name + " get "
                    + OrderFocus.friendly(nearestAlt.material));
        }
        msgs.add(ChatColor.AQUA + "  • Cancel: /crew stop " + name);
        return new PlanResult(focus, false, msgs);
    }

    private static void appendChoices(List<String> msgs, CrewBot bot, List<SurveyHit> alts, int max) {
        int n = 0;
        for (SurveyHit h : alts) {
            if (n >= max) {
                break;
            }
            msgs.add(ChatColor.WHITE + "  • " + OrderFocus.friendly(h.material)
                    + ChatColor.GRAY + " ~" + Math.round(h.distance) + " blocks — "
                    + ChatColor.AQUA + "/crew assign " + bot.getName() + " get "
                    + OrderFocus.friendly(h.material));
            n++;
        }
    }

    private static SurveyHit nearestAccepting(List<SurveyHit> sorted, OrderFocus focus) {
        for (SurveyHit h : sorted) {
            if (focus.accepts(h.material)) {
                return h;
            }
        }
        return null;
    }

    private static List<SurveyHit> alternatives(List<SurveyHit> sorted, OrderFocus focus) {
        List<SurveyHit> alts = new ArrayList<>();
        for (SurveyHit h : sorted) {
            if (!focus.accepts(h.material)) {
                alts.add(h);
            }
        }
        // Also collapse deepslate iron etc — already separate
        return alts;
    }

    private static String shortCmd(OrderFocus focus) {
        if (focus.specific() != null) {
            return OrderFocus.friendly(focus.specific());
        }
        return focus.label();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Group log/leaf materials into species for choice prompts. */
    private static final class SpeciesHit {
        final String name;
        final double distance;
        final int rank;

        SpeciesHit(String name, double distance, int rank) {
            this.name = name;
            this.distance = distance;
            this.rank = rank;
        }
    }

    private static List<SpeciesHit> woodSpecies(List<SurveyHit> hits) {
        Map<String, Double> best = new LinkedHashMap<>();
        for (SurveyHit h : hits) {
            String sp = woodSpeciesName(h.material);
            if (sp == null) {
                continue;
            }
            best.merge(sp, h.distance, Math::min);
        }
        List<SpeciesHit> list = new ArrayList<>();
        int rank = 0;
        List<Map.Entry<String, Double>> entries = new ArrayList<>(best.entrySet());
        entries.sort(Comparator.comparingDouble(Map.Entry::getValue));
        for (Map.Entry<String, Double> e : entries) {
            list.add(new SpeciesHit(e.getKey(), e.getValue(), rank++));
        }
        return list;
    }

    private static String woodSpeciesName(Material m) {
        if (m == null) {
            return null;
        }
        String n = m.name();
        if (n.contains("KELP")) {
            return null;
        }
        if (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_LEAVES")) {
            String base = n;
            if (base.startsWith("STRIPPED_")) {
                base = base.substring("STRIPPED_".length());
            }
            if (base.endsWith("_LOG")) {
                base = base.substring(0, base.length() - 4);
            } else if (base.endsWith("_WOOD")) {
                base = base.substring(0, base.length() - 5);
            } else if (base.endsWith("_LEAVES")) {
                base = base.substring(0, base.length() - 7);
            }
            return base.toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        if (n.contains("CRIMSON")) {
            return "crimson";
        }
        if (n.contains("WARPED")) {
            return "warped";
        }
        return null;
    }

    private static boolean isStoneish(OrderFocus focus) {
        if (focus.specific() == null) {
            return focus.category() == OrderFocus.Category.STONE
                    || focus.category() == OrderFocus.Category.PICKAXE
                    || focus.category() == OrderFocus.Category.ORE;
        }
        String n = focus.specific().name();
        return n.contains("STONE") || n.contains("COBBLE") || n.endsWith("_ORE")
                || n.equals("NETHERRACK") || n.contains("DEEPSLATE");
    }

    private static boolean isLikelyDesert(Location loc) {
        try {
            String b = loc.getBlock().getBiome().getKey().getKey();
            return b.contains("desert") || b.contains("badlands");
        } catch (Throwable t) {
            Material ground = loc.getWorld().getHighestBlockAt(loc).getType();
            return ground == Material.SAND || ground == Material.RED_SAND
                    || ground == Material.SANDSTONE;
        }
    }

    public static void sendAsBot(Player player, CrewBot bot, List<String> messages) {
        if (player == null || messages == null) {
            return;
        }
        for (String m : messages) {
            player.sendMessage(m);
        }
    }
}
