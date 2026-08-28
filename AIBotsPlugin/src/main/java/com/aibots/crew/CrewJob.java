package com.aibots.crew;

import java.util.Locale;
import java.util.UUID;

/**
 * One unit of work on the shared crew job board.
 */
public final class CrewJob {

    public enum Status {
        OPEN,
        CLAIMED,
        DONE,
        FAILED,
        CANCELLED
    }

    private final UUID id;
    private final UUID ownerId;
    private final UUID requesterBotId;
    private final BotTitle preferredTitle;
    private final String description;
    private final int priority;
    private final long createdAtMs;
    private Status status;
    private UUID assigneeBotId;
    private long claimedAtMs;
    private long finishedAtMs;
    private String resultNote;

    public CrewJob(
            UUID ownerId,
            UUID requesterBotId,
            BotTitle preferredTitle,
            String description,
            int priority) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.requesterBotId = requesterBotId;
        this.preferredTitle = preferredTitle;
        this.description = description == null ? "" : description.trim();
        this.priority = priority;
        this.createdAtMs = System.currentTimeMillis();
        this.status = Status.OPEN;
    }

    public UUID id() {
        return id;
    }

    /** Short id for chat (first 8 hex chars). */
    public String shortId() {
        return id.toString().substring(0, 8);
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID requesterBotId() {
        return requesterBotId;
    }

    public BotTitle preferredTitle() {
        return preferredTitle;
    }

    public String description() {
        return description;
    }

    public int priority() {
        return priority;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public Status status() {
        return status;
    }

    public UUID assigneeBotId() {
        return assigneeBotId;
    }

    public long claimedAtMs() {
        return claimedAtMs;
    }

    public long finishedAtMs() {
        return finishedAtMs;
    }

    public String resultNote() {
        return resultNote;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    public boolean isActive() {
        return status == Status.OPEN || status == Status.CLAIMED;
    }

    public boolean matchesTitle(BotTitle title) {
        if (preferredTitle == null) {
            return true;
        }
        return preferredTitle == title;
    }

    public void claim(UUID botId) {
        this.assigneeBotId = botId;
        this.status = Status.CLAIMED;
        this.claimedAtMs = System.currentTimeMillis();
    }

    public void complete(String note) {
        this.status = Status.DONE;
        this.finishedAtMs = System.currentTimeMillis();
        this.resultNote = note;
    }

    public void fail(String note) {
        this.status = Status.FAILED;
        this.finishedAtMs = System.currentTimeMillis();
        this.resultNote = note;
    }

    public void cancel(String note) {
        this.status = Status.CANCELLED;
        this.finishedAtMs = System.currentTimeMillis();
        this.resultNote = note;
    }

    public void reopen() {
        this.status = Status.OPEN;
        this.assigneeBotId = null;
        this.claimedAtMs = 0;
        this.finishedAtMs = 0;
        this.resultNote = null;
    }

    public String summaryLine() {
        String title = preferredTitle == null ? "any" : preferredTitle.display().toLowerCase(Locale.ROOT);
        String who = assigneeBotId == null ? "-" : assigneeBotId.toString().substring(0, 8);
        return "#" + shortId() + " [" + status.name() + "] " + title + " :: " + description
                + (assigneeBotId != null ? " @" + who : "");
    }
}
