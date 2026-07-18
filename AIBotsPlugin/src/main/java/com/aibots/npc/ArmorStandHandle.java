package com.aibots.npc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.EulerAngle;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reliable avatar on Paper 26: armor stand + real player head (Citizens player skins render black).
 */
public final class ArmorStandHandle implements NpcHandle {

    private final ArmorStand stand;
    private final JavaPlugin plugin;

    public ArmorStandHandle(ArmorStand stand, JavaPlugin plugin) {
        this.stand = stand;
        this.plugin = plugin;
    }

    public static ArmorStandHandle spawn(Location loc, String nameplate, String skin, Color armorColor, JavaPlugin plugin) {
        Location at = loc.clone();
        ArmorStand stand = at.getWorld().spawn(at, ArmorStand.class, as -> {
            as.setCustomName(nameplate);
            as.setCustomNameVisible(true);
            as.setGravity(true);
            as.setVisible(true);
            as.setArms(true);
            as.setBasePlate(false);
            as.setSmall(false);
            as.setRemoveWhenFarAway(false);
            as.setCanPickupItems(false);
            as.setInvulnerable(true);
            as.setCollidable(false);
            as.setSilent(true);
            // Slight pose so it reads as a person
            as.setRightArmPose(new EulerAngle(Math.toRadians(-15), 0, Math.toRadians(10)));
            as.setLeftArmPose(new EulerAngle(Math.toRadians(-15), 0, Math.toRadians(-10)));

            as.getEquipment().setChestplate(dyed(Material.LEATHER_CHESTPLATE, armorColor));
            as.getEquipment().setLeggings(dyed(Material.LEATHER_LEGGINGS, armorColor));
            as.getEquipment().setBoots(dyed(Material.LEATHER_BOOTS, armorColor));
            as.getEquipment().setItemInMainHand(new ItemStack(Material.WOODEN_AXE));
            as.getEquipment().setHelmet(new ItemStack(Material.PLAYER_HEAD));
            EntityCleanup.tagAsCrew(as);
        });

        ArmorStandHandle handle = new ArmorStandHandle(stand, plugin);
        handle.applyHeadSkin(skin);
        return handle;
    }

    /** Back-compat */
    public static ArmorStandHandle spawn(Location loc, String nameplate, String skin) {
        return spawn(loc, nameplate, skin, Color.fromRGB(0xC4A35A), null);
    }

    private static ItemStack dyed(Material mat, Color color) {
        ItemStack item = new ItemStack(mat);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color == null ? Color.fromRGB(0xC4A35A) : color);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void applyHeadSkin(String skinName) {
        if (!isValid() || plugin == null) {
            return;
        }
        String name = skinName == null || skinName.isBlank() ? "Steve" : skinName.trim();

        // 1) Online player — copy their live profile onto the skull
        Player online = Bukkit.getPlayerExact(name);
        if (online == null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().equalsIgnoreCase(name)) {
                    online = p;
                    break;
                }
            }
        }
        if (online != null) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                try {
                    meta.setOwningPlayer(online);
                    // Paper profile copy
                    try {
                        Method getProf = online.getClass().getMethod("getPlayerProfile");
                        Object profile = getProf.invoke(online);
                        Method setProf = null;
                        for (Method m : meta.getClass().getMethods()) {
                            if (m.getName().equals("setPlayerProfile") && m.getParameterCount() == 1) {
                                setProf = m;
                                break;
                            }
                        }
                        if (setProf != null && profile != null) {
                            setProf.invoke(meta, profile);
                        }
                    } catch (Throwable ignored) {
                    }
                    head.setItemMeta(meta);
                    stand.getEquipment().setHelmet(head);
                    plugin.getLogger().info("[AIBots] ArmorStand head skin from online " + online.getName());
                    return;
                } catch (Throwable t) {
                    plugin.getLogger().warning("[AIBots] Online head apply failed: " + t.getMessage());
                }
            }
        }

        // 2) Async Mojang / Ashcon textures → PlayerProfile on skull
        final String fetch = name;
        CompletableFuture.runAsync(() -> {
            SkinApplier.Texture tex = SkinApplier.fetchTexturePublic(fetch, plugin);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!isValid()) {
                    return;
                }
                ItemStack head = headFromTexture(fetch, tex);
                stand.getEquipment().setHelmet(head);
                plugin.getLogger().info("[AIBots] ArmorStand head skin from textures for " + fetch);
            });
        });
    }

    private static ItemStack headFromTexture(String name, SkinApplier.Texture tex) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) {
            return head;
        }
        try {
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            String shortName = name.length() > 16 ? name.substring(0, 16) : name;

            if (tex != null && tex.value() != null) {
                // Prefer Paper createProfile + property
                try {
                    Object pp = Bukkit.class.getMethod("createProfile", UUID.class, String.class)
                            .invoke(null, uuid, shortName);
                    Class<?> propCl;
                    try {
                        propCl = Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
                    } catch (ClassNotFoundException e) {
                        propCl = Class.forName("io.papermc.paper.profile.ProfileProperty");
                    }
                    Object prop = propCl.getConstructor(String.class, String.class, String.class)
                            .newInstance("textures", tex.value(), tex.signature() == null ? "" : tex.signature());
                    for (Method m : pp.getClass().getMethods()) {
                        if (m.getName().equals("setProperty") && m.getParameterCount() == 1) {
                            m.invoke(pp, prop);
                            break;
                        }
                    }
                    for (Method m : meta.getClass().getMethods()) {
                        if (m.getName().equals("setPlayerProfile") && m.getParameterCount() == 1) {
                            m.invoke(meta, pp);
                            head.setItemMeta(meta);
                            return head;
                        }
                    }
                } catch (Throwable ignored) {
                }

                // Bukkit PlayerProfile + skin URL from base64
                try {
                    PlayerProfile profile = Bukkit.createPlayerProfile(uuid, shortName);
                    String json = new String(Base64.getDecoder().decode(tex.value()), StandardCharsets.UTF_8);
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                    String url = root.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
                    URL skinUrl = URI.create(url).toURL();
                    PlayerTextures textures = profile.getTextures();
                    textures.setSkin(skinUrl);
                    profile.setTextures(textures);
                    meta.setOwnerProfile(profile);
                    head.setItemMeta(meta);
                    return head;
                } catch (Throwable ignored) {
                }
            }

            meta.setOwningPlayer(Bukkit.getOfflinePlayer(name));
            head.setItemMeta(meta);
        } catch (Throwable t) {
            // default head
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
        applyHeadSkin(skinNameOrUrl);
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
