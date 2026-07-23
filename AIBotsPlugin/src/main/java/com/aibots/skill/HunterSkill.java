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
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Hunter: hunt wild animals for food/drops, deposit when bag full.
 */
public final class HunterSkill {

    private static final Set<String> PREY = Set.of(
            "COW", "PIG", "SHEEP", "CHICKEN", "RABBIT", "GOAT", "MOOSHROOM",
            "COD", "SALMON", "SQUID", "GLOW_SQUID", "TURTLE", "FROG", "CAMEL",
            "HORSE", "DONKEY", "MULE", "LLAMA", "FOX", "PANDA", "HOGLIN"
    );

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private final ChestNetwork chests;
    private final LearningService learning;

    public HunterSkill(JavaPlugin plugin, NpcService npcService, ChestNetwork chests, LearningService learning) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.chests = chests;
        this.learning = learning;
    }

    public void tick(CrewBot bot) {
        if (bot.getTitle() == null || !bot.getTitle().isHunter()) {
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
        boolean ordered = order != null && looksLikeHunt(order);
        boolean auto = plugin.getConfig().getBoolean("titles.hunter.auto-when-idle", false);
        if (!ordered && !(auto && bot.getStatus() == BotStatus.IDLE)) {
            return;
        }
        if (ordered) {
            bot.setStatus(BotStatus.BUSY);
        }

        Location home = bot.getHome() != null ? bot.getHome() : loc;
        double range = plugin.getConfig().getDouble("titles.hunter.hunt-radius", 24);
        double damage = plugin.getConfig().getDouble("titles.hunter.attack-damage", 5.0);

        // Deposit when bag full
        if (bot.getLoot().getInventory().firstEmpty() == -1) {
            deposit(bot, body, loc, home);
            return;
        }

        LivingEntity prey = findPrey(loc, range);
        if (prey == null) {
            // return toward home / wander slightly
            if (loc.distanceSquared(home) > 64) {
                body.walkTo(home, 1.0);
            }
            return;
        }

        Location tloc = prey.getLocation();
        if (loc.distanceSquared(tloc) > 6.25) {
            body.walkTo(tloc, 1.15);
            return;
        }

        body.stopWalking();
        Entity ent = body.getEntity();
        if (ent instanceof LivingEntity living) {
            living.swingMainHand();
            if (body instanceof VillagerHandle vh) {
                vh.lookAt(tloc.clone().add(0, 0.5, 0));
            } else if (living instanceof Mob mob) {
                try {
                    mob.lookAt(prey);
                } catch (Throwable ignored) {
                }
            }
            try {
                prey.damage(damage, living);
            } catch (Throwable t) {
                prey.damage(damage);
            }
            // Collect nearby drops after hit
            collectNearbyDrops(bot, loc, 3.5);
            if (prey.isDead()) {
                bot.remember("Hunted " + prey.getType().name());
                learning.observe(bot, "hunt", "Killed " + prey.getType().name(), true, null);
                collectNearbyDrops(bot, loc, 4.0);
            }
        }
    }

    private void collectNearbyDrops(CrewBot bot, Location loc, double r) {
        for (Entity e : loc.getWorld().getNearbyEntities(loc, r, r, r)) {
            if (e instanceof org.bukkit.entity.Item item) {
                ItemStack stack = item.getItemStack();
                ItemStack left = bot.getLoot().add(stack);
                if (left == null) {
                    item.remove();
                } else if (left.getAmount() < stack.getAmount()) {
                    item.setItemStack(left);
                }
            }
        }
    }

    private LivingEntity findPrey(Location from, double range) {
        LivingEntity best = null;
        double bestD = range * range;
        for (Entity e : from.getWorld().getNearbyEntities(from, range, range / 2, range)) {
            if (!(e instanceof LivingEntity living) || living.isDead()) {
                continue;
            }
            if (living instanceof Player) {
                continue;
            }
            if (living instanceof Tameable t && t.isTamed()) {
                continue;
            }
            String type = living.getType().name();
            boolean prey = PREY.contains(type) || living instanceof Animals;
            if (!prey) {
                continue;
            }
            // Skip named pets
            if (living.getCustomName() != null && !living.getCustomName().isBlank()) {
                continue;
            }
            double d = living.getLocation().distanceSquared(from);
            if (d < bestD) {
                bestD = d;
                best = living;
            }
        }
        return best;
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
            learning.observe(bot, "deposit", "Hunter deposited " + moved, true, null);
            bot.setStatus(BotStatus.BUSY);
        }
    }

    public static boolean looksLikeHunt(String order) {
        if (order == null) {
            return false;
        }
        String l = order.toLowerCase();
        return l.contains("hunt") || l.contains("prey") || l.contains("meat")
                || l.contains("animal") || l.contains("kill cow") || l.contains("food")
                || l.contains("slaughter") || l.contains("game");
    }
}
