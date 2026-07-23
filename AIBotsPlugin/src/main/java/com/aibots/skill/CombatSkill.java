package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import com.aibots.learn.LearningService;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcService;
import com.aibots.npc.VillagerHandle;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Warrior / Protector: patrol near home or owner, engage hostile mobs.
 */
public final class CombatSkill {

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private final LearningService learning;

    public CombatSkill(JavaPlugin plugin, NpcService npcService, LearningService learning) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.learning = learning;
    }

    public void tick(CrewBot bot) {
        if (bot.getTitle() == null || !bot.getTitle().isCombat()) {
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
        boolean ordered = order != null && looksLikeCombat(order);
        boolean auto = plugin.getConfig().getBoolean("titles." + bot.getTitle().name().toLowerCase() + ".auto-when-idle",
                plugin.getConfig().getBoolean("titles.protector.auto-when-idle", true));
        if (!ordered && !(auto && bot.getStatus() != BotStatus.STOPPED)) {
            // Protectors default to auto-patrol when idle
            if (bot.getTitle() == BotTitle.PROTECTOR || bot.getTitle() == BotTitle.WARRIOR) {
                if (bot.getStatus() == BotStatus.IDLE || bot.getStatus() == BotStatus.BUSY) {
                    // fall through with auto
                } else {
                    return;
                }
            } else {
                return;
            }
        }
        if (ordered) {
            bot.setStatus(BotStatus.BUSY);
        }

        String tkey = bot.getTitle().name().toLowerCase();
        double range = plugin.getConfig().getDouble("titles." + tkey + ".engage-radius",
                plugin.getConfig().getDouble("titles.protector.engage-radius", 16));
        double damage = plugin.getConfig().getDouble("titles." + tkey + ".attack-damage",
                plugin.getConfig().getDouble("titles.protector.attack-damage", 4.0));
        boolean guardOwner = plugin.getConfig().getBoolean("titles." + tkey + ".guard-owner",
                plugin.getConfig().getBoolean("titles.protector.guard-owner", true));

        Location anchor = bot.getHome() != null ? bot.getHome() : loc;
        Player owner = bot.getOwnerPlayer();
        if (guardOwner && owner != null && owner.isOnline()
                && owner.getWorld().equals(loc.getWorld())) {
            anchor = owner.getLocation();
        }

        LivingEntity target = findHostile(loc, range, owner);
        if (target == null) {
            // Patrol: stay near anchor
            if (loc.distanceSquared(anchor) > 25) {
                body.walkTo(anchor, 1.05);
            } else if (Math.random() < 0.15) {
                // small wander
                Location wander = anchor.clone().add(
                        (Math.random() - 0.5) * 6,
                        0,
                        (Math.random() - 0.5) * 6);
                body.walkTo(wander, 0.9);
            }
            return;
        }

        Location tloc = target.getLocation();
        double dist = loc.distanceSquared(tloc);
        if (dist > 6.25) {
            body.walkTo(tloc, 1.2);
            return;
        }

        body.stopWalking();
        Entity ent = body.getEntity();
        if (ent instanceof LivingEntity living) {
            living.swingMainHand();
            if (living instanceof Mob mob) {
                try {
                    mob.lookAt(target);
                } catch (Throwable ignored) {
                }
            } else if (body instanceof VillagerHandle vh) {
                vh.lookAt(tloc.clone().add(0, 1, 0));
            }
            // Melee hit
            try {
                target.damage(damage, living);
            } catch (Throwable t) {
                target.damage(damage);
            }
            // Face-knock lightly
            try {
                Vector knock = tloc.toVector().subtract(loc.toVector()).normalize().multiply(0.2);
                knock.setY(0.1);
                target.setVelocity(target.getVelocity().add(knock));
            } catch (Throwable ignored) {
            }
            bot.remember("Fought " + target.getType().name());
            learning.observe(bot, "combat", "Attacked " + target.getType().name(), true, null);
        }
    }

    private LivingEntity findHostile(Location from, double range, Player owner) {
        LivingEntity best = null;
        double bestD = range * range;
        for (Entity e : from.getWorld().getNearbyEntities(from, range, range, range)) {
            if (!(e instanceof LivingEntity living) || living.isDead()) {
                continue;
            }
            if (living instanceof Player p) {
                if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
                    continue;
                }
                // Never attack owner or other players unless they are the threat — skip players
                continue;
            }
            if (!(living instanceof Monster)
                    && !living.getType().name().contains("SLIME")
                    && !living.getType().name().contains("PHANTOM")
                    && !living.getType().name().contains("HOGLIN")
                    && !living.getType().name().contains("PIGLIN")
                    && !living.getType().name().equals("ENDERMAN")) {
                // only hostiles
                if (!(living instanceof Mob mob) || mob.getTarget() == null) {
                    continue;
                }
                // if mob is targeting owner, engage
                if (owner == null || !owner.equals(mob.getTarget())) {
                    continue;
                }
            }
            if (living instanceof Monster || living.getType().name().contains("SLIME")
                    || living.getType().name().contains("PHANTOM")
                    || living.getType().name().contains("HOGLIN")
                    || living.getType().name().equals("PIGLIN")
                    || living.getType().name().equals("PIGLIN_BRUTE")
                    || living.getType().name().equals("ENDERMAN")) {
                double d = living.getLocation().distanceSquared(from);
                if (d < bestD) {
                    bestD = d;
                    best = living;
                }
            }
        }
        return best;
    }

    public static boolean looksLikeCombat(String order) {
        if (order == null) {
            return false;
        }
        String l = order.toLowerCase();
        return l.contains("guard") || l.contains("protect") || l.contains("patrol")
                || l.contains("defend") || l.contains("kill") || l.contains("fight")
                || l.contains("attack") || l.contains("secure") || l.contains("watch");
    }
}
