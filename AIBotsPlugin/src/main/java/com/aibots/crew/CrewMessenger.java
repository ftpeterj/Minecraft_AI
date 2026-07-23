package com.aibots.crew;

import com.aibots.learn.LearningService;
import com.aibots.storage.ChestNetwork;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Inter-bot messaging and simple task delegation (Phase 1).
 * Bots request help by title/name; recipients auto-pick up NEED_MATERIAL / DELEGATE.
 */
public final class CrewMessenger {

    private final JavaPlugin plugin;
    private final Function<UUID, Optional<CrewBot>> botById;
    private final Function<String, Optional<CrewBot>> botByName;
    private final Function<UUID, List<CrewBot>> botsOwnedBy;
    private final LearningService learning;
    private final ChestNetwork chests;
    private final Map<UUID, List<BotMessage>> inbox = new ConcurrentHashMap<>();

    public CrewMessenger(
            JavaPlugin plugin,
            Function<UUID, Optional<CrewBot>> botById,
            Function<String, Optional<CrewBot>> botByName,
            Function<UUID, List<CrewBot>> botsOwnedBy,
            LearningService learning,
            ChestNetwork chests) {
        this.plugin = plugin;
        this.botById = botById;
        this.botByName = botByName;
        this.botsOwnedBy = botsOwnedBy;
        this.learning = learning;
        this.chests = chests;
    }

    public void send(CrewBot from, CrewBot to, BotMessage.Kind kind, String body) {
        if (from == null || to == null) {
            return;
        }
        BotMessage msg = new BotMessage(from.getId(), to.getId(), kind, body);
        inbox.computeIfAbsent(to.getId(), k -> new ArrayList<>()).add(msg);
        from.remember("To " + to.getName() + ": " + body);
        learning.observe(from, "msg_send", "To " + to.getName() + " (" + kind + "): " + truncate(body, 60),
                true, kind.name());
        announce(from, to, kind, body);
    }

    /**
     * Find a teammate with the given title under the same owner and send a request.
     *
     * @return target bot name if delivered, empty if none available
     */
    public Optional<String> requestByTitle(CrewBot from, BotTitle neededTitle, String request) {
        if (from == null || neededTitle == null) {
            return Optional.empty();
        }
        List<CrewBot> owned = botsOwnedBy.apply(from.getOwnerId());
        CrewBot best = null;
        for (CrewBot b : owned) {
            if (b.getId().equals(from.getId())) {
                continue;
            }
            if (b.getTitle() == neededTitle
                    && b.getStatus() != BotStatus.DISMISSED
                    && b.getStatus() != BotStatus.STOPPED) {
                if (best == null || b.getStatus() == BotStatus.IDLE) {
                    best = b;
                    if (b.getStatus() == BotStatus.IDLE) {
                        break;
                    }
                }
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        send(from, best, BotMessage.Kind.REQUEST, request);
        return Optional.of(best.getName());
    }

    /** Ask gatherers for a material (storage short). */
    public Optional<String> needMaterial(CrewBot from, Material material, int amount) {
        String text = "Need " + amount + "x " + pretty(material) + " for my work.";
        String body = "NEED " + material.name() + " " + amount + " | " + text;
        BotTitle prefer = preferTitleFor(material);
        Optional<CrewBot> target = findTeammate(from, prefer);
        if (target.isEmpty()) {
            for (BotTitle t : List.of(BotTitle.SCAVENGER, BotTitle.MINER, BotTitle.WOODSMAN)) {
                target = findTeammate(from, t);
                if (target.isPresent()) {
                    break;
                }
            }
        }
        if (target.isEmpty()) {
            return Optional.empty();
        }
        send(from, target.get(), BotMessage.Kind.NEED_MATERIAL, body);
        return Optional.of(target.get().getName());
    }

    private Optional<CrewBot> findTeammate(CrewBot from, BotTitle neededTitle) {
        if (from == null || neededTitle == null) {
            return Optional.empty();
        }
        List<CrewBot> owned = botsOwnedBy.apply(from.getOwnerId());
        CrewBot best = null;
        for (CrewBot b : owned) {
            if (b.getId().equals(from.getId())) {
                continue;
            }
            if (b.getTitle() == neededTitle
                    && b.getStatus() != BotStatus.DISMISSED
                    && b.getStatus() != BotStatus.STOPPED) {
                if (best == null || b.getStatus() == BotStatus.IDLE) {
                    best = b;
                    if (b.getStatus() == BotStatus.IDLE) {
                        break;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Process inbox for one bot — auto-start gather/work on NEED_MATERIAL / DELEGATE.
     *
     * @return human-readable lines for optional player feedback
     */
    public List<String> processInbox(CrewBot bot, AssignHook assign) {
        List<String> lines = new ArrayList<>();
        List<BotMessage> box = inbox.get(bot.getId());
        if (box == null || box.isEmpty()) {
            return lines;
        }
        List<BotMessage> pending = new ArrayList<>(box);
        box.clear();
        for (BotMessage msg : pending) {
            if (msg.isConsumed()) {
                continue;
            }
            Optional<CrewBot> fromOpt = botById.apply(msg.fromBotId());
            String fromName = fromOpt.map(CrewBot::getName).orElse("teammate");
            bot.remember("From " + fromName + ": " + msg.body());
            learning.observe(bot, "msg_recv", "From " + fromName + ": " + truncate(msg.body(), 60),
                    true, msg.kind().name());

            switch (msg.kind()) {
                case NEED_MATERIAL -> {
                    String order = parseNeedOrder(msg.body());
                    if (order != null && assign != null && bot.getTitle() != null && bot.getTitle().isGatherer()) {
                        if (bot.getStatus() == BotStatus.IDLE || bot.getStatus() == BotStatus.BUSY) {
                            List<String> r = assign.assign(bot, order);
                            lines.addAll(r);
                            lines.add(ChatColor.DARK_AQUA + bot.getName() + " accepted material request from "
                                    + fromName + ".");
                        }
                    }
                }
                case DELEGATE, REQUEST -> {
                    if (assign != null && shouldAutoAccept(bot, msg)) {
                        String order = stripMeta(msg.body());
                        if (!order.isBlank() && bot.getStatus() == BotStatus.IDLE) {
                            List<String> r = assign.assign(bot, order);
                            lines.addAll(r);
                            lines.add(ChatColor.DARK_AQUA + bot.getName() + " accepted request from "
                                    + fromName + ".");
                        }
                    }
                }
                case STATUS, CHAT -> {
                    // Logged to memory only
                }
            }
            msg.markConsumed();
        }
        return lines;
    }

    public int pendingCount(UUID botId) {
        List<BotMessage> box = inbox.get(botId);
        return box == null ? 0 : box.size();
    }

    public void clearFor(UUID botId) {
        inbox.remove(botId);
    }

    @FunctionalInterface
    public interface AssignHook {
        List<String> assign(CrewBot bot, String order);
    }

    private void announce(CrewBot from, CrewBot to, BotMessage.Kind kind, String body) {
        if (!plugin.getConfig().getBoolean("crew.inter-bot-announce", true)) {
            return;
        }
        String line = ChatColor.DARK_AQUA + "[Crew] " + ChatColor.AQUA + from.getName()
                + ChatColor.GRAY + " → " + ChatColor.AQUA + to.getName()
                + ChatColor.DARK_GRAY + " (" + kind.name().toLowerCase(Locale.ROOT) + ") "
                + ChatColor.WHITE + truncate(body, 80);
        Player owner = Bukkit.getPlayer(from.getOwnerId());
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(line);
        }
    }

    private static boolean shouldAutoAccept(CrewBot bot, BotMessage msg) {
        // Auto-accept if idle gatherer/farmer/combat when message looks like an order
        if (bot.getStatus() != BotStatus.IDLE) {
            return false;
        }
        String b = msg.body().toLowerCase(Locale.ROOT);
        return b.contains("gather") || b.contains("mine") || b.contains("chop")
                || b.contains("hunt") || b.contains("farm") || b.contains("guard")
                || b.contains("build") || b.contains("need") || b.contains("get ")
                || b.contains("fetch") || b.contains("collect");
    }

    private static String parseNeedOrder(String body) {
        if (body == null) {
            return null;
        }
        // NEED OAK_LOG 16 | text
        String[] parts = body.split("\\|", 2);
        String head = parts[0].trim();
        if (head.toUpperCase(Locale.ROOT).startsWith("NEED ")) {
            String rest = head.substring(5).trim();
            String[] bits = rest.split("\\s+");
            if (bits.length >= 1) {
                String mat = bits[0].toLowerCase(Locale.ROOT).replace('_', ' ');
                return "gather " + mat;
            }
        }
        return stripMeta(body);
    }

    private static String stripMeta(String body) {
        if (body == null) {
            return "";
        }
        int bar = body.indexOf('|');
        if (bar >= 0 && bar + 1 < body.length()) {
            return body.substring(bar + 1).trim();
        }
        return body.trim();
    }

    private static BotTitle preferTitleFor(Material m) {
        if (m == null) {
            return BotTitle.SCAVENGER;
        }
        String n = m.name();
        if (n.contains("LOG") || n.contains("WOOD") || n.contains("LEAVES") || n.contains("SAPLING")) {
            return BotTitle.WOODSMAN;
        }
        if (n.contains("ORE") || n.contains("STONE") || n.contains("DEEPSLATE") || n.contains("COBBLE")
                || n.contains("IRON") || n.contains("DIAMOND") || n.contains("COAL")) {
            return BotTitle.MINER;
        }
        return BotTitle.SCAVENGER;
    }

    private static String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
