package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Adapter: scavenger / miner / woodsman. */
public final class GatherCrewSkill implements CrewSkill {

    private final ScavengeSkill inner;

    public GatherCrewSkill(ScavengeSkill inner) {
        this.inner = inner;
    }

    public ScavengeSkill inner() {
        return inner;
    }

    @Override
    public String id() {
        return "gather";
    }

    @Override
    public boolean appliesTo(BotTitle title) {
        return title != null && title.isGatherer();
    }

    @Override
    public boolean canHandle(CrewBot bot, String order) {
        if (order == null || order.isBlank()) {
            return false;
        }
        String o = order.toLowerCase(Locale.ROOT);
        if (o.contains("guard") || o.contains("hunt") || o.contains("farm") || o.contains("build wall")
                || o.contains("platform") || o.contains("pillar") || o.contains("fish")) {
            return false;
        }
        return true;
    }

    @Override
    public List<String> accept(CrewBot bot, String order, Location origin) {
        List<String> lines = new ArrayList<>();
        var plan = inner.planOrder(bot, order, origin);
        lines.addAll(plan.messages);
        String lower = order.toLowerCase(Locale.ROOT);
        boolean miningFlavor = lower.contains("mine") || lower.contains("ore") || lower.contains("pick")
                || lower.contains("stone") || lower.contains("iron") || lower.contains("coal")
                || lower.contains("diamond") || lower.contains("deepslate") || lower.contains("cobble");
        if (plan.startWork && miningFlavor) {
            if (lower.contains("recipe") || lower.contains("craft") || lower.contains("how")
                    || lower.contains("tool") || lower.contains("anvil") || lower.contains("repair")) {
                for (String line : inner.minerTools().recipeHelpLines()) {
                    lines.add(org.bukkit.ChatColor.GRAY + "  " + line);
                }
            } else {
                lines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.GRAY
                        + ": I'll use the right pickaxe tier for the job, craft a table if needed, "
                        + "and prefer anvil repair over crafting new tools when picks wear out.");
            }
        }
        // A vague order (planOrder surveyed and asked instead of guessing) leaves the
        // bot idle with no order recorded — don't override that back to BUSY here.
        if (plan.startWork) {
            bot.setCurrentOrder(order);
            bot.setStatus(BotStatus.BUSY);
        }
        return lines;
    }

    @Override
    public void tick(CrewBot bot) {
        inner.tick(bot);
    }
}
