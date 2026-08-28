package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Adapter: warrior / protector. */
public final class CombatCrewSkill implements CrewSkill {

    private final CombatSkill inner;

    public CombatCrewSkill(CombatSkill inner) {
        this.inner = inner;
    }

    @Override
    public String id() {
        return "combat";
    }

    @Override
    public boolean appliesTo(BotTitle title) {
        return title != null && title.isCombat();
    }

    @Override
    public boolean canHandle(CrewBot bot, String order) {
        if (order == null || order.isBlank()) {
            return bot.getTitle() == BotTitle.PROTECTOR;
        }
        String o = order.toLowerCase(Locale.ROOT);
        return o.contains("guard") || o.contains("protect") || o.contains("patrol")
                || o.contains("fight") || o.contains("attack") || o.contains("defend")
                || o.contains("kill") || o.contains("hostile");
    }

    @Override
    public List<String> accept(CrewBot bot, String order, Location origin) {
        List<String> lines = new ArrayList<>();
        bot.setCurrentOrder(order);
        bot.setStatus(BotStatus.BUSY);
        lines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.WHITE
                + ": Guarding — I'll fight hostiles near you/home until stopped.");
        return lines;
    }

    @Override
    public void tick(CrewBot bot) {
        inner.tick(bot);
    }
}
