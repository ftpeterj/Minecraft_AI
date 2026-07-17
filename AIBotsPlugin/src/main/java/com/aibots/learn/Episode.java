package com.aibots.learn;

import org.bukkit.configuration.ConfigurationSection;

/** One experience event used for reflection and stats. */
public class Episode {

    private final long timeEpochMs;
    private final String type;      // scavenge, deposit, chat, teach, combat, build, farm, help
    private final String summary;
    private final boolean success;
    private final String detail;

    public Episode(String type, String summary, boolean success, String detail) {
        this.timeEpochMs = System.currentTimeMillis();
        this.type = type == null ? "event" : type;
        this.summary = summary == null ? "" : summary;
        this.success = success;
        this.detail = detail == null ? "" : detail;
    }

    private Episode(long time, String type, String summary, boolean success, String detail) {
        this.timeEpochMs = time;
        this.type = type;
        this.summary = summary;
        this.success = success;
        this.detail = detail;
    }

    public long getTimeEpochMs() {
        return timeEpochMs;
    }

    public String getType() {
        return type;
    }

    public String getSummary() {
        return summary;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getDetail() {
        return detail;
    }

    public void saveTo(ConfigurationSection section) {
        section.set("time", timeEpochMs);
        section.set("type", type);
        section.set("summary", summary);
        section.set("success", success);
        section.set("detail", detail);
    }

    public static Episode loadFrom(ConfigurationSection section) {
        return new Episode(
                section.getLong("time", System.currentTimeMillis()),
                section.getString("type", "event"),
                section.getString("summary", ""),
                section.getBoolean("success", true),
                section.getString("detail", "")
        );
    }

    public String promptLine() {
        return (success ? "OK" : "FAIL") + " " + type + ": " + summary
                + (detail.isBlank() ? "" : " (" + detail + ")");
    }
}
