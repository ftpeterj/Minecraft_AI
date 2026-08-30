package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.CrewBot;
import com.aibots.learn.LearningService;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcLocations;
import com.aibots.npc.NpcService;
import com.aibots.storage.ChestNetwork;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fishing: find water, "cast" (a randomized wait like a real bite timer), then
 * reel in a real vanilla catch generated from the game's own fishing loot
 * table — no player-triggered PlayerFishEvent needed since the bot isn't a
 * real client.
 */
public final class FishingSkill {

    private static final NamespacedKey FISHING_TABLE = NamespacedKey.minecraft("gameplay/fishing");
    private static final Random RANDOM = new Random();

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private final ChestNetwork chests;
    private final LearningService learning;
    private final Map<UUID, FishState> stateByBot = new ConcurrentHashMap<>();

    private static final class FishState {
        Location water;
        int ticksUntilBite;
    }

    public FishingSkill(JavaPlugin plugin, NpcService npcService, ChestNetwork chests, LearningService learning) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.chests = chests;
        this.learning = learning;
    }

    public void clear(CrewBot bot) {
        stateByBot.remove(bot.getId());
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

        String order = bot.getCurrentOrder();
        if (order == null || !looksLikeFish(order)) {
            return;
        }
        bot.setStatus(BotStatus.BUSY);

        Location home = bot.getHome() != null ? bot.getHome() : loc;
        int radius = plugin.getConfig().getInt("titles.gatherer.fish-radius", 20);
        int depositThreshold = plugin.getConfig().getInt("titles.gatherer.deposit-threshold", 64);

        if (bot.getLoot().shouldDeposit(depositThreshold)) {
            deposit(bot, body, loc, home);
            return;
        }

        FishState state = stateByBot.computeIfAbsent(bot.getId(), id -> new FishState());

        if (state.water == null || state.water.getBlock().getType() != Material.WATER) {
            Block water = findWater(loc, radius);
            if (water == null) {
                if (loc.distanceSquared(home) > 64) {
                    body.walkTo(home, 1.0);
                }
                return;
            }
            state.water = water.getLocation();
            state.ticksUntilBite = 0;
        }

        Location shore = NpcLocations.findDryStandNear(state.water, 4);
        if (shore == null) {
            // No dry shore near this spot — abandon it and try another next tick
            state.water = null;
            return;
        }
        if (loc.distanceSquared(shore) > 4.0) {
            body.walkTo(shore, 1.0);
            return;
        }

        body.stopWalking();
        body.lookAt(state.water.clone().add(0.5, 0, 0.5));

        if (state.ticksUntilBite <= 0) {
            // Start a cast — vanilla bite times run roughly 5-30s; crew ticks are
            // ~1/sec (crew.tick-interval), so this counts down in crew ticks.
            state.ticksUntilBite = 5 + RANDOM.nextInt(26);
            return;
        }
        state.ticksUntilBite--;
        if (state.ticksUntilBite > 0) {
            return;
        }

        // Bite! Reel in a real vanilla catch.
        body.swingMainHand();
        int added = 0;
        for (ItemStack item : rollCatch(state.water)) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemStack left = bot.getLoot().add(item);
            added += item.getAmount() - (left == null ? 0 : left.getAmount());
        }
        bot.remember("Caught something fishing");
        learning.observe(bot, "fish", "Fishing catch (" + added + " items)", true, null);
        // Re-cast the same spot next tick rather than immediately re-searching.
        state.ticksUntilBite = 0;
    }

    private Collection<ItemStack> rollCatch(Location water) {
        LootTable table = Bukkit.getLootTable(FISHING_TABLE);
        if (table == null) {
            return List.of(new ItemStack(Material.COD));
        }
        try {
            LootContext context = new LootContext.Builder(water).build();
            return table.populateLoot(RANDOM, context);
        } catch (Throwable t) {
            return List.of(new ItemStack(Material.COD));
        }
    }

    private Block findWater(Location origin, int radius) {
        int r = Math.min(Math.max(6, radius), 48);
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) {
            for (int y = -3; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    int by = oy + y;
                    if (by < world.getMinHeight() || by >= world.getMaxHeight()) {
                        continue;
                    }
                    Block b = world.getBlockAt(ox + x, by, oz + z);
                    if (b.getType() != Material.WATER) {
                        continue;
                    }
                    Block above = b.getRelative(0, 1, 0);
                    if (!above.getType().isAir()) {
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

    private void deposit(CrewBot bot, NpcHandle body, Location loc, Location home) {
        chests.ensureStorageNear(home);
        Location chest = chests.nearestChestWithSpace(home);
        if (chest == null) {
            chest = chests.nearestChest(home);
        }
        if (chest == null) {
            return;
        }
        Location approach = chest.clone().add(0.5, 0, 0.5);
        if (loc.distanceSquared(approach) > 6) {
            body.walkTo(approach, 1.0);
            return;
        }
        body.stopWalking();
        int moved = 0;
        for (int i = 0; i < bot.getLoot().getInventory().getSize(); i++) {
            ItemStack s = bot.getLoot().getInventory().getItem(i);
            if (s == null || s.getType().isAir()) {
                continue;
            }
            int before = s.getAmount();
            int left = chests.depositStack(s);
            moved += before - left;
            if (left <= 0) {
                bot.getLoot().getInventory().setItem(i, null);
            } else {
                s.setAmount(left);
                bot.getLoot().getInventory().setItem(i, s);
            }
        }
        if (moved > 0) {
            learning.observe(bot, "deposit", "Fishing deposited " + moved, true, null);
            bot.setStatus(BotStatus.BUSY);
        }
    }

    public static boolean looksLikeFish(String order) {
        if (order == null) {
            return false;
        }
        String l = order.toLowerCase(Locale.ROOT);
        return l.contains("fish") || l.contains("catch dinner") || l.contains("catch some food");
    }
}
