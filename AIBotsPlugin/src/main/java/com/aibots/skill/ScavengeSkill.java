package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import com.aibots.learn.LearningService;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcService;
import com.aibots.storage.ChestNetwork;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 2 scavenger: gather nearby valued blocks, deposit into chest network, expand when full.
 */
public class ScavengeSkill {

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private final ChestNetwork chests;
    private final LearningService learning;
    private final Map<UUID, Integer> carryCount = new ConcurrentHashMap<>();
    private final Map<UUID, Material> focusMaterial = new ConcurrentHashMap<>();

    private static final Set<Material> DEFAULT_VALUED = EnumSet.of(
            Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG, Material.DARK_OAK_LOG,
            Material.ACACIA_LOG, Material.CHERRY_LOG, Material.MANGROVE_LOG, Material.JUNGLE_LOG,
            Material.COBBLESTONE, Material.STONE, Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.COPPER_ORE,
            Material.DEEPSLATE_COPPER_ORE, Material.SAND, Material.GRAVEL, Material.CLAY,
            Material.OAK_LEAVES, Material.WHEAT, Material.CARROTS, Material.POTATOES
    );

    public ScavengeSkill(JavaPlugin plugin, NpcService npcService, ChestNetwork chests, LearningService learning) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.chests = chests;
        this.learning = learning;
    }

    public void parseOrder(CrewBot bot, String order) {
        if (order == null) {
            focusMaterial.remove(bot.getId());
            return;
        }
        String lower = order.toLowerCase(Locale.ROOT);
        Material found = null;
        for (Material m : Material.values()) {
            if (!m.isBlock() && !m.isItem()) {
                continue;
            }
            String key = m.name().toLowerCase(Locale.ROOT);
            String spaced = key.replace('_', ' ');
            if (lower.contains(key) || lower.contains(spaced)) {
                found = m;
                break;
            }
            // common aliases
            if (lower.contains("wood") || lower.contains("log")) {
                found = Material.OAK_LOG;
                break;
            }
            if (lower.contains("cobble")) {
                found = Material.COBBLESTONE;
                break;
            }
            if (lower.contains("iron")) {
                found = Material.IRON_ORE;
                break;
            }
        }
        if (found != null) {
            focusMaterial.put(bot.getId(), found);
            learning.observe(bot, "plan", "Focus gather " + found.name(), true, order);
        }
        if (lower.contains("gather") || lower.contains("scavenge") || lower.contains("collect")
                || lower.contains("mine") || lower.contains("everything") || lower.contains("fill")) {
            bot.setStatus(BotStatus.BUSY);
        }
    }

    public void tick(CrewBot bot) {
        if (bot.getTitle() != BotTitle.SCAVENGER) {
            return;
        }
        if (bot.getStatus() == BotStatus.STOPPED || bot.getStatus() == BotStatus.DISMISSED) {
            return;
        }

        NpcHandle body = npcService.get(bot.getId());
        if (body == null || !body.isValid()) {
            return;
        }
        Location loc = body.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }

        // Ensure storage near home
        Location home = bot.getHome() != null ? bot.getHome() : loc;
        chests.ensureStorageNear(home);

        int carried = carryCount.getOrDefault(bot.getId(), 0);
        int depositThreshold = plugin.getConfig().getInt("titles.scavenger.deposit-threshold", 8);
        int radius = plugin.getConfig().getInt("titles.scavenger.gather-radius", 24);

        // Deposit if carrying enough or inventory-like threshold
        if (carried >= depositThreshold) {
            deposit(bot, body, loc);
            return;
        }

        // Only actively gather when BUSY or has gather-like order, or IDLE auto-scavenge
        boolean auto = plugin.getConfig().getBoolean("titles.scavenger.auto-when-idle", true);
        String order = bot.getCurrentOrder();
        boolean shouldGather = bot.getStatus() == BotStatus.BUSY
                || (order != null && looksLikeGather(order))
                || (auto && bot.getStatus() == BotStatus.IDLE);

        if (!shouldGather) {
            return;
        }

        Material focus = focusMaterial.get(bot.getId());
        Block target = findTarget(loc, radius, focus);
        if (target == null) {
            // learned: this area is sparse
            learning.observe(bot, "scavenge", "No valued blocks within " + radius, false, locBlockKey(loc));
            // walk toward home / random step
            stepToward(body, home);
            return;
        }

        // Move toward target then break
        Location tloc = target.getLocation().add(0.5, 0, 0.5);
        double dist = loc.distanceSquared(tloc);
        if (dist > 4.0) {
            stepToward(body, tloc);
            return;
        }

        Material type = target.getType();
        // Don't break chests or bedrock etc.
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL
                || type == Material.BEDROCK || type == Material.SPAWNER) {
            return;
        }

        target.breakNaturally();
        carryCount.put(bot.getId(), carried + 1);
        bot.setStatus(BotStatus.BUSY);
        bot.remember("Gathered " + type.name());
        learning.observe(bot, "scavenge", "Gathered " + type.name(), true, locBlockKey(tloc));

        // Learn valuable materials near home
        if (carried + 1 >= 3) {
            learning.teach(bot,
                    "Good resource near base: " + type.name(),
                    "experience",
                    true);
        }

        // Occasionally pick up nearby ground items into "carry" abstraction
        // (real item entities — deposit still uses chest expansion via virtual carry count;
        //  also try to suck items into a fake transfer by depositing natural drops next tick)
    }

    private void deposit(CrewBot bot, NpcHandle body, Location loc) {
        Location chestLoc = chests.nearestChest(loc);
        if (chestLoc == null) {
            chestLoc = chests.ensureStorageNear(bot.getHome() != null ? bot.getHome() : loc);
        }
        if (chestLoc == null) {
            learning.observe(bot, "deposit", "No chest available", false, null);
            return;
        }
        if (loc.distanceSquared(chestLoc.clone().add(0.5, 0, 0.5)) > 6.0) {
            stepToward(body, chestLoc.clone().add(0.5, 0, 0.5));
            return;
        }

        // Convert carried count into deposited items of focus or cobble/log
        Material mat = focusMaterial.getOrDefault(bot.getId(), Material.OAK_LOG);
        int carried = carryCount.getOrDefault(bot.getId(), 0);
        if (carried <= 0) {
            return;
        }
        ItemStack stack = new ItemStack(mat, Math.min(carried, 64));
        int left = chests.depositStack(stack);
        int deposited = stack.getAmount() - left;
        if (deposited > 0) {
            carryCount.put(bot.getId(), Math.max(0, carried - deposited));
            bot.remember("Deposited " + deposited + "x " + mat.name());
            learning.observe(bot, "deposit", "Deposited " + deposited + " " + mat.name(), true, null);
            learning.teach(bot, "Storage network accepts " + mat.name(), "experience", true);
            if (left > 0) {
                // expand attempted inside depositStack
                learning.observe(bot, "expand_chest", "Network was full; expand attempted", left < stack.getAmount(), null);
            }
        } else {
            boolean expanded = chests.expandChest(chestLoc);
            learning.observe(bot, "expand_chest", expanded ? "Placed new chest" : "Could not expand", expanded, null);
            if (expanded) {
                learning.teach(bot, "When chests are full, place another chest beside the network", "experience", true);
            }
        }

        if (carryCount.getOrDefault(bot.getId(), 0) == 0) {
            bot.setStatus(BotStatus.IDLE);
        }
    }

    private Block findTarget(Location origin, int radius, Material focus) {
        List<Material> valued = loadValued();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        int r = Math.min(radius, 32);
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        WorldSafe world = new WorldSafe(origin);

        for (int x = -r; x <= r; x++) {
            for (int y = -8; y <= 8; y++) {
                for (int z = -r; z <= r; z++) {
                    Block b = world.block(ox + x, oy + y, oz + z);
                    if (b == null) {
                        continue;
                    }
                    Material t = b.getType();
                    if (t.isAir()) {
                        continue;
                    }
                    boolean match = focus != null ? t == focus : valued.contains(t);
                    // log family if focus oak_log
                    if (!match && focus != null && focus.name().endsWith("_LOG") && t.name().endsWith("_LOG")) {
                        match = true;
                    }
                    if (!match) {
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

    private List<Material> loadValued() {
        List<String> names = plugin.getConfig().getStringList("titles.scavenger.valued-materials");
        if (names == null || names.isEmpty()) {
            return new ArrayList<>(DEFAULT_VALUED);
        }
        List<Material> list = new ArrayList<>();
        for (String n : names) {
            try {
                list.add(Material.valueOf(n.toUpperCase(Locale.ROOT)));
            } catch (Exception ignored) {
            }
        }
        return list.isEmpty() ? new ArrayList<>(DEFAULT_VALUED) : list;
    }

    private void stepToward(NpcHandle body, Location target) {
        Location from = body.getLocation();
        if (from == null || target == null) {
            return;
        }
        Location next = from.clone();
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.2) {
            return;
        }
        double step = Math.min(1.2, dist);
        next.add(dx / dist * step, 0, dz / dist * step);
        // keep on ground
        next.setY(from.getWorld().getHighestBlockYAt(next) + 1);
        // face target
        next.setDirection(target.toVector().subtract(from.toVector()));
        body.teleport(next);

        // mild animation: swing if living
        Entity e = body.getEntity();
        if (e instanceof LivingEntity living) {
            living.swingMainHand();
        }
    }

    private static boolean looksLikeGather(String order) {
        String l = order.toLowerCase(Locale.ROOT);
        return l.contains("gather") || l.contains("scavenge") || l.contains("collect")
                || l.contains("mine") || l.contains("wood") || l.contains("fill")
                || l.contains("loot") || l.contains("everything");
    }

    private static String locBlockKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private static final class WorldSafe {
        private final org.bukkit.World world;

        WorldSafe(Location origin) {
            this.world = origin.getWorld();
        }

        Block block(int x, int y, int z) {
            if (world == null) {
                return null;
            }
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                return null;
            }
            return world.getBlockAt(x, y, z);
        }
    }
}
