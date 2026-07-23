package com.aibots.crew;

import java.util.Locale;
import java.util.Optional;

/**
 * Role / title a crew bot can hold.
 */
public enum BotTitle {
    SCAVENGER("Scavenger", "GOLD", RoleKind.GATHER),
    MINER("Miner", "DARK_GRAY", RoleKind.GATHER),
    WOODSMAN("Woodsman", "DARK_GREEN", RoleKind.GATHER),
    HUNTER("Hunter", "GOLD", RoleKind.HUNT),
    FARMER("Farmer", "GREEN", RoleKind.FARM),
    WARRIOR("Warrior", "RED", RoleKind.COMBAT),
    PROTECTOR("Protector", "DARK_RED", RoleKind.COMBAT),
    BUILDER("Builder", "AQUA", RoleKind.BUILD);

    public enum RoleKind {
        GATHER, HUNT, FARM, COMBAT, BUILD
    }

    private final String display;
    private final String defaultColor;
    private final RoleKind kind;

    BotTitle(String display, String defaultColor, RoleKind kind) {
        this.display = display;
        this.defaultColor = defaultColor;
        this.kind = kind;
    }

    public String display() {
        return display;
    }

    public String defaultColor() {
        return defaultColor;
    }

    public RoleKind kind() {
        return kind;
    }

    public boolean isGatherer() {
        return kind == RoleKind.GATHER;
    }

    public boolean isCombat() {
        return kind == RoleKind.COMBAT;
    }

    public boolean isHunter() {
        return kind == RoleKind.HUNT;
    }

    public boolean isFarmer() {
        return kind == RoleKind.FARM;
    }

    public static Optional<BotTitle> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (key) {
            case "SCAVENGER", "SCAV", "GATHERER", "GATHER" -> Optional.of(SCAVENGER);
            case "MINER", "MINE", "DIGGER" -> Optional.of(MINER);
            case "WOODSMAN", "WOODSMEN", "LUMBERJACK", "LUMBER", "FORESTER", "WOODCUTTER", "LOGGER" -> Optional.of(WOODSMAN);
            case "HUNTER", "HUNT", "TRAPPER" -> Optional.of(HUNTER);
            case "FARMER", "FARM", "AGRICULTURE", "RANCHER" -> Optional.of(FARMER);
            case "WARRIOR", "FIGHTER", "SOLDIER" -> Optional.of(WARRIOR);
            case "PROTECTOR", "GUARD", "DEFENDER", "SENTINEL" -> Optional.of(PROTECTOR);
            case "BUILDER", "BUILD", "ARCHITECT" -> Optional.of(BUILDER);
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
        return "scavenger|miner|woodsman|hunter|farmer|warrior|protector|builder";
    }
}
