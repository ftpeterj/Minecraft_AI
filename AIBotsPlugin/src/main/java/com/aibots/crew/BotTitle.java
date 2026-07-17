package com.aibots.crew;

import java.util.Locale;
import java.util.Optional;

/**
 * Role / title a crew bot can hold. Phase 1: identity + prompts only.
 * Phase 2+: each title gains a task engine.
 */
public enum BotTitle {
    SCAVENGER("Scavenger", "GOLD"),
    WARRIOR("Warrior", "RED"),
    BUILDER("Builder", "AQUA"),
    FARMER("Farmer", "GREEN");

    private final String display;
    private final String defaultColor;

    BotTitle(String display, String defaultColor) {
        this.display = display;
        this.defaultColor = defaultColor;
    }

    public String display() {
        return display;
    }

    public String defaultColor() {
        return defaultColor;
    }

    public static Optional<BotTitle> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        // Aliases
        return switch (key) {
            case "SCAVENGER", "SCAV", "GATHERER", "GATHER", "MINER" -> Optional.of(SCAVENGER);
            case "WARRIOR", "PROTECTOR", "GUARD", "FIGHTER", "DEFENDER" -> Optional.of(WARRIOR);
            case "BUILDER", "BUILD", "ARCHITECT" -> Optional.of(BUILDER);
            case "FARMER", "FARM", "AGRICULTURE" -> Optional.of(FARMER);
            default -> {
                try {
                    yield Optional.of(BotTitle.valueOf(key));
                } catch (IllegalArgumentException e) {
                    yield Optional.empty();
                }
            }
        };
    }

    public static String usageList() {
        return "scavenger|warrior|builder|farmer";
    }
}
