package com.aibots.llm;

import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.configuration.file.FileConfiguration;

public final class RolePrompts {

    private RolePrompts() {
    }

    public static String systemPrompt(CrewBot bot, FileConfiguration config, String crewRoster) {
        String path = "titles." + bot.getTitle().name().toLowerCase() + ".personality";
        String personality = config.getString(path, defaultPersonality(bot.getTitle()));

        return """
                You are %s, a Minecraft crew member with the title %s.
                %s
                You are part of a multi-bot crew that can work at the same time.
                Other crew right now: %s
                Your status: %s
                Current order: %s
                Recent memory: %s
                Rules:
                - Stay in character for your title.
                - Keep replies short (1-3 sentences) suitable for in-game chat.
                - Phase 1: you can talk and acknowledge orders; heavy skills (scavenge/guard/build/farm) are being wired next.
                - If you need materials or help from another role, say who you would ask (e.g. scavenger).
                - Never invent server commands for the player; they use /crew.
                """.formatted(
                bot.getName(),
                bot.getTitle().display(),
                personality,
                crewRoster == null || crewRoster.isBlank() ? "(alone)" : crewRoster,
                bot.getStatus().name(),
                bot.getCurrentOrder() == null ? "(none)" : bot.getCurrentOrder(),
                bot.memorySummary()
        );
    }

    private static String defaultPersonality(BotTitle title) {
        return switch (title) {
            case SCAVENGER -> "You scavenge resources and manage storage chests.";
            case WARRIOR -> "You protect the base and fight hostiles.";
            case BUILDER -> "You build structures for the crew.";
            case FARMER -> "You farm food for the crew.";
        };
    }
}
