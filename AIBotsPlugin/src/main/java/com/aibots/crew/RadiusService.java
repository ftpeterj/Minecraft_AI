package com.aibots.crew;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Work / survey radius for crew bots.
 * <ul>
 *   <li><b>default</b> — persisted in config.yml ({@code crew.work-radius})</li>
 *   <li><b>session</b> — optional override until restart or {@code /crew radius clear}</li>
 * </ul>
 * Values above {@link #WARN_ABOVE} should be confirmed by the player.
 * Hard-capped at {@link #HARD_MAX} so pathfinding/scan cannot melt the server.
 */
public final class RadiusService {

    public static final int WARN_ABOVE = 200;
    public static final int HARD_MAX = 512;
    public static final int HARD_MIN = 8;

    private final JavaPlugin plugin;
    /** From config — survives restarts when saved. */
    private int defaultRadius;
    /** Null = use default. */
    private Integer sessionRadius;

    public RadiusService(JavaPlugin plugin) {
        this.plugin = plugin;
        reloadFromConfig();
    }

    public void reloadFromConfig() {
        FileConfiguration cfg = plugin.getConfig();
        // Prefer crew.work-radius; fall back to survey-radius then 48 (old hardcoded cap)
        int def = cfg.getInt("crew.work-radius",
                cfg.getInt("crew.survey-radius", 48));
        this.defaultRadius = clamp(def);
        // session cleared on reload
        this.sessionRadius = null;
    }

    /** Effective radius used by skills this tick. */
    public int effective() {
        int r = sessionRadius != null ? sessionRadius : defaultRadius;
        return clamp(r);
    }

    public int defaultRadius() {
        return defaultRadius;
    }

    public Integer sessionRadius() {
        return sessionRadius;
    }

    public boolean hasSessionOverride() {
        return sessionRadius != null;
    }

    public int warnAbove() {
        return plugin.getConfig().getInt("crew.radius-warn-above", WARN_ABOVE);
    }

    public int hardMax() {
        return Math.min(HARD_MAX, plugin.getConfig().getInt("crew.radius-hard-max", HARD_MAX));
    }

    public int hardMin() {
        return Math.max(HARD_MIN, plugin.getConfig().getInt("crew.radius-hard-min", HARD_MIN));
    }

    public int clamp(int r) {
        return Math.max(hardMin(), Math.min(hardMax(), r));
    }

    public boolean needsWarning(int r) {
        return r > warnAbove();
    }

    /**
     * Set session-only radius (not written to disk).
     *
     * @return clamped value applied
     */
    public int setSession(int radius) {
        this.sessionRadius = clamp(radius);
        return this.sessionRadius;
    }

    /** Clear session override; skills use config default again. */
    public void clearSession() {
        this.sessionRadius = null;
    }

    /**
     * Persist new default to config.yml and clear session override.
     *
     * @return clamped value saved
     */
    public int setDefaultAndSave(int radius) {
        int v = clamp(radius);
        this.defaultRadius = v;
        this.sessionRadius = null;
        plugin.getConfig().set("crew.work-radius", v);
        // Keep survey in sync for OrderPlanner unless explicitly different
        plugin.getConfig().set("crew.survey-radius", v);
        plugin.saveConfig();
        return v;
    }

    public String statusLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("work-radius effective=").append(effective());
        sb.append(" (default=").append(defaultRadius);
        if (sessionRadius != null) {
            sb.append(", session=").append(sessionRadius);
        }
        sb.append(")");
        sb.append("  warn>").append(warnAbove());
        sb.append("  hard-max=").append(hardMax());
        return sb.toString();
    }
}
