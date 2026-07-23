package com.aibots.listener;

import com.aibots.crew.CrewBot;
import com.aibots.crew.CrewManager;
import com.aibots.skill.CombatSkill;
import com.aibots.skill.FarmerSkill;
import com.aibots.skill.HunterSkill;
import com.aibots.skill.ScavengeSkill;
import com.aibots.storage.ChestNetwork;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Route chat that mentions a bot name to that bot's LLM.
 * Imperative gather orders and storage questions are handled directly.
 */
public class ChatListener implements Listener {

    private static final Pattern HAS_ITEM = Pattern.compile(
            "(?i)\\b(?:do\\s+we\\s+have|have\\s+we|how\\s+many|got\\s+any|any)\\b\\s+(.+?)\\??$");
    private static final Pattern CHEST_LIST = Pattern.compile(
            "(?i)\\b(?:list\\s+(?:the\\s+)?chests?|what\\s+chests|storage\\s+list|show\\s+storage)\\b");
    private static final Pattern CHEST_NUM = Pattern.compile(
            "(?i)\\b(?:what(?:'s|\\s+is)\\s+in\\s+)?(?:chest|storage)\\s*#?\\s*(\\d+)\\b");

    private final JavaPlugin plugin;
    private final CrewManager crew;

    public ChatListener(JavaPlugin plugin, CrewManager crew) {
        this.plugin = plugin;
        this.crew = crew;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getConfig().getBoolean("crew.chat-mention-enabled", true)) {
            return;
        }
        String msg = event.getMessage();
        Optional<CrewBot> match = crew.matchMention(msg);
        if (match.isEmpty()) {
            return;
        }
        CrewBot bot = match.get();
        final String order = msg;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();

            // Storage Q&A before gather/LLM
            if (answerStorageQuestion(player, bot, order)) {
                return;
            }

            boolean workOrder = ScavengeSkill.looksLikeGather(order)
                    || CombatSkill.looksLikeCombat(order)
                    || HunterSkill.looksLikeHunt(order)
                    || FarmerSkill.looksLikeFarm(order);
            if (workOrder) {
                java.util.List<String> report = crew.assign(bot, order);
                if (report != null && !report.isEmpty()) {
                    for (String line : report) {
                        player.sendMessage(line);
                    }
                    if (bot.getStatus() != com.aibots.crew.BotStatus.BUSY) {
                        return;
                    }
                }
            }
            crew.talk(bot, player.getName() + " says: " + order, player);
        });
    }

    /** @return true if handled */
    private boolean answerStorageQuestion(Player player, CrewBot bot, String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        ChestNetwork net = crew.getChestNetwork();
        String name = bot.getName();

        if (CHEST_LIST.matcher(msg).find() || lower.contains("list chests")
                || lower.contains("how many chests")) {
            var units = net.listUnits();
            player.sendMessage(ChatColor.GOLD + name + ChatColor.WHITE + ": We have "
                    + units.size() + " storage chest(s), " + net.freeSlots() + " free slots.");
            for (var u : units) {
                player.sendMessage(ChatColor.AQUA + "  #" + u.number
                        + ChatColor.GRAY + (u.doubleChest ? " double" : " single")
                        + " @ " + u.location.getBlockX() + "," + u.location.getBlockY()
                        + "," + u.location.getBlockZ()
                        + ChatColor.YELLOW + " items=" + u.totalItems()
                        + ChatColor.DARK_GRAY + " free=" + u.freeSlots);
            }
            if (!units.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "  Ask: \"Rusty what's in chest 1\" or /crew storage 1");
            }
            return true;
        }

        Matcher chestM = CHEST_NUM.matcher(msg);
        if (chestM.find()) {
            int num = Integer.parseInt(chestM.group(1));
            var unit = net.unit(num);
            if (unit.isEmpty()) {
                player.sendMessage(ChatColor.GOLD + name + ChatColor.WHITE
                        + ": I don't have a chest #" + num + ". Try /crew storage list");
                return true;
            }
            var u = unit.get();
            player.sendMessage(ChatColor.GOLD + name + ChatColor.WHITE + ": Chest #" + u.number
                    + " has " + u.totalItems() + " items (" + u.freeSlots + " free slots):");
            if (u.contents.isEmpty()) {
                player.sendMessage(ChatColor.DARK_GRAY + "  (empty)");
            } else {
                u.contents.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .limit(20)
                        .forEach(e -> player.sendMessage(ChatColor.WHITE + "  • "
                                + friendly(e.getKey()) + ChatColor.YELLOW + " x" + e.getValue()));
            }
            return true;
        }

        Matcher hasM = HAS_ITEM.matcher(msg.replaceAll("(?i)" + Pattern.quote(name), " ").trim());
        // Also: "Rusty oak logs?" / "Rusty do you have iron"
        String itemQ = null;
        if (hasM.find()) {
            itemQ = hasM.group(1).trim()
                    .replaceAll("(?i)\\b(do we have|have we|how many|got any|any|left|in storage|in the chests?)\\b", "")
                    .replaceAll("[?!.]+$", "")
                    .trim();
        } else if (lower.matches(".*\\b(have|has|got|stock|how many)\\b.*")) {
            itemQ = msg.replaceAll("(?i)" + Pattern.quote(name), " ")
                    .replaceAll("(?i)\\b(do we|we|you|have|has|got|any|how many|is there|are there|left|in storage|in the chest|please|\\?)\\b", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
        }
        if (itemQ != null && itemQ.length() >= 2) {
            Map<Material, Integer> tally = net.tallyMatching(itemQ);
            int total = tally.values().stream().mapToInt(Integer::intValue).sum();
            if (total <= 0) {
                player.sendMessage(ChatColor.GOLD + name + ChatColor.WHITE
                        + ": We don't have any \"" + itemQ + "\" in storage right now.");
            } else {
                player.sendMessage(ChatColor.GOLD + name + ChatColor.WHITE + ": We have "
                        + ChatColor.YELLOW + total + ChatColor.WHITE + " \"" + itemQ + "\" in storage:");
                tally.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .forEach(e -> player.sendMessage(ChatColor.WHITE + "  • "
                                + friendly(e.getKey()) + ChatColor.YELLOW + " x" + e.getValue()));
                for (var u : net.listUnits()) {
                    int in = 0;
                    for (var e : u.contents.entrySet()) {
                        String n = e.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' ');
                        if (n.contains(itemQ.toLowerCase(Locale.ROOT))
                                || e.getKey().name().toLowerCase(Locale.ROOT)
                                .contains(itemQ.toLowerCase(Locale.ROOT).replace(' ', '_'))) {
                            in += e.getValue();
                        }
                    }
                    if (in > 0) {
                        player.sendMessage(ChatColor.AQUA + "  chest #" + u.number
                                + ChatColor.GRAY + ": " + in);
                    }
                }
            }
            return true;
        }
        return false;
    }

    private static String friendly(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
