package com.botinterop;

import org.bukkit.Location;
import org.bukkit.entity.Player;

final class BotStatusFormatter {

    private BotStatusFormatter() {
    }

    static String format(Player bot) {
        Location loc = bot.getLocation();
        return String.format(
                "§e[BotInterop] §f%s §7HP: §c%.1f/%.0f §7Hunger: §6%d/20 §7@ §f%s %d,%d,%d",
                bot.getName(),
                bot.getHealth(),
                bot.getMaxHealth(),
                bot.getFoodLevel(),
                loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
