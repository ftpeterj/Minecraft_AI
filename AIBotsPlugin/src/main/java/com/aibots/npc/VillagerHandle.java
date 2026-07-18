package com.aibots.npc;

import com.aibots.crew.BotTitle;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Native Minecraft villager body — walks and arm-swings work; no black player skins.
 */
public final class VillagerHandle implements NpcHandle {

    private final Villager villager;

    public VillagerHandle(Villager villager) {
        this.villager = villager;
    }

    public static VillagerHandle spawn(Location loc, String nameplate, BotTitle title, JavaPlugin plugin) {
        // Single name tag only (avoid double holograms / duplicate setters)
        final String plate = nameplate == null ? "Bot" : nameplate;
        Villager v = loc.getWorld().spawn(loc, Villager.class, villager -> {
            villager.setCustomName(plate);
            villager.setCustomNameVisible(true);
            villager.setProfession(professionFor(title));
            villager.setVillagerType(Villager.Type.PLAINS);
            villager.setVillagerLevel(2);
            villager.setAdult();
            villager.setAI(false); // we drive movement
            villager.setAware(false);
            villager.setCanPickupItems(false);
            villager.setRemoveWhenFarAway(false);
            villager.setPersistent(true);
            villager.setSilent(false);
            villager.setInvulnerable(true);
            villager.setCollidable(true);
            // No trading — empty recipe list (right-click opens loot UI via listener)
            try {
                villager.setRecipes(java.util.Collections.emptyList());
            } catch (Throwable ignored) {
            }
            EntityCleanup.tagAsCrew(villager);
            try {
                var attr = villager.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
                if (attr != null) {
                    attr.setBaseValue(0.35);
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
        if (plugin != null) {
            plugin.getLogger().info("[AIBots] Spawned villager avatar profession=" + v.getProfession());
        }
        return new VillagerHandle(v);
    }

    private static Villager.Profession professionFor(BotTitle title) {
        if (title == null) {
            return Villager.Profession.NITWIT;
        }
        return switch (title) {
            case SCAVENGER -> Villager.Profession.TOOLSMITH;
            case WARRIOR -> Villager.Profession.WEAPONSMITH;
            case BUILDER -> Villager.Profession.MASON;
            case FARMER -> Villager.Profession.FARMER;
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
            villager.remove();
        }
    }

    @Override
    public void teleport(Location location) {
        if (isValid() && location != null) {
            Location dest = location.clone();
            Location from = villager.getLocation();
            if (from.getWorld() != null && dest.getWorld() != null) {
                // Yaw only — keep head level while walking (no staring at feet)
                double dx = dest.getX() - from.getX();
                double dz = dest.getZ() - from.getZ();
                if (dx * dx + dz * dz > 0.0001) {
                    float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
                    dest.setYaw(yaw);
                } else {
                    dest.setYaw(from.getYaw());
                }
                dest.setPitch(0f);
            }
            villager.teleport(dest);
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
        // Villagers don't use player skins — profession/clothes convey role
    }

    @Override
    public Entity getEntity() {
        return villager;
    }

    @Override
    public Integer getCitizensId() {
        return null;
    }
}
