package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class HunterCrewSkill implements CrewSkill {

    private final HunterSkill inner;

    public HunterCrewSkill(HunterSkill inner) {
        this.inner = inner;
    }

    @Override
    public String id() {
        return "hunt";
    }

    @Override
    public boolean appliesTo(BotTitle title) {
        return title != null && title.isHunter();
    }

    @Override
    public boolean canHandle(CrewBot bot, String order) {
        if (order == null || order.isBlank()) {
            return false;
        }
        String o = order.toLowerCase(Locale.ROOT);
        return o.contains("hunt") || o.contains("meat") || o.contains("animal")
                || o.contains("food") || o.contains("cow") || o.contains("pig")
                || o.contains("sheep") || o.contains("chicken") || o.contains("kill");
    }

    @Override
    public List<String> accept(CrewBot bot, String order, Location origin) {
        List<String> lines = new ArrayList<>();
        bot.setCurrentOrder(order);
        bot.setStatus(BotStatus.BUSY);
        lines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.WHITE
                + ": Hunting nearby animals for food. I'll fill my bag and deposit.");
        return lines;
    }

    @Override
    public void tick(CrewBot bot) {
        inner.tick(bot);
    }
}
