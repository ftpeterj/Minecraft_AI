package com.aibots.npc;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

/**
 * Fallback body when Citizens is not installed.
 * Uses an ArmorStand with optional player-head for a crude avatar.
 */
public final class ArmorStandHandle implements NpcHandle {

    private final ArmorStand stand;

    public ArmorStandHandle(ArmorStand stand) {
        this.stand = stand;
    }

    public static ArmorStandHandle spawn(Location loc, String nameplate, String skin) {
        ArmorStand stand = loc.getWorld().spawn(loc, ArmorStand.class, as -> {
            as.setCustomName(nameplate);
            as.setCustomNameVisible(true);
            as.setGravity(true);
            as.setVisible(true);
            as.setArms(true);
            as.setBasePlate(false);
            as.setRemoveWhenFarAway(false);
            as.setCanPickupItems(false);
            as.setInvulnerable(false);
            as.getEquipment().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
            as.getEquipment().setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
            as.getEquipment().setBoots(new ItemStack(Material.LEATHER_BOOTS));
            as.getEquipment().setItemInMainHand(new ItemStack(Material.WOODEN_SWORD));
            as.getEquipment().setHelmet(playerHead(skin));
        });
        return new ArmorStandHandle(stand);
    }

    private static ItemStack playerHead(String skin) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null && skin != null && !skin.isBlank()) {
            try {
                meta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(skin));
            } catch (Exception ignored) {
                // leave default head
            }
            head.setItemMeta(meta);
        }
        return head;
    }

    @Override
    public String backend() {
        return "armorstand";
    }

    @Override
    public boolean isValid() {
        return stand != null && stand.isValid() && !stand.isDead();
    }

    @Override
    public void destroy() {
        if (stand != null && stand.isValid()) {
            stand.remove();
        }
    }

    @Override
    public void teleport(Location location) {
        if (isValid() && location != null) {
            stand.teleport(location);
        }
    }

    @Override
    public Location getLocation() {
        return isValid() ? stand.getLocation() : null;
    }

    @Override
    public void setNameplate(String nameplate) {
        if (isValid()) {
            stand.setCustomName(nameplate);
            stand.setCustomNameVisible(true);
        }
    }

    @Override
    public void setSkin(String skinNameOrUrl) {
        if (isValid()) {
            stand.getEquipment().setHelmet(playerHead(skinNameOrUrl));
        }
    }

    @Override
    public Entity getEntity() {
        return stand;
    }

    @Override
    public Integer getCitizensId() {
        return null;
    }
}
