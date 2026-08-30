package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/** Adapter: gatherer, fishing orders. */
public final class FishingCrewSkill implements CrewSkill {

    private final FishingSkill inner;

    public FishingCrewSkill(FishingSkill inner) {
        this.inner = inner;
    }

    @Override
    public String id() {
        return "fish";
    }

    @Override
    public boolean appliesTo(BotTitle title) {
        return title != null && title.isGatherer();
    }

    @Override
    public boolean canHandle(CrewBot bot, String order) {
        return FishingSkill.looksLikeFish(order);
    }

    @Override
    public List<String> accept(CrewBot bot, String order, Location origin) {
        List<String> lines = new ArrayList<>();
        bot.setCurrentOrder(order);
        bot.setStatus(BotStatus.BUSY);
        lines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.WHITE
                + ": Heading to the water to fish.");
        return lines;
    }

    @Override
    public void tick(CrewBot bot) {
        inner.tick(bot);
    }

    @Override
    public void onStop(CrewBot bot) {
        inner.clear(bot);
    }
}
