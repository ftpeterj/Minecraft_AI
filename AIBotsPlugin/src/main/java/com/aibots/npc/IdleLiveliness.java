package com.aibots.npc;

import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cosmetic idle behavior (wander / look-at-player / ambient emote) for bots with
 * no active order. Purely for "not a frozen statue" feel — never issues work,
 * never moves a bot outside a small home-anchored radius. Titles with
 * auto-when-idle already have real idle work and are skipped here so the two
 * systems never fight over the same lookAt/walkTo call in the same tick.
 */
public final class IdleLiveliness {

    private static final List<String> GENERIC_EMOTES = List.of(
            "checks their tools",
            "glances around",
            "hums quietly",
            "stretches",
            "watches the treeline",
            "adjusts their pack"
    );

    private static final Map<BotTitle, List<String>> TITLE_EMOTES = Map.of(
            BotTitle.GATHERER, List.of("taps a pickaxe against a rock", "eyes the nearest cliff face",
                    "runs a hand along the bark", "eyes a nearby tree", "checks the soil", "eyes the crop rows"),
            BotTitle.DEFENDER, List.of("checks their blade's edge", "keeps a wary stance",
                    "scans the treeline for game", "eyes the half-finished wall", "sketches something in the dirt")
    );

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private final Random random = new Random();
    private final Map<UUID, Long> nextWanderAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextLookAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextEmoteAt = new ConcurrentHashMap<>();

    public IdleLiveliness(JavaPlugin plugin, NpcService npcService) {
        this.plugin = plugin;
        this.npcService = npcService;
    }

    public void tick(Map<UUID, CrewBot> botsById) {
        if (!plugin.getConfig().getBoolean("crew.idle-liveliness.enabled", true)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (CrewBot bot : botsById.values()) {
            if (!eligible(bot)) {
                continue;
            }
            NpcHandle handle = npcService.get(bot.getId());
            if (handle == null || !handle.isValid() || handle.isWalking()) {
                continue;
            }
            maybeWander(bot, handle, now);
            maybeLook(bot, handle, now);
            maybeEmote(bot, now);
        }
    }

    private boolean eligible(CrewBot bot) {
        if (bot.getStatus() != BotStatus.IDLE || bot.getTitle() == null) {
            return false;
        }
        String order = bot.getCurrentOrder();
        if (order != null && !order.isBlank()) {
            return false;
        }
        String tkey = bot.getTitle().name().toLowerCase(Locale.ROOT);
        // Titles with auto-when-idle already move/act on their own while idle
        // (scavenger auto-gather, protector auto-patrol) — don't layer this on top.
        return !plugin.getConfig().getBoolean("titles." + tkey + ".auto-when-idle", false);
    }

    private void maybeWander(CrewBot bot, NpcHandle handle, long now) {
        String backend = handle.backend();
        if (!"citizens".equals(backend) && !"villager".equals(backend)) {
            // ArmorStand walkTo falls back to an instant teleport — skip wander,
            // still let look/emote run for it below.
            return;
        }
        if (!due(nextWanderAt, bot.getId(), now)) {
            return;
        }
        Location anchor = bot.getHome() != null ? bot.getHome() : bot.getLastLocation();
        if (anchor != null) {
            int radius = plugin.getConfig().getInt("crew.idle-liveliness.wander-radius", 5);
            Location dest = NpcLocations.findDryStandNear(anchor, Math.max(2, radius));
            if (dest != null) {
                handle.walkTo(dest, 0.7);
            }
        }
        scheduleNext(nextWanderAt, bot.getId(), now, "wander-interval-seconds", 60);
    }

    private void maybeLook(CrewBot bot, NpcHandle handle, long now) {
        if (!due(nextLookAt, bot.getId(), now)) {
            return;
        }
        Location loc = handle.getLocation();
        if (loc != null && loc.getWorld() != null) {
            int radius = plugin.getConfig().getInt("crew.idle-liveliness.look-radius", 10);
            double bestDistSq = (double) radius * radius;
            Player nearest = null;
            for (Player p : loc.getWorld().getPlayers()) {
                double d = p.getLocation().distanceSquared(loc);
                if (d < bestDistSq) {
                    bestDistSq = d;
                    nearest = p;
                }
            }
            if (nearest != null) {
                handle.lookAt(nearest.getEyeLocation());
            }
        }
        scheduleNext(nextLookAt, bot.getId(), now, "look-interval-seconds", 10);
    }

    private void maybeEmote(CrewBot bot, long now) {
        if (!due(nextEmoteAt, bot.getId(), now)) {
            return;
        }
        scheduleNext(nextEmoteAt, bot.getId(), now, "emote-interval-seconds", 120);
        double chance = plugin.getConfig().getDouble("crew.idle-liveliness.emote-chance", 0.4);
        if (random.nextDouble() > chance) {
            return;
        }
        Player owner = bot.getOwnerPlayer();
        if (owner == null || !owner.isOnline()) {
            return;
        }
        List<String> pool = random.nextBoolean()
                ? TITLE_EMOTES.getOrDefault(bot.getTitle(), GENERIC_EMOTES)
                : GENERIC_EMOTES;
        String line = pool.get(random.nextInt(pool.size()));
        Bukkit.broadcastMessage("§f<" + bot.getName() + "> " + line);
    }

    private boolean due(Map<UUID, Long> schedule, UUID id, long now) {
        Long at = schedule.get(id);
        return at == null || now >= at;
    }

    /** Randomize +/-40% so bots don't all act in lockstep. */
    private void scheduleNext(Map<UUID, Long> schedule, UUID id, long now, String configKey, int defaultSeconds) {
        int seconds = plugin.getConfig().getInt("crew.idle-liveliness." + configKey, defaultSeconds);
        double jitter = 0.6 + random.nextDouble() * 0.8;
        long delayMs = Math.max(1000L, (long) (seconds * 1000L * jitter));
        schedule.put(id, now + delayMs);
    }
}
