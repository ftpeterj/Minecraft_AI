package com.aibots.skill;

import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.Location;

import java.util.List;

/**
 * Unified skill contract for crew roles (Phase 2 framework).
 * Implementations handle plan/accept, per-tick work, and stop cleanup.
 */
public interface CrewSkill {

    /** Stable id for logs and job routing (e.g. "gather", "combat", "build"). */
    String id();

    /** Whether this skill applies to the bot's title. */
    boolean appliesTo(BotTitle title);

    /**
     * Whether this skill can start work for the given order text.
     * Called before {@link #accept}; may be conservative.
     */
    boolean canHandle(CrewBot bot, String order);

    /**
     * Begin work from an order. Sets bot status/order as needed.
     *
     * @return chat lines for the player (may be empty)
     */
    List<String> accept(CrewBot bot, String order, Location origin);

    /** Per crew tick while the bot is alive. */
    void tick(CrewBot bot);

    /** Cleanup when {@code /crew stop} or job cancelled. */
    default void onStop(CrewBot bot) {
    }
}
