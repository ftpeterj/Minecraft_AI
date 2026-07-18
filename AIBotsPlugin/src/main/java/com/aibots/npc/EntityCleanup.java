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
                String cn = entity.getCustomName();
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
}
