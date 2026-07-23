package com.aibots.npc;

import com.aibots.crew.BotTitle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Native Minecraft villager body — real pathfinder walking (not teleport steps).
 */
public final class VillagerHandle implements NpcHandle {

    private final Villager villager;
    private final JavaPlugin plugin;
    /** Last path destination we issued (sticky re-path). */
    private Location lastWalkGoal;
    private long lastWalkIssuedMs;

    public VillagerHandle(Villager villager, JavaPlugin plugin) {
        this.villager = villager;
        this.plugin = plugin;
    }

    public static VillagerHandle spawn(Location loc, String nameplate, BotTitle title, JavaPlugin plugin) {
        final String plate = nameplate == null ? "Bot" : nameplate;
        Villager v = loc.getWorld().spawn(loc, Villager.class, villager -> {
            villager.setCustomName(plate);
            villager.setCustomNameVisible(true);
            villager.setProfession(professionFor(title));
            villager.setVillagerType(Villager.Type.PLAINS);
            villager.setVillagerLevel(2);
            villager.setAdult();
            villager.setAI(true);
            villager.setAware(true);
            villager.setGravity(true);
            villager.setCanPickupItems(false);
            villager.setRemoveWhenFarAway(false);
            villager.setPersistent(true);
            villager.setSilent(false);
            villager.setInvulnerable(true);
            villager.setCollidable(true);
            try {
                villager.setRecipes(java.util.Collections.emptyList());
            } catch (Throwable ignored) {
            }
            EntityCleanup.tagAsCrew(villager);
            try {
                var attr = villager.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
                if (attr != null) {
                    attr.setBaseValue(0.28);
                }
            } catch (Throwable ignored) {
            }
            try {
                villager.setMemory(MemoryKey.JOB_SITE, null);
                villager.setMemory(MemoryKey.HOME, null);
                villager.setMemory(MemoryKey.MEETING_POINT, null);
            } catch (Throwable ignored) {
            }
        });

        stripGoals(v);
        configureNavigator(v);
        if (plugin != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                stripGoals(v);
                configureNavigator(v);
            }, 1L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                stripGoals(v);
                configureNavigator(v);
            }, 40L);
            plugin.getLogger().info("[AIBots] Spawned walking villager profession=" + v.getProfession());
        }
        return new VillagerHandle(v, plugin);
    }

    private static void configureNavigator(Villager v) {
        if (v == null || !v.isValid()) {
            return;
        }
        try {
            var pf = v.getPathfinder();
            pf.setCanOpenDoors(true);
            pf.setCanPassDoors(true);
            pf.setCanFloat(true);
        } catch (Throwable ignored) {
        }
    }

    private static void stripGoals(Villager v) {
        if (v == null || !v.isValid()) {
            return;
        }
        try {
            Bukkit.getMobGoals().removeAllGoals(v);
        } catch (Throwable ignored) {
        }
        try {
            v.setTarget(null);
        } catch (Throwable ignored) {
        }
    }

    private static Villager.Profession professionFor(BotTitle title) {
        if (title == null) {
            return Villager.Profession.NITWIT;
        }
        return switch (title) {
            case SCAVENGER -> Villager.Profession.TOOLSMITH;
            case MINER -> Villager.Profession.ARMORER;
            case WOODSMAN -> Villager.Profession.FLETCHER;
            case HUNTER -> Villager.Profession.BUTCHER;
            case FARMER -> Villager.Profession.FARMER;
            case WARRIOR -> Villager.Profession.WEAPONSMITH;
            case PROTECTOR -> Villager.Profession.WEAPONSMITH;
            case BUILDER -> Villager.Profession.MASON;
        };
    }

    public void setProfessionForTitle(BotTitle title) {
        if (isValid()) {
            villager.setProfession(professionFor(title));
        }
    }

    @Override
    public String backend() {
        return "villager";
    }

    @Override
    public boolean isValid() {
        return villager != null && villager.isValid() && !villager.isDead();
    }

    @Override
    public void destroy() {
        if (isValid()) {
            try {
                villager.getPathfinder().stopPathfinding();
            } catch (Throwable ignored) {
            }
            villager.remove();
        }
    }

    @Override
    public void teleport(Location location) {
        if (!isValid() || location == null) {
            return;
        }
        try {
            villager.getPathfinder().stopPathfinding();
        } catch (Throwable ignored) {
        }
        lastWalkGoal = null;
        Location dest = location.clone();
        dest.setPitch(0f);
        villager.teleport(dest);
        stripGoals(villager);
        configureNavigator(villager);
    }

    @Override
    public boolean walkTo(Location target, double speed) {
        if (!isValid() || target == null || target.getWorld() == null) {
            return false;
        }
        if (villager.getWorld() == null || !villager.getWorld().equals(target.getWorld())) {
            teleport(target);
            return true;
        }
        if (!villager.hasAI()) {
            villager.setAI(true);
        }
        configureNavigator(villager);

        double spd = speed <= 0 ? 1.0 : Math.min(speed, 1.25);
        // Path to standable feet position (center of block), not into solids
        Location dest = target.clone();
        dest.setX(dest.getBlockX() + 0.5);
        dest.setY(dest.getY());
        dest.setZ(dest.getBlockZ() + 0.5);

        // Sticky path: don't re-issue every tick — that causes circle-walking
        long now = System.currentTimeMillis();
        boolean sameGoal = lastWalkGoal != null
                && lastWalkGoal.getWorld() != null
                && lastWalkGoal.getWorld().equals(dest.getWorld())
                && lastWalkGoal.distanceSquared(dest) < 2.25; // within 1.5 blocks
        boolean stillPathing = false;
        try {
            stillPathing = villager.getPathfinder().hasPath();
        } catch (Throwable ignored) {
        }
        if (sameGoal && stillPathing && (now - lastWalkIssuedMs) < 4000L) {
            return true;
        }
        // Re-path if goal changed, path lost, or stuck long enough
        if (sameGoal && stillPathing && (now - lastWalkIssuedMs) < 8000L) {
            return true;
        }

        // Don't strip goals every step (was thrashing AI) — only if goals reappeared
        try {
            if (!Bukkit.getMobGoals().getAllGoals(villager).isEmpty()) {
                stripGoals(villager);
            }
        } catch (Throwable ignored) {
        }

        try {
            boolean ok = villager.getPathfinder().moveTo(dest, spd);
            if (ok) {
                lastWalkGoal = dest.clone();
                lastWalkIssuedMs = now;
                return true;
            }
        } catch (Throwable t) {
            if (plugin != null) {
                plugin.getLogger().fine("pathfinder.moveTo failed: " + t.getMessage());
            }
        }

        lastWalkGoal = dest.clone();
        lastWalkIssuedMs = now;
        return velocityStep(dest);
    }

    private boolean velocityStep(Location target) {
        Location from = villager.getLocation();
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.15) {
            return false;
        }
        double scale = Math.min(0.18, dist * 0.35);
        Vector v = new Vector(dx / dist * scale, Math.min(villager.getVelocity().getY(), 0), dz / dist * scale);
        if (!villager.isOnGround() && v.getY() > -0.15) {
            v.setY(-0.1);
        }
        villager.setVelocity(v);
        try {
            villager.lookAt(target);
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public void stopWalking() {
        if (!isValid()) {
            return;
        }
        lastWalkGoal = null;
        try {
            villager.getPathfinder().stopPathfinding();
        } catch (Throwable ignored) {
        }
        try {
            Vector v = villager.getVelocity();
            villager.setVelocity(new Vector(0, v.getY(), 0));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isWalking() {
        if (!isValid()) {
            return false;
        }
        try {
            return villager.getPathfinder().hasPath();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Location getLocation() {
        return isValid() ? villager.getLocation() : null;
    }

    @Override
    public void setNameplate(String nameplate) {
        if (isValid()) {
            villager.setCustomName(nameplate);
            villager.setCustomNameVisible(true);
        }
    }

    @Override
    public void setSkin(String skinNameOrUrl) {
    }

    @Override
    public Entity getEntity() {
        return villager;
    }

    @Override
    public Integer getCitizensId() {
        return null;
    }

    public void lookAt(Location loc) {
        if (isValid() && loc != null) {
            try {
                villager.lookAt(loc);
            } catch (Throwable ignored) {
            }
        }
    }
}
