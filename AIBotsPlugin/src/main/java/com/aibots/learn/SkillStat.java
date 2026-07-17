package com.aibots.learn;

import org.bukkit.configuration.ConfigurationSection;

public class SkillStat {

    private final String skill;
    private int attempts;
    private int successes;
    private int failures;

    public SkillStat(String skill) {
        this.skill = skill;
    }

    public String getSkill() {
        return skill;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getSuccesses() {
        return successes;
    }

    public int getFailures() {
        return failures;
    }

    public double successRate() {
        if (attempts == 0) {
            return 0.0;
        }
        return (double) successes / (double) attempts;
    }

    public void record(boolean success) {
        attempts++;
        if (success) {
            successes++;
        } else {
            failures++;
        }
    }

    public void saveTo(ConfigurationSection section) {
        section.set("skill", skill);
        section.set("attempts", attempts);
        section.set("successes", successes);
        section.set("failures", failures);
    }

    public static SkillStat loadFrom(ConfigurationSection section) {
        SkillStat s = new SkillStat(section.getString("skill", "unknown"));
        s.attempts = section.getInt("attempts", 0);
        s.successes = section.getInt("successes", 0);
        s.failures = section.getInt("failures", 0);
        return s;
    }

    public String promptLine() {
        return skill + ": " + successes + "/" + attempts
                + " (" + String.format("%.0f", successRate() * 100) + "%)";
    }
}
