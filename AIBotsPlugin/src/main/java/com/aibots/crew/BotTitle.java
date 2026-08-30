package com.aibots.crew;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Role / title a crew bot can hold.
 */
public enum BotTitle {
    GATHERER("Gatherer", "GOLD", Set.of(RoleKind.GATHER, RoleKind.FARM)),
    DEFENDER("Defender", "RED", Set.of(RoleKind.BUILD, RoleKind.HUNT, RoleKind.COMBAT));

    public enum RoleKind {
        GATHER, HUNT, FARM, COMBAT, BUILD
    }

    private final String display;
    private final String defaultColor;
    private final Set<RoleKind> kinds;

    BotTitle(String display, String defaultColor, Set<RoleKind> kinds) {
        this.display = display;
        this.defaultColor = defaultColor;
        this.kinds = EnumSet.copyOf(kinds);
    }

    public String display() {
        return display;
    }

    public String defaultColor() {
        return defaultColor;
    }

    public Set<RoleKind> kinds() {
        return kinds;
    }

    public boolean isGatherer() {
        return kinds.contains(RoleKind.GATHER);
    }

    public boolean isCombat() {
        return kinds.contains(RoleKind.COMBAT);
    }

    public boolean isHunter() {
        return kinds.contains(RoleKind.HUNT);
    }

    public boolean isFarmer() {
        return kinds.contains(RoleKind.FARM);
    }

    public boolean isBuilder() {
        return kinds.contains(RoleKind.BUILD);
    }

    public static Optional<BotTitle> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (key) {
            case "GATHERER", "GATHER", "SCAVENGER", "SCAV", "MINER", "MINE", "DIGGER",
                    "WOODSMAN", "WOODSMEN", "LUMBERJACK", "LUMBER", "FORESTER", "WOODCUTTER", "LOGGER",
                    "FARMER", "FARM", "AGRICULTURE", "RANCHER", "FISHER", "FISHERMAN" -> Optional.of(GATHERER);
            case "DEFENDER", "DEFEND", "BUILDER", "BUILD", "ARCHITECT",
                    "HUNTER", "HUNT", "TRAPPER",
                    "WARRIOR", "FIGHTER", "SOLDIER", "PROTECTOR", "GUARD", "SENTINEL" -> Optional.of(DEFENDER);
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
        return "gatherer|defender";
    }
}
