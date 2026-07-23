package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.CrewBot;
import com.aibots.learn.LearningService;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcService;
import com.aibots.npc.VillagerHandle;
import com.aibots.storage.ChestNetwork;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Farmer: harvest mature crops and replant seeds from drops/loot.
 */
public final class FarmerSkill {

    private static final Map<Material, Material> SEED_FOR_CROP = new HashMap<>();

    static {
        SEED_FOR_CROP.put(Material.WHEAT, Material.WHEAT_SEEDS);
        SEED_FOR_CROP.put(Material.CARROTS, Material.CARROT);
        SEED_FOR_CROP.put(Material.POTATOES, Material.POTATO);
        SEED_FOR_CROP.put(Material.BEETROOTS, Material.BEETROOT_SEEDS);
        SEED_FOR_CROP.put(Material.NETHER_WART, Material.NETHER_WART);
        SEED_FOR_CROP.put(Material.COCOA, Material.COCOA_BEANS);
        try {
            SEED_FOR_CROP.put(Material.TORCHFLOWER_CROP, Material.TORCHFLOWER_SEEDS);
        } catch (Throwable ignored) {
        }
    }

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private final ChestNetwork chests;
    private final LearningService learning;

    public FarmerSkill(JavaPlugin plugin, NpcService npcService, ChestNetwork chests, LearningService learning) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.chests = chests;
        this.learning = learning;
    }

    public void tick(CrewBot bot) {
        if (bot.getTitle() == null || !bot.getTitle().isFarmer()) {
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

        String order = bot.getCurrentOrder();
        boolean ordered = order != null && looksLikeFarm(order);
        boolean auto = plugin.getConfig().getBoolean("titles.farmer.auto-when-idle", false);
        if (!ordered && !(auto && bot.getStatus() == BotStatus.IDLE)) {
            return;
        }
        if (ordered) {
            bot.setStatus(BotStatus.BUSY);
        }

        Location home = bot.getHome() != null ? bot.getHome() : loc;
        int radius = plugin.getConfig().getInt("titles.farmer.farm-radius", 20);

        if (bot.getLoot().getInventory().firstEmpty() == -1) {
            deposit(bot, body, loc, home);
            return;
        }

        Block crop = findMatureCrop(loc, radius);
        if (crop == null) {
            // also plant on empty farmland if we have seeds
            Block soil = findEmptyFarmland(loc, radius);
            if (soil != null) {
                Material seed = firstSeedInLoot(bot);
                if (seed != null) {
                    Location approach = soil.getLocation().add(0.5, 1, 0.5);
                    if (loc.distanceSquared(approach) > 6) {
                        body.walkTo(approach, 1.0);
                        return;
                    }
                    body.stopWalking();
                    plantOn(soil, seed, bot);
                    return;
                }
            }
            if (loc.distanceSquared(home) > 36) {
                body.walkTo(home, 1.0);
            }
            return;
        }

        Location approach = crop.getLocation().add(0.5, 0, 0.5);
        if (loc.distanceSquared(approach) > 6.25) {
            body.walkTo(approach, 1.05);
            return;
        }

        body.stopWalking();
        if (body.getEntity() instanceof LivingEntity living) {
            living.swingMainHand();
        }
        if (body instanceof VillagerHandle vh) {
            vh.lookAt(crop.getLocation().add(0.5, 0.5, 0.5));
        }

        Material type = crop.getType();
        Material seed = SEED_FOR_CROP.getOrDefault(type, null);
        Collection<ItemStack> drops = crop.getDrops(new ItemStack(Material.IRON_HOE));
        crop.setType(Material.AIR);

        // farmland usually remains under wheat etc.
        Block below = crop.getRelative(0, -1, 0);
        if (below.getType() == Material.FARMLAND || below.getType() == Material.SOUL_SAND) {
            // replant if we have seed (from drops or loot)
            if (seed != null) {
                boolean haveSeed = bot.getLoot().count(seed) > 0;
                if (!haveSeed) {
                    for (ItemStack d : drops) {
                        if (d.getType() == seed) {
                            haveSeed = true;
                            break;
                        }
                    }
                }
                if (haveSeed) {
                    // consume one seed from drops first
                    boolean planted = false;
                    for (ItemStack d : drops) {
                        if (d.getType() == seed && d.getAmount() > 0) {
                            d.setAmount(d.getAmount() - 1);
                            planted = true;
                            break;
                        }
                    }
                    if (!planted) {
                        bot.getLoot().remove(seed, 1);
                    }
                    crop.setType(type);
                    BlockData data = crop.getBlockData();
                    if (data instanceof Ageable age) {
                        age.setAge(0);
                        crop.setBlockData(age);
                    }
                }
            }
        }

        for (ItemStack d : drops) {
            if (d == null || d.getType().isAir() || d.getAmount() <= 0) {
                continue;
            }
            ItemStack left = bot.getLoot().add(d);
            if (left != null) {
                crop.getWorld().dropItemNaturally(crop.getLocation(), left);
            }
        }
        bot.remember("Harvested " + type.name());
        learning.observe(bot, "farm", "Harvested " + type.name(), true, null);
    }

    private void plantOn(Block soil, Material seed, CrewBot bot) {
        Material crop = cropForSeed(seed);
        if (crop == null) {
            return;
        }
        Block above = soil.getRelative(0, 1, 0);
        if (!above.getType().isAir()) {
            return;
        }
        bot.getLoot().remove(seed, 1);
        above.setType(crop);
        BlockData data = above.getBlockData();
        if (data instanceof Ageable age) {
            age.setAge(0);
            above.setBlockData(age);
        }
        learning.observe(bot, "farm", "Planted " + crop.name(), true, null);
    }

    private Material firstSeedInLoot(CrewBot bot) {
        for (Material seed : SEED_FOR_CROP.values()) {
            if (bot.getLoot().count(seed) > 0) {
                return seed;
            }
        }
        if (bot.getLoot().count(Material.WHEAT_SEEDS) > 0) {
            return Material.WHEAT_SEEDS;
        }
        return null;
    }

    private static Material cropForSeed(Material seed) {
        for (Map.Entry<Material, Material> e : SEED_FOR_CROP.entrySet()) {
            if (e.getValue() == seed) {
                return e.getKey();
            }
        }
        if (seed == Material.WHEAT_SEEDS) {
            return Material.WHEAT;
        }
        return null;
    }

    private Block findMatureCrop(Location origin, int radius) {
        int r = Math.min(radius, 24);
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    Block b = origin.getWorld().getBlockAt(ox + x, oy + y, oz + z);
                    if (!isMatureCrop(b)) {
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

    private Block findEmptyFarmland(Location origin, int radius) {
        int r = Math.min(radius, 16);
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        for (int x = -r; x <= r; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    Block soil = origin.getWorld().getBlockAt(ox + x, oy + y, oz + z);
                    if (soil.getType() != Material.FARMLAND && soil.getType() != Material.SOUL_SAND) {
                        continue;
                    }
                    Block above = soil.getRelative(0, 1, 0);
                    if (above.getType().isAir()) {
                        return soil;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isMatureCrop(Block b) {
        Material t = b.getType();
        if (!SEED_FOR_CROP.containsKey(t) && t != Material.WHEAT && t != Material.CARROTS
                && t != Material.POTATOES && t != Material.BEETROOTS && t != Material.NETHER_WART
                && t != Material.SWEET_BERRY_BUSH && t != Material.MELON && t != Material.PUMPKIN) {
            return false;
        }
        BlockData data = b.getBlockData();
        if (data instanceof Ageable age) {
            return age.getAge() >= age.getMaximumAge();
        }
        // melon/pumpkin fully grown as block
        return t == Material.MELON || t == Material.PUMPKIN;
    }

    private void deposit(CrewBot bot, NpcHandle body, Location loc, Location home) {
        chests.ensureStorageNear(home);
        Location chest = chests.nearestChestWithSpace(loc);
        if (chest == null) {
            chest = chests.nearestChest(loc);
        }
        if (chest == null) {
            return;
        }
        if (loc.distanceSquared(chest.clone().add(0.5, 0, 0.5)) > 6) {
            body.walkTo(chest.clone().add(0.5, 0, 0.5), 1.0);
            return;
        }
        body.stopWalking();
        for (int i = 0; i < bot.getLoot().getInventory().getSize(); i++) {
            ItemStack s = bot.getLoot().getInventory().getItem(i);
            if (s == null || s.getType().isAir()) {
                continue;
            }
            // keep some seeds
            if (SEED_FOR_CROP.containsValue(s.getType()) && bot.getLoot().count(s.getType()) <= 16) {
                continue;
            }
            int left = chests.depositStack(s);
            if (left <= 0) {
                bot.getLoot().getInventory().setItem(i, null);
            } else {
                s.setAmount(left);
                bot.getLoot().getInventory().setItem(i, s);
            }
        }
        bot.setStatus(BotStatus.BUSY);
    }

    public static boolean looksLikeFarm(String order) {
        if (order == null) {
            return false;
        }
        String l = order.toLowerCase();
        return l.contains("farm") || l.contains("harvest") || l.contains("crop")
                || l.contains("plant") || l.contains("wheat") || l.contains("carrot")
                || l.contains("potato") || l.contains("replant") || l.contains("field");
    }
}
