package com.aibots.learn;

import org.bukkit.configuration.ConfigurationSection;

/**
 * A durable piece of knowledge a bot (or the whole crew) has learned.
 */
public class LearnedFact {

    private final String key;
    private String text;
    private double confidence; // 0.0 – 1.0
    private String source;     // player name, "experience", "teammate:Rusty", etc.
    private int reinforcements;
    private long lastUpdatedEpochMs;
    private boolean shared;    // available to whole crew

    public LearnedFact(String key, String text, double confidence, String source, boolean shared) {
        this.key = key;
        this.text = text;
        this.confidence = clamp(confidence);
        this.source = source == null ? "unknown" : source;
        this.reinforcements = 1;
        this.lastUpdatedEpochMs = System.currentTimeMillis();
        this.shared = shared;
    }

    public String getKey() {
        return key;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
        touch();
    }

    public double getConfidence() {
        return confidence;
    }

    public String getSource() {
        return source;
    }

    public int getReinforcements() {
        return reinforcements;
    }

    public long getLastUpdatedEpochMs() {
        return lastUpdatedEpochMs;
    }

    public boolean isShared() {
        return shared;
    }

    public void setShared(boolean shared) {
        this.shared = shared;
        touch();
    }

    public void reinforce(double delta, String sourceNote) {
        this.reinforcements++;
        this.confidence = clamp(this.confidence + delta);
        if (sourceNote != null && !sourceNote.isBlank()) {
            this.source = sourceNote;
        }
        touch();
    }

    public void weaken(double delta) {
        this.confidence = clamp(this.confidence - delta);
        touch();
    }

    private void touch() {
        this.lastUpdatedEpochMs = System.currentTimeMillis();
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    public void saveTo(ConfigurationSection section) {
        section.set("key", key);
        section.set("text", text);
        section.set("confidence", confidence);
        section.set("source", source);
        section.set("reinforcements", reinforcements);
        section.set("last-updated", lastUpdatedEpochMs);
        section.set("shared", shared);
    }

    public static LearnedFact loadFrom(ConfigurationSection section) {
        LearnedFact f = new LearnedFact(
                section.getString("key", "fact"),
                section.getString("text", ""),
                section.getDouble("confidence", 0.5),
                section.getString("source", "unknown"),
                section.getBoolean("shared", false)
        );
        f.reinforcements = section.getInt("reinforcements", 1);
        f.lastUpdatedEpochMs = section.getLong("last-updated", System.currentTimeMillis());
        return f;
    }

    public String promptLine() {
        return String.format("- [%s conf=%.2f x%d] %s", key, confidence, reinforcements, text);
    }
}
