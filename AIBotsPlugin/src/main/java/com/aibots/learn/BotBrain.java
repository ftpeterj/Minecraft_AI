package com.aibots.learn;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Long-term learning state for one bot: facts, episodes, skill stats.
 */
public class BotBrain {

    private final UUID botId;
    private final Map<String, LearnedFact> facts = new LinkedHashMap<>();
    private final Deque<Episode> episodes = new ArrayDeque<>();
    private final Map<String, SkillStat> skills = new LinkedHashMap<>();
    private static final int MAX_EPISODES = 40;
    private static final int MAX_FACTS = 80;

    public BotBrain(UUID botId) {
        this.botId = botId;
    }

    public UUID getBotId() {
        return botId;
    }

    public Collection<LearnedFact> facts() {
        return List.copyOf(facts.values());
    }

    public List<Episode> recentEpisodes(int n) {
        List<Episode> all = new ArrayList<>(episodes);
        if (all.size() <= n) {
            return all;
        }
        return all.subList(all.size() - n, all.size());
    }

    public Collection<SkillStat> skillStats() {
        return List.copyOf(skills.values());
    }

    public LearnedFact learnFact(String key, String text, double confidence, String source, boolean shared) {
        String k = normalizeKey(key);
        LearnedFact existing = facts.get(k);
        if (existing != null) {
            existing.setText(text);
            existing.reinforce(0.08, source);
            if (shared) {
                existing.setShared(true);
            }
            return existing;
        }
        LearnedFact f = new LearnedFact(k, text, confidence, source, shared);
        facts.put(k, f);
        trimFacts();
        return f;
    }

    public LearnedFact reinforce(String key, double delta) {
        LearnedFact f = facts.get(normalizeKey(key));
        if (f != null) {
            f.reinforce(delta, null);
        }
        return f;
    }

    public void observe(String type, String summary, boolean success, String detail) {
        episodes.addLast(new Episode(type, summary, success, detail));
        while (episodes.size() > MAX_EPISODES) {
            episodes.removeFirst();
        }
        String skill = type.toLowerCase(Locale.ROOT);
        skills.computeIfAbsent(skill, SkillStat::new).record(success);
    }

    public void absorb(LearnedFact fact) {
        learnFact(fact.getKey(), fact.getText(), fact.getConfidence() * 0.9, "teammate", fact.isShared());
    }

    public String promptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("Learned facts:\n");
        if (facts.isEmpty()) {
            sb.append("- (none yet — learn from the player and experience)\n");
        } else {
            facts.values().stream()
                    .sorted((a, b) -> Double.compare(b.getConfidence(), a.getConfidence()))
                    .limit(15)
                    .forEach(f -> sb.append(f.promptLine()).append('\n'));
        }
        sb.append("Recent experience:\n");
        List<Episode> recent = recentEpisodes(8);
        if (recent.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (Episode e : recent) {
                sb.append("- ").append(e.promptLine()).append('\n');
            }
        }
        if (!skills.isEmpty()) {
            sb.append("Skill success rates:\n");
            for (SkillStat s : skills.values()) {
                sb.append("- ").append(s.promptLine()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    public void saveTo(ConfigurationSection section) {
        section.set("bot-id", botId.toString());
        int i = 0;
        for (LearnedFact f : facts.values()) {
            f.saveTo(section.createSection("facts." + i++));
        }
        i = 0;
        for (Episode e : episodes) {
            e.saveTo(section.createSection("episodes." + i++));
        }
        i = 0;
        for (SkillStat s : skills.values()) {
            s.saveTo(section.createSection("skills." + i++));
        }
    }

    public static BotBrain loadFrom(ConfigurationSection section, UUID fallbackId) {
        UUID id = fallbackId;
        if (section.getString("bot-id") != null) {
            try {
                id = UUID.fromString(section.getString("bot-id"));
            } catch (Exception ignored) {
            }
        }
        BotBrain brain = new BotBrain(id);
        ConfigurationSection factsSec = section.getConfigurationSection("facts");
        if (factsSec != null) {
            for (String k : factsSec.getKeys(false)) {
                ConfigurationSection fs = factsSec.getConfigurationSection(k);
                if (fs != null) {
                    LearnedFact f = LearnedFact.loadFrom(fs);
                    brain.facts.put(f.getKey(), f);
                }
            }
        }
        ConfigurationSection epSec = section.getConfigurationSection("episodes");
        if (epSec != null) {
            List<String> keys = new ArrayList<>(epSec.getKeys(false));
            keys.sort(String::compareTo);
            for (String k : keys) {
                ConfigurationSection es = epSec.getConfigurationSection(k);
                if (es != null) {
                    brain.episodes.addLast(Episode.loadFrom(es));
                }
            }
        }
        ConfigurationSection skSec = section.getConfigurationSection("skills");
        if (skSec != null) {
            for (String k : skSec.getKeys(false)) {
                ConfigurationSection ss = skSec.getConfigurationSection(k);
                if (ss != null) {
                    SkillStat st = SkillStat.loadFrom(ss);
                    brain.skills.put(st.getSkill(), st);
                }
            }
        }
        return brain;
    }

    private void trimFacts() {
        while (facts.size() > MAX_FACTS) {
            // drop lowest confidence
            String worst = null;
            double conf = Double.MAX_VALUE;
            for (Map.Entry<String, LearnedFact> e : facts.entrySet()) {
                if (e.getValue().getConfidence() < conf) {
                    conf = e.getValue().getConfidence();
                    worst = e.getKey();
                }
            }
            if (worst != null) {
                facts.remove(worst);
            } else {
                break;
            }
        }
    }

    public static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return "fact-" + System.currentTimeMillis();
        }
        return key.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
    }
}
