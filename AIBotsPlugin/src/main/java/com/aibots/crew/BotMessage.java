package com.aibots.crew;

import java.util.UUID;

/**
 * Inter-bot collaboration message (Phase 1 messaging).
 */
public final class BotMessage {

    public enum Kind {
        /** Ask a teammate for help (e.g. need wood). */
        REQUEST,
        /** Explicit task hand-off. */
        DELEGATE,
        /** Status / info for a teammate. */
        STATUS,
        /** Need a material from gatherers. */
        NEED_MATERIAL,
        /** Free-form chat between bots. */
        CHAT
    }

    private final UUID id;
    private final UUID fromBotId;
    private final UUID toBotId;
    private final Kind kind;
    private final String body;
    private final long createdAtMs;
    private boolean consumed;

    public BotMessage(UUID fromBotId, UUID toBotId, Kind kind, String body) {
        this.id = UUID.randomUUID();
        this.fromBotId = fromBotId;
        this.toBotId = toBotId;
        this.kind = kind == null ? Kind.CHAT : kind;
        this.body = body == null ? "" : body;
        this.createdAtMs = System.currentTimeMillis();
        this.consumed = false;
    }

    public UUID id() {
        return id;
    }

    public UUID fromBotId() {
        return fromBotId;
    }

    public UUID toBotId() {
        return toBotId;
    }

    public Kind kind() {
        return kind;
    }

    public String body() {
        return body;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public void markConsumed() {
        this.consumed = true;
    }
}
