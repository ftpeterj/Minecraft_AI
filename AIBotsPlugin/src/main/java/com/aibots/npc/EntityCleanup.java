package com.aibots.npc;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Remove orphan crew bodies left in the world (duplicate nameplates / ghosts).
 */
public final class EntityCleanup {

    public static final String TAG = "aibots-crew";

    private EntityCleanup() {
    }

    public static String stripColor(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                .replaceAll("&#[0-9a-fA-F]{6}", "")
                .trim();
    }

    /** Bare name from "Rusty [Scavenger]" or colored nameplates. */
    public static String bareName(String customName) {
        String s = stripColor(customName);
        int bracket = s.indexOf('[');
        if (bracket > 0) {
            s = s.substring(0, bracket).trim();
        }
        return s;
    }

    public static void tagAsCrew(Entity entity) {
        if (entity != null) {
            entity.addScoreboardTag(TAG);
        }
    }

    /**
     * Remove living non-player entities that are tagged or named like this crew bot.
     */
    public static int removeCrewBodiesNamed(String botName) {
        if (botName == null || botName.isBlank()) {
            return 0;
        }
        String want = botName.trim().toLowerCase(Locale.ROOT);
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    continue;
                }
                if (!(entity instanceof LivingEntity)) {
                    continue;
                }
                boolean tagged = entity.getScoreboardTags().contains(TAG);
                String cn = resolveName(entity);
                boolean nameMatch = false;
                if (cn != null) {
                    String bare = bareName(cn).toLowerCase(Locale.ROOT);
                    String full = stripColor(cn).toLowerCase(Locale.ROOT);
                    nameMatch = bare.equals(want)
                            || full.startsWith(want + " ")
                            || full.startsWith(want + "[");
                }
                // Remove tagged crew bodies or any villager/armorstand with matching name
                boolean bodyType = entity instanceof org.bukkit.entity.Villager
                        || entity instanceof org.bukkit.entity.ArmorStand;
                if (nameMatch && (tagged || bodyType)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    public static int removeAllTaggedCrew() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    continue;
                }
                if (entity.getScoreboardTags().contains(TAG)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Aggressive sweep: any villager/armorstand that looks like a crew bot
     * (tagged, or name ends with a role title).
     */
    public static int removeAllLikelyCrewBodies() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    continue;
                }
                if (!(entity instanceof LivingEntity)) {
                    continue;
                }
                boolean bodyType = entity instanceof org.bukkit.entity.Villager
                        || entity instanceof org.bukkit.entity.ArmorStand;
                if (!bodyType && !entity.getScoreboardTags().contains(TAG)) {
                    continue;
                }
                String cn = resolveName(entity);
                if (entity.getScoreboardTags().contains(TAG) || looksLikeCrewName(cn)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    public static boolean looksLikeCrewName(String customName) {
        if (customName == null) {
            return false;
        }
        String full = stripColor(customName).toLowerCase(Locale.ROOT);
        return full.contains("[scavenger]")
                || full.contains("[miner]")
                || full.contains("[woodsman]")
                || full.contains("[hunter]")
                || full.contains("[farmer]")
                || full.contains("[warrior]")
                || full.contains("[protector]")
                || full.contains("[builder]")
                || full.contains("[fighter]")
                || full.contains("[guard]")
                || full.contains("[lumberjack]");
    }

    /**
     * Remove crew-like villager/armorstand whose bare name matches (case-insensitive).
     * Broader than {@link #removeCrewBodiesNamed}: uses Adventure customName fallback
     * and accepts any living entity that looks like a crew nameplate.
     */
    public static int removeAllLikelyCrewBodiesMatching(String botName) {
        if (botName == null || botName.isBlank()) {
            return 0;
        }
        String want = botName.trim().toLowerCase(Locale.ROOT);
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player || !(entity instanceof LivingEntity)) {
                    continue;
                }
                boolean bodyType = entity instanceof org.bukkit.entity.Villager
                        || entity instanceof org.bukkit.entity.ArmorStand;
                if (!bodyType && !entity.getScoreboardTags().contains(TAG)) {
                    continue;
                }
                String resolved = resolveName(entity);
                if (resolved == null) {
                    continue;
                }
                String bare = bareName(resolved).toLowerCase(Locale.ROOT);
                String full = stripColor(resolved).toLowerCase(Locale.ROOT);
                boolean nameMatch = bare.equals(want)
                        || full.startsWith(want + " ")
                        || full.startsWith(want + "[")
                        || full.contains(want);
                if (nameMatch && (entity.getScoreboardTags().contains(TAG)
                        || looksLikeCrewName(resolved)
                        || bodyType)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Prefer legacy customName; fall back to Adventure component string. */
    public static String resolveName(Entity entity) {
        if (entity == null) {
            return null;
        }
        String cn = entity.getCustomName();
        if (cn != null && !cn.isBlank()) {
            return cn;
        }
        try {
            var component = entity.customName();
            if (component != null) {
                return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(component);
            }
        } catch (Throwable ignored) {
        }
        try {
            String name = entity.getName();
            if (name != null && !name.isBlank() && !name.equalsIgnoreCase("Villager")
                    && !name.equalsIgnoreCase("Armor Stand")) {
                return name;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
