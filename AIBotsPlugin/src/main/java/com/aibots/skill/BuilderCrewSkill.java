package com.aibots.skill;

import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.Location;

import java.util.List;

public final class BuilderCrewSkill implements CrewSkill {

    private final BuilderSkill inner;

    public BuilderCrewSkill(BuilderSkill inner) {
        this.inner = inner;
    }

    public BuilderSkill inner() {
        return inner;
    }

    @Override
    public String id() {
        return "build";
    }

    @Override
    public boolean appliesTo(BotTitle title) {
        return title == BotTitle.DEFENDER;
    }

    @Override
    public boolean canHandle(CrewBot bot, String order) {
        return BuilderSkill.looksLikeBuild(order);
    }

    @Override
    public List<String> accept(CrewBot bot, String order, Location origin) {
        return inner.startJob(bot, order, origin);
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
