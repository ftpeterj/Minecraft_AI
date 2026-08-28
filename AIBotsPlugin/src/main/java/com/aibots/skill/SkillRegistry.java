package com.aibots.skill;

import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Routes orders to the correct {@link CrewSkill} by title and canHandle.
 */
public final class SkillRegistry {

    private final Map<String, CrewSkill> byId = new LinkedHashMap<>();

    public void register(CrewSkill skill) {
        if (skill != null) {
            byId.put(skill.id().toLowerCase(Locale.ROOT), skill);
        }
    }

    public Optional<CrewSkill> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<CrewSkill> all() {
        return List.copyOf(byId.values());
    }

    public Optional<CrewSkill> forTitle(BotTitle title) {
        if (title == null) {
            return Optional.empty();
        }
        for (CrewSkill s : byId.values()) {
            if (s.appliesTo(title)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /**
     * Find a skill that applies to the bot and can handle the order.
     * Prefers the title-primary skill, then any other matching skill.
     */
    public Optional<CrewSkill> resolve(CrewBot bot, String order) {
        if (bot == null) {
            return Optional.empty();
        }
        Optional<CrewSkill> primary = forTitle(bot.getTitle());
        if (primary.isPresent() && primary.get().canHandle(bot, order)) {
            return primary;
        }
        for (CrewSkill s : byId.values()) {
            if (s.appliesTo(bot.getTitle()) && s.canHandle(bot, order)) {
                return Optional.of(s);
            }
        }
        // Fall back: primary skill accepts any order for that title
        return primary;
    }

    public List<String> accept(CrewBot bot, String order, Location origin) {
        return resolve(bot, order)
                .map(s -> s.accept(bot, order, origin))
                .orElseGet(ArrayList::new);
    }

    public void tick(CrewBot bot) {
        forTitle(bot.getTitle()).ifPresent(s -> s.tick(bot));
    }

    public void stop(CrewBot bot) {
        forTitle(bot.getTitle()).ifPresent(s -> s.onStop(bot));
    }
}
