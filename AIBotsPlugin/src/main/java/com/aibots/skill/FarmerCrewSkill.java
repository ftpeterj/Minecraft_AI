package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FarmerCrewSkill implements CrewSkill {

    private final FarmerSkill inner;

    public FarmerCrewSkill(FarmerSkill inner) {
        this.inner = inner;
    }

    @Override
    public String id() {
        return "farm";
    }

    @Override
    public boolean appliesTo(BotTitle title) {
        return title != null && title.isFarmer();
    }

    @Override
    public boolean canHandle(CrewBot bot, String order) {
        if (order == null || order.isBlank()) {
            return false;
        }
        String o = order.toLowerCase(Locale.ROOT);
        return o.contains("farm") || o.contains("crop") || o.contains("harvest")
                || o.contains("plant") || o.contains("wheat") || o.contains("field")
                || o.contains("replant") || o.contains("tend");
    }

    @Override
    public List<String> accept(CrewBot bot, String order, Location origin) {
        List<String> lines = new ArrayList<>();
        bot.setCurrentOrder(order);
        bot.setStatus(BotStatus.BUSY);
        lines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.WHITE
                + ": Working the fields — harvest mature crops and replant.");
        return lines;
    }

    @Override
    public void tick(CrewBot bot) {
        inner.tick(bot);
    }
}
