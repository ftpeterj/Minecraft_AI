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
            return true;
        }
        String o = order.toLowerCase(Locale.ROOT);
        // Defer to Hunter for orders naming a passive/huntable animal — "kill the cow"
        // is a hunt order, not a guard/attack order, even though it contains "kill".
        boolean namesAnimal = o.contains("cow") || o.contains("pig") || o.contains("sheep")
                || o.contains("chicken") || o.contains("rabbit") || o.contains("animal") || o.contains("meat");
        boolean explicitGuard = o.contains("guard") || o.contains("protect") || o.contains("patrol")
                || o.contains("hostile");
        if (namesAnimal && !explicitGuard) {
            return false;
        }
        return explicitGuard || o.contains("fight") || o.contains("attack")
                || o.contains("defend") || o.contains("kill");
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
