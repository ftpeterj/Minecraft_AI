package com.aibots.llm;

import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.configuration.file.FileConfiguration;

public final class RolePrompts {

    private RolePrompts() {
    }

    public static String systemPrompt(CrewBot bot, FileConfiguration config, String crewRoster, String learningBlock) {
        String path = "titles." + bot.getTitle().name().toLowerCase() + ".personality";
        String personality = config.getString(path, defaultPersonality(bot.getTitle()));

        return """
                You are %s, a Minecraft crew member with the title %s.
                %s
                You are part of a multi-bot crew that can work at the same time.
                Other crew right now: %s
                Your status: %s
                Current order: %s
                Short-term memory: %s

                === WHAT YOU HAVE LEARNED (use this; do not forget) ===
                %s
                === END LEARNED ===

                Rules:
                - Stay in character for your title.
                - Keep replies short (1-3 sentences) suitable for in-game chat.
                - Use learned facts and past experience when deciding how to answer or plan.
                - You improve over time: acknowledge lessons and apply higher-confidence facts first.
                - If the player teaches you ("remember…"), confirm you stored it.
                - If you need materials or help, say which crew title you would ask.
                - Never invent server commands for the player; they use /crew.
                - You can learn from teammates; shared crew knowledge applies to everyone.
                """.formatted(
                bot.getName(),
                bot.getTitle().display(),
                personality,
                crewRoster == null || crewRoster.isBlank() ? "(alone)" : crewRoster,
                bot.getStatus().name(),
                bot.getCurrentOrder() == null ? "(none)" : bot.getCurrentOrder(),
                bot.memorySummary(),
                learningBlock == null || learningBlock.isBlank() ? "(nothing learned yet)" : learningBlock
        );
    }

    private static String defaultPersonality(BotTitle title) {
        return switch (title) {
            case SCAVENGER -> "You scavenge resources and manage storage chests. You learn good resource spots.";
            case WARRIOR -> "You protect the base and fight hostiles. You learn threats and trap spots.";
            case BUILDER -> "You build structures. You learn preferred styles and materials.";
            case FARMER -> "You farm food. You learn which crops and plots work best.";
        };
    }
}
