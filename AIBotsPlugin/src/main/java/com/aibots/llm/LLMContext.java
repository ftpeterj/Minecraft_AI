package com.aibots.llm;

import com.aibots.crew.BotTitle;

import java.util.Objects;
import java.util.UUID;

/**
 * Request context for multi-LLM routing (role, task type, complexity).
 */
public final class LLMContext {

    public enum TaskType {
        CHAT,
        PLAN,
        DELEGATE,
        BUILD,
        COMBAT,
        GATHER,
        GENERAL
    }

    public enum Complexity {
        /** Short chat / simple reply — prefer fast local model */
        SIMPLE,
        /** Multi-step or structure plan — may escalate to stronger model */
        COMPLEX
    }

    private final String botName;
    private final UUID botId;
    private final BotTitle title;
    private final TaskType taskType;
    private final Complexity complexity;
    private final String preferredProviderId;

    private LLMContext(Builder b) {
        this.botName = b.botName;
        this.botId = b.botId;
        this.title = b.title;
        this.taskType = b.taskType != null ? b.taskType : TaskType.GENERAL;
        this.complexity = b.complexity != null ? b.complexity : Complexity.SIMPLE;
        this.preferredProviderId = b.preferredProviderId;
    }

    public String botName() {
        return botName;
    }

    public UUID botId() {
        return botId;
    }

    public BotTitle title() {
        return title;
    }

    public TaskType taskType() {
        return taskType;
    }

    public Complexity complexity() {
        return complexity;
    }

    public String preferredProviderId() {
        return preferredProviderId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String botName;
        private UUID botId;
        private BotTitle title;
        private TaskType taskType = TaskType.CHAT;
        private Complexity complexity = Complexity.SIMPLE;
        private String preferredProviderId;

        public Builder botName(String botName) {
            this.botName = botName;
            return this;
        }

        public Builder botId(UUID botId) {
            this.botId = botId;
            return this;
        }

        public Builder title(BotTitle title) {
            this.title = title;
            return this;
        }

        public Builder taskType(TaskType taskType) {
            this.taskType = taskType;
            return this;
        }

        public Builder complexity(Complexity complexity) {
            this.complexity = complexity;
            return this;
        }

        public Builder preferredProviderId(String preferredProviderId) {
            this.preferredProviderId = preferredProviderId;
            return this;
        }

        public LLMContext build() {
            return new LLMContext(this);
        }
    }

    @Override
    public String toString() {
        return "LLMContext{bot=" + botName + ", title=" + title
                + ", task=" + taskType + ", complexity=" + complexity
                + (preferredProviderId != null ? ", prefer=" + preferredProviderId : "")
                + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LLMContext that)) {
            return false;
        }
        return Objects.equals(botName, that.botName)
                && Objects.equals(botId, that.botId)
                && title == that.title
                && taskType == that.taskType
                && complexity == that.complexity
                && Objects.equals(preferredProviderId, that.preferredProviderId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(botName, botId, title, taskType, complexity, preferredProviderId);
    }
}
