package com.aibots.crew;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Shared crew job queue — post work, idle bots claim by title/owner.
 */
public final class CrewJobBoard {

    private final JavaPlugin plugin;
    private final Map<UUID, CrewJob> jobs = new ConcurrentHashMap<>();
    /** Bot currently working a claimed job (botId → jobId). */
    private final Map<UUID, UUID> activeByBot = new ConcurrentHashMap<>();
    private final int maxJobs;
    private final long claimTimeoutMs;

    public CrewJobBoard(JavaPlugin plugin) {
        this.plugin = plugin;
        this.maxJobs = Math.max(8, plugin.getConfig().getInt("crew.job-board.max-jobs", 64));
        this.claimTimeoutMs = Math.max(30_000L,
                plugin.getConfig().getLong("crew.job-board.claim-timeout-seconds", 300) * 1000L);
    }

    public CrewJob post(UUID ownerId, UUID requesterBotId, BotTitle preferredTitle,
                        String description, int priority) {
        pruneFinished();
        if (jobs.size() >= maxJobs) {
            // Drop oldest finished first already done; if still full drop oldest OPEN
            jobs.values().stream()
                    .filter(CrewJob::isOpen)
                    .min(Comparator.comparingLong(CrewJob::createdAtMs))
                    .ifPresent(j -> {
                        j.cancel("board full");
                        jobs.remove(j.id());
                    });
        }
        CrewJob job = new CrewJob(ownerId, requesterBotId, preferredTitle, description, priority);
        jobs.put(job.id(), job);
        plugin.getLogger().fine("[Jobs] posted " + job.summaryLine());
        return job;
    }

    public Optional<CrewJob> find(String shortOrFullId) {
        if (shortOrFullId == null || shortOrFullId.isBlank()) {
            return Optional.empty();
        }
        String key = shortOrFullId.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("#")) {
            key = key.substring(1);
        }
        for (CrewJob j : jobs.values()) {
            if (j.id().toString().equalsIgnoreCase(key) || j.shortId().equalsIgnoreCase(key)) {
                return Optional.of(j);
            }
        }
        return Optional.empty();
    }

    public Optional<CrewJob> activeFor(UUID botId) {
        UUID jid = activeByBot.get(botId);
        if (jid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(jobs.get(jid));
    }

    /**
     * Claim the best open job for this bot (same owner, matching title, highest priority).
     */
    public Optional<CrewJob> tryClaim(CrewBot bot) {
        if (bot == null || bot.getStatus() == BotStatus.STOPPED || bot.getStatus() == BotStatus.DISMISSED) {
            return Optional.empty();
        }
        if (activeByBot.containsKey(bot.getId())) {
            return Optional.empty();
        }
        // Already has work / pending survey choice
        if (bot.getCurrentOrder() != null && !bot.getCurrentOrder().isBlank()) {
            return Optional.empty();
        }
        if (bot.getStatus() == BotStatus.BUSY) {
            return Optional.empty();
        }

        List<CrewJob> open = jobs.values().stream()
                .filter(CrewJob::isOpen)
                .filter(j -> j.ownerId().equals(bot.getOwnerId()))
                .filter(j -> j.matchesTitle(bot.getTitle()))
                .sorted(Comparator
                        .comparingInt(CrewJob::priority).reversed()
                        .thenComparingLong(CrewJob::createdAtMs))
                .collect(Collectors.toList());

        if (open.isEmpty()) {
            return Optional.empty();
        }
        CrewJob job = open.get(0);
        job.claim(bot.getId());
        activeByBot.put(bot.getId(), job.id());
        return Optional.of(job);
    }

    public void completeForBot(UUID botId, String note) {
        UUID jid = activeByBot.remove(botId);
        if (jid == null) {
            return;
        }
        CrewJob job = jobs.get(jid);
        if (job != null && job.status() == CrewJob.Status.CLAIMED) {
            job.complete(note == null ? "done" : note);
        }
    }

    public void failForBot(UUID botId, String note) {
        UUID jid = activeByBot.remove(botId);
        if (jid == null) {
            return;
        }
        CrewJob job = jobs.get(jid);
        if (job != null && job.status() == CrewJob.Status.CLAIMED) {
            job.fail(note == null ? "failed" : note);
        }
    }

    public boolean cancel(String shortOrFullId, String note) {
        Optional<CrewJob> opt = find(shortOrFullId);
        if (opt.isEmpty()) {
            return false;
        }
        CrewJob job = opt.get();
        if (job.assigneeBotId() != null) {
            activeByBot.remove(job.assigneeBotId(), job.id());
        }
        if (job.isActive()) {
            job.cancel(note == null ? "cancelled" : note);
        }
        return true;
    }

    public void releaseBot(UUID botId) {
        UUID jid = activeByBot.remove(botId);
        if (jid == null) {
            return;
        }
        CrewJob job = jobs.get(jid);
        if (job != null && job.status() == CrewJob.Status.CLAIMED) {
            // Return to board so another bot can pick up
            job.reopen();
        }
    }

    /** Stale CLAIMED jobs re-open. */
    public int reclaimTimedOut() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (CrewJob job : jobs.values()) {
            if (job.status() != CrewJob.Status.CLAIMED) {
                continue;
            }
            if (now - job.claimedAtMs() < claimTimeoutMs) {
                continue;
            }
            UUID bot = job.assigneeBotId();
            if (bot != null) {
                activeByBot.remove(bot, job.id());
            }
            job.reopen();
            n++;
        }
        return n;
    }

    public List<CrewJob> listForOwner(UUID ownerId, boolean activeOnly) {
        return jobs.values().stream()
                .filter(j -> ownerId == null || j.ownerId().equals(ownerId))
                .filter(j -> !activeOnly || j.isActive())
                .sorted(Comparator
                        .comparingInt(CrewJob::priority).reversed()
                        .thenComparingLong(CrewJob::createdAtMs))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public List<CrewJob> listOpen(UUID ownerId) {
        return jobs.values().stream()
                .filter(CrewJob::isOpen)
                .filter(j -> ownerId == null || j.ownerId().equals(ownerId))
                .sorted(Comparator
                        .comparingInt(CrewJob::priority).reversed()
                        .thenComparingLong(CrewJob::createdAtMs))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void pruneFinished() {
        long cutoff = System.currentTimeMillis() - 3_600_000L; // 1h
        jobs.values().removeIf(j ->
                !j.isActive()
                        && j.finishedAtMs() > 0
                        && j.finishedAtMs() < cutoff);
    }

    public int size() {
        return jobs.size();
    }
}
