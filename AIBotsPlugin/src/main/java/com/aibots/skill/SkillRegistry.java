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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes orders to the correct {@link CrewSkill} by title and canHandle.
 */
public final class SkillRegistry {

    private final Map<String, CrewSkill> byId = new LinkedHashMap<>();
    /** Which skill actually accept()-ed each bot's current order — tick()/stop() must
     *  keep hitting that same skill instance while the order is active, not just
     *  whichever skill is first-registered for the title (a title can now cover
     *  several skills at once). Cleared when the order finishes. */
    private final Map<UUID, CrewSkill> activeSkillByBot = new ConcurrentHashMap<>();

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
        Optional<CrewSkill> chosen = resolve(bot, order);
        List<String> lines = chosen
                .map(s -> s.accept(bot, order, origin))
                .orElseGet(ArrayList::new);
        if (chosen.isPresent() && bot.getCurrentOrder() != null && !bot.getCurrentOrder().isBlank()) {
            activeSkillByBot.put(bot.getId(), chosen.get());
        }
        return lines;
    }

    public void tick(CrewBot bot) {
        String order = bot.getCurrentOrder();
        CrewSkill active = activeSkillByBot.get(bot.getId());
        if (active != null && order != null && !order.isBlank()) {
            active.tick(bot);
            return;
        }
        // Order finished or was never set — fall back to the title's default skill
        // (first-registered for the title) so auto-when-idle behavior still runs.
        activeSkillByBot.remove(bot.getId());
        forTitle(bot.getTitle()).ifPresent(s -> s.tick(bot));
    }

    public void stop(CrewBot bot) {
        CrewSkill active = activeSkillByBot.remove(bot.getId());
        if (active != null) {
            active.onStop(bot);
            return;
        }
        forTitle(bot.getTitle()).ifPresent(s -> s.onStop(bot));
    }
}
