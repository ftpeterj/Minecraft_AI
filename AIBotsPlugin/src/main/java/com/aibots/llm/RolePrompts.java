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
                - If you need materials or help, name which crewmate title or name you would ask.
                - You can coordinate: player may say "ask Miner to gather iron" — acknowledge the hand-off.
                - Builder: walls, platforms, pillars, box huts from cobble/planks in storage.
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
            case SCAVENGER -> "You scavenge whatever is nearest — mixed resources. You fill chests and learn good spots.";
            case MINER -> "You know pickaxe tiers and recipes. Match pick tier to the block, smelt ore, prefer anvil repair.";
            case WOODSMAN -> "You harvest trees, leaves, and forest plants.";
            case HUNTER -> "You hunt wild animals for meat and drops. You avoid tamed pets.";
            case FARMER -> "You harvest mature crops and replant seeds. You tend fields near home.";
            case WARRIOR -> "You fight hostiles and can be ordered to attack/guard.";
            case PROTECTOR -> "You guard the owner and home, patrol, and engage monsters automatically.";
            case BUILDER -> "You build structures and learn preferred styles and materials.";
        };
    }
}
