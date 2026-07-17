package com.aibots.learn;

import com.aibots.crew.CrewBot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages per-bot brains + shared crew knowledge. All titles can learn.
 */
public class LearningService {

    private final JavaPlugin plugin;
    private final Map<UUID, BotBrain> brains = new ConcurrentHashMap<>();
    private final Map<String, LearnedFact> sharedFacts = new LinkedHashMap<>();
    private final File file;

    private static final Pattern PREFER = Pattern.compile(
            "(?i)\\b(?:always|prefer|please|remember|never|don't|do not)\\b.{5,120}");
    private static final Pattern IS_FACT = Pattern.compile(
            "(?i)\\b(.+?)\\s+(?:is|are|means|equals)\\s+(.+)");

    public LearningService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "learning.yml");
    }

    public BotBrain brain(CrewBot bot) {
        return brains.computeIfAbsent(bot.getId(), BotBrain::new);
    }

    public void ensureBrain(CrewBot bot) {
        brain(bot);
    }

    public LearnedFact teach(CrewBot bot, String factText, String source, boolean shareWithCrew) {
        String key = deriveKey(factText);
        BotBrain b = brain(bot);
        LearnedFact f = b.learnFact(key, factText, 0.85, source, shareWithCrew);
        b.observe("teach", "Taught: " + factText, true, source);
        if (shareWithCrew || f.isShared()) {
            publishShared(f);
        }
        save();
        return f;
    }

    public void observe(CrewBot bot, String type, String summary, boolean success, String detail) {
        BotBrain b = brain(bot);
        b.observe(type, summary, success, detail);
        // Auto-promote strong repeated successes into facts
        if (success && summary != null && summary.length() > 8) {
            String key = "exp_" + BotBrain.normalizeKey(type + "_" + summary);
            if (b.facts().stream().noneMatch(f -> f.getKey().equals(key))) {
                // only create fact after we can check skill rate
                b.skillStats().stream()
                        .filter(s -> s.getSkill().equalsIgnoreCase(type) && s.getSuccesses() >= 3 && s.successRate() >= 0.6)
                        .findFirst()
                        .ifPresent(s -> b.learnFact(key, "From experience: " + summary, 0.55, "experience", false));
            }
        }
        // Debounce disk: save periodically via manager; still save important failures/successes lightly
        if (plugin.getConfig().getBoolean("learning.save-on-observe", false)) {
            save();
        }
    }

    public void learnFromPlayerChat(CrewBot bot, String playerName, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        BotBrain b = brain(bot);
        b.observe("chat", "Heard " + playerName + ": " + truncate(message, 120), true, "chat");

        // Explicit teach phrases
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("remember") || lower.contains("learn this") || lower.contains("note that")
                || lower.startsWith("fact:") || lower.contains("always ") || lower.contains("never ")) {
            String fact = message.replaceFirst("(?i).*?(remember that|remember|learn this|note that|fact:)\\s*", "").trim();
            if (fact.length() < 4) {
                fact = message.trim();
            }
            teach(bot, fact, playerName, lower.contains("tell the crew") || lower.contains("share with"));
            return;
        }

        Matcher prefer = PREFER.matcher(message);
        if (prefer.find()) {
            teach(bot, prefer.group().trim(), playerName, false);
        }

        Matcher isFact = IS_FACT.matcher(message);
        if (isFact.find() && message.length() < 160) {
            String key = BotBrain.normalizeKey(isFact.group(1));
            b.learnFact(key, message.trim(), 0.45, playerName, false);
        }
    }

    public int shareAllSharedTo(CrewBot bot) {
        BotBrain b = brain(bot);
        int n = 0;
        for (LearnedFact f : sharedFacts.values()) {
            b.absorb(f);
            n++;
        }
        return n;
    }

    public LearnedFact shareFact(CrewBot from, CrewBot to, String keyOrText) {
        BotBrain fb = brain(from);
        LearnedFact match = fb.facts().stream()
                .filter(f -> f.getKey().equalsIgnoreCase(keyOrText)
                        || f.getText().toLowerCase(Locale.ROOT).contains(keyOrText.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
        if (match == null) {
            // teach as new shared from free text
            match = teach(from, keyOrText, from.getName(), true);
        } else {
            match.setShared(true);
            publishShared(match);
        }
        brain(to).absorb(match);
        brain(to).observe("learn_from_teammate", "Learned from " + from.getName() + ": " + match.getText(), true, match.getKey());
        brain(from).observe("teach_teammate", "Taught " + to.getName() + ": " + match.getText(), true, match.getKey());
        save();
        return match;
    }

    public void publishShared(LearnedFact f) {
        sharedFacts.put(f.getKey(), f);
    }

    public List<LearnedFact> sharedFacts() {
        return List.copyOf(sharedFacts.values());
    }

    public String promptContext(CrewBot bot) {
        StringBuilder sb = new StringBuilder();
        sb.append(brain(bot).promptBlock());
        if (!sharedFacts.isEmpty()) {
            sb.append("\nCrew shared knowledge:\n");
            sharedFacts.values().stream().limit(12).forEach(f -> sb.append(f.promptLine()).append('\n'));
        }
        return sb.toString().trim();
    }

    public void removeBrain(UUID botId) {
        brains.remove(botId);
        save();
    }

    public void load() {
        brains.clear();
        sharedFacts.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection bots = yaml.getConfigurationSection("bots");
        if (bots != null) {
            for (String key : bots.getKeys(false)) {
                ConfigurationSection sec = bots.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                try {
                    UUID id = UUID.fromString(sec.getString("bot-id", key));
                    BotBrain brain = BotBrain.loadFrom(sec, id);
                    brains.put(id, brain);
                } catch (Exception e) {
                    plugin.getLogger().warning("Bad learning entry " + key + ": " + e.getMessage());
                }
            }
        }
        ConfigurationSection shared = yaml.getConfigurationSection("shared");
        if (shared != null) {
            for (String key : shared.getKeys(false)) {
                ConfigurationSection sec = shared.getConfigurationSection(key);
                if (sec != null) {
                    LearnedFact f = LearnedFact.loadFrom(sec);
                    sharedFacts.put(f.getKey(), f);
                }
            }
        }
        plugin.getLogger().info("Loaded learning for " + brains.size() + " bot(s), "
                + sharedFacts.size() + " shared fact(s).");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        int i = 0;
        for (Map.Entry<UUID, BotBrain> e : brains.entrySet()) {
            ConfigurationSection sec = yaml.createSection("bots." + i++);
            e.getValue().saveTo(sec);
        }
        i = 0;
        for (LearnedFact f : sharedFacts.values()) {
            f.saveTo(yaml.createSection("shared." + i++));
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save learning.yml: " + ex.getMessage());
        }
    }

    private static String deriveKey(String text) {
        String t = text.trim();
        if (t.length() > 40) {
            t = t.substring(0, 40);
        }
        return BotBrain.normalizeKey(t);
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }

    public List<String> memoryLines(CrewBot bot, int factLimit) {
        List<String> lines = new ArrayList<>();
        BotBrain b = brain(bot);
        lines.add("Facts:");
        int n = 0;
        for (LearnedFact f : b.facts()) {
            if (n++ >= factLimit) {
                break;
            }
            lines.add("  " + f.promptLine());
        }
        if (n == 0) {
            lines.add("  (none)");
        }
        lines.add("Recent episodes:");
        for (Episode e : b.recentEpisodes(10)) {
            lines.add("  " + e.promptLine());
        }
        lines.add("Skills:");
        if (b.skillStats().isEmpty()) {
            lines.add("  (none)");
        } else {
            for (SkillStat s : b.skillStats()) {
                lines.add("  " + s.promptLine());
            }
        }
        return lines;
    }
}
