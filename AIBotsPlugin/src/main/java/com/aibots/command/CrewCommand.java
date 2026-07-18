package com.aibots.command;

import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import com.aibots.crew.CrewManager;
import com.aibots.npc.NpcHandle;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public class CrewCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final CrewManager crew;

    public CrewCommand(JavaPlugin plugin, CrewManager crew) {
        this.plugin = plugin;
        this.crew = crew;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("aibots.crew")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "help" -> sendHelp(sender, label);
                case "summon", "spawn" -> summon(sender, args);
                case "dismiss", "remove", "kill" -> dismiss(sender, args);
                case "list", "ls" -> list(sender);
                case "title", "role" -> title(sender, args);
                case "skin" -> skin(sender, args);
                case "assign", "order" -> assign(sender, args);
                case "stop" -> stop(sender, args);
                case "home" -> home(sender, args);
                case "say", "talk" -> say(sender, args);
                case "broadcast", "bc" -> broadcast(sender, args);
                case "teach", "learn" -> teach(sender, args);
                case "memory", "brain" -> memory(sender, args);
                case "share" -> share(sender, args);
                case "purge" -> purge(sender);
                case "storage", "chests" -> storage(sender, args);
                case "reload" -> reload(sender);
                case "info" -> info(sender, args);
                default -> sender.sendMessage(ChatColor.YELLOW + "Unknown subcommand. Try /" + label + " help");
            }
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
        } catch (Exception ex) {
            sender.sendMessage(ChatColor.RED + "Error: " + ex.getMessage());
            plugin.getLogger().warning("Command error: " + ex.getMessage());
        }
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "=== AI Crew ===");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " summon <name> <title> [skin]");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " dismiss <name>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " list");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " title <name> <title>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " skin <name> <playerName>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " assign <name> <order...>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " stop <name>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " home <name>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " say <name> <message...>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " broadcast <message...>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " teach <name> [share] <fact...>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " memory <name>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " share <from> <to> <topic...>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " info <name>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " purge  " + ChatColor.GRAY + "(remove all crew + orphan Citizens NPCs)");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " storage fix  " + ChatColor.GRAY + "(merge adjacent singles into double chests)");
        sender.sendMessage(ChatColor.GRAY + "Titles: " + BotTitle.usageList());
        sender.sendMessage(ChatColor.GRAY + "All bots learn from teaching, chat, and experience (saved in learning.yml).");
        sender.sendMessage(ChatColor.GRAY + "Backend: " + (crew.getNpcService().usingCitizens() ? "Citizens" : "ArmorStand fallback"));
    }

    private void summon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew summon <name> <title> [skin]");
            return;
        }
        String name = args[1];
        BotTitle title = BotTitle.parse(args[2])
                .orElseThrow(() -> new IllegalArgumentException("Unknown title. Use " + BotTitle.usageList()));
        String skin = args.length >= 4 ? args[3] : null;
        CrewBot bot = crew.summon(player, name, title, skin);
        NpcHandle body = crew.bodyOf(bot);
        sender.sendMessage(ChatColor.GREEN + "Summoned " + bot.getName()
                + " as " + bot.getTitle().display()
                + " (skin=" + bot.getSkin() + ", body=" + (body == null ? "?" : body.backend()) + ")");
        sender.sendMessage(ChatColor.GRAY + "Talk: /crew say " + bot.getName() + " hello  — or mention their name in chat.");
    }

    private void dismiss(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew dismiss <name>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], true);
        crew.dismiss(bot);
        sender.sendMessage(ChatColor.GREEN + "Dismissed " + bot.getName() + ".");
    }

    private void list(CommandSender sender) {
        List<CrewBot> bots = new ArrayList<>(crew.allBots());
        if (bots.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No crew bots. Summon one with /crew summon <name> <title>");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Crew (" + bots.size() + "):");
        for (CrewBot bot : bots) {
            String ownerName = Optional.ofNullable(bot.getOwnerPlayer())
                    .map(Player::getName)
                    .orElse(bot.getOwnerId().toString().substring(0, 8));
            sender.sendMessage(ChatColor.AQUA + " • " + bot.getName()
                    + ChatColor.GRAY + " [" + bot.getTitle().display() + "] "
                    + ChatColor.WHITE + bot.getStatus().name()
                    + ChatColor.DARK_GRAY + " owner=" + ownerName
                    + " skin=" + bot.getSkin());
        }
    }

    private void title(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew title <name> <title>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], true);
        BotTitle title = BotTitle.parse(args[2])
                .orElseThrow(() -> new IllegalArgumentException("Unknown title. Use " + BotTitle.usageList()));
        crew.setTitle(bot, title);
        sender.sendMessage(ChatColor.GREEN + bot.getName() + " is now " + title.display() + ".");
    }

    private void skin(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew skin <name> <playerName>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], true);
        String skin = args[2];
        crew.setSkin(bot, skin);
        sender.sendMessage(ChatColor.GREEN + bot.getName() + " skin set to " + skin
                + (crew.getNpcService().usingCitizens() ? "" : " (ArmorStand head approx.)"));
    }

    private void assign(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew assign <name> <order...>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], true);
        String order = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        crew.assign(bot, order);
        sender.sendMessage(ChatColor.GREEN + "Assigned " + bot.getName() + ": " + order);
        // Phase 1: also have them acknowledge via LLM
        crew.talk(bot, "Your owner assigned you this order: " + order + ". Acknowledge briefly and say what you'll do.", sender);
    }

    private void stop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew stop <name>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], true);
        crew.stop(bot);
        sender.sendMessage(ChatColor.GREEN + bot.getName() + " stopped. Status=" + BotStatus.STOPPED);
    }

    private void home(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew home <name>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], true);
        crew.setHome(bot, player.getLocation());
        sender.sendMessage(ChatColor.GREEN + bot.getName() + " home set to your location.");
    }

    private void say(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew say <name> <message...>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], false);
        String msg = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        sender.sendMessage(ChatColor.GRAY + "Talking to " + bot.getName() + "…");
        crew.talk(bot, msg, sender);
    }

    private void broadcast(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew broadcast <message...>");
            return;
        }
        String msg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        List<CrewBot> owned = crew.botsOwnedBy(player.getUniqueId());
        if (owned.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "You have no bots.");
            return;
        }
        sender.sendMessage(ChatColor.GRAY + "Broadcasting to " + owned.size() + " bot(s)…");
        crew.broadcastToOwned(player, msg);
    }

    private void teach(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew teach <name> [share] <fact...>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], true);
        boolean share = args[2].equalsIgnoreCase("share");
        int start = share ? 3 : 2;
        if (args.length <= start) {
            sender.sendMessage(ChatColor.RED + "Provide a fact to teach.");
            return;
        }
        String fact = String.join(" ", Arrays.copyOfRange(args, start, args.length));
        String source = sender instanceof Player p ? p.getName() : "console";
        crew.teach(bot, fact, source, share);
        sender.sendMessage(ChatColor.GREEN + bot.getName() + " learned"
                + (share ? " (shared with crew)" : "") + ": " + ChatColor.WHITE + fact);
        crew.talk(bot, "I just taught you: " + fact + ". Confirm you remember it.", sender);
    }

    private void memory(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew memory <name>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], false);
        sender.sendMessage(ChatColor.GOLD + "=== " + bot.getName() + " memory ===");
        for (String line : crew.getLearning().memoryLines(bot, 20)) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
        if (!crew.getLearning().sharedFacts().isEmpty()) {
            sender.sendMessage(ChatColor.GOLD + "Crew shared:");
            crew.getLearning().sharedFacts().stream().limit(10).forEach(f ->
                    sender.sendMessage(ChatColor.DARK_AQUA + "  " + f.promptLine()));
        }
    }

    private void share(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew share <from> <to> <topic...>");
            return;
        }
        CrewBot from = requireBot(sender, args[1], true);
        CrewBot to = requireBot(sender, args[2], true);
        String topic = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        crew.shareKnowledge(from, to, topic);
        sender.sendMessage(ChatColor.GREEN + from.getName() + " shared knowledge with " + to.getName() + ".");
        crew.talk(to, from.getName() + " just taught you about: " + topic + ". Acknowledge briefly.", sender);
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew info <name>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], false);
        sender.sendMessage(ChatColor.GOLD + "--- " + bot.getName() + " ---");
        sender.sendMessage(ChatColor.YELLOW + "Title: " + bot.getTitle().display());
        sender.sendMessage(ChatColor.YELLOW + "Skin: " + bot.getSkin());
        sender.sendMessage(ChatColor.YELLOW + "Status: " + bot.getStatus());
        sender.sendMessage(ChatColor.YELLOW + "Order: " + (bot.getCurrentOrder() == null ? "-" : bot.getCurrentOrder()));
        sender.sendMessage(ChatColor.YELLOW + "Citizens id: " + bot.getCitizensNpcId());
        NpcHandle body = crew.bodyOf(bot);
        sender.sendMessage(ChatColor.YELLOW + "Body: " + (body == null ? "none" : body.backend() + " valid=" + body.isValid()));
        sender.sendMessage(ChatColor.DARK_GRAY + "Short memory: " + bot.memorySummary());
        long facts = crew.getLearning().brain(bot).facts().size();
        long eps = crew.getLearning().brain(bot).recentEpisodes(100).size();
        sender.sendMessage(ChatColor.YELLOW + "Learned facts: " + facts + " | recent episodes: " + eps);
        sender.sendMessage(ChatColor.GRAY + "Use /crew memory " + bot.getName() + " for details.");
    }

    private void purge(CommandSender sender) {
        if (!sender.hasPermission("aibots.admin") && !(sender instanceof Player)) {
            // allow players who own bots to purge their mess; admins always
        }
        int n = crew.purgeAll();
        sender.sendMessage(ChatColor.GREEN + "Purged crew entries / orphan Citizens NPCs (actions=" + n + ").");
        sender.sendMessage(ChatColor.GRAY + "If a ghost remains, look at it and run: /npc remove");
    }

    private void storage(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("fix")) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew storage fix");
            return;
        }
        crew.getChestNetwork().forceReconnectAll();
        sender.sendMessage(ChatColor.GREEN + "Tried to merge adjacent network chests into double chests. Look again.");
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("aibots.admin")) {
            sender.sendMessage(ChatColor.RED + "Admin only.");
            return;
        }
        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "config.yml reloaded. (bots.yml not wiped)");
    }

    private CrewBot requireBot(CommandSender sender, String name, boolean requireOwner) {
        CrewBot bot = crew.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("No bot named '" + name + "'."));
        if (requireOwner && sender instanceof Player player && !player.hasPermission("aibots.admin")) {
            if (!bot.getOwnerId().equals(player.getUniqueId())) {
                throw new IllegalArgumentException("You do not own " + bot.getName() + ".");
            }
        }
        return bot;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(args[0], List.of(
                    "help", "summon", "dismiss", "list", "title", "skin",
                    "assign", "stop", "home", "say", "broadcast", "teach", "memory",
                    "share", "info", "purge", "storage", "reload"
            ));
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("dismiss", "title", "skin", "assign", "stop", "home", "say", "info",
                    "teach", "memory", "share").contains(sub)) {
                return filter(args[1], crew.allBots().stream().map(CrewBot::getName).collect(Collectors.toList()));
            }
            if (sub.equals("summon")) {
                return List.of("<name>");
            }
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("summon") || sub.equals("title") || sub.equals("role")) {
                return filter(args[2], List.of("scavenger", "warrior", "builder", "farmer"));
            }
            if (sub.equals("skin")) {
                return filter(args[2], List.of("Steve", "Alex"));
            }
            if (sub.equals("teach")) {
                return filter(args[2], List.of("share"));
            }
            if (sub.equals("share")) {
                return filter(args[2], crew.allBots().stream().map(CrewBot::getName).collect(Collectors.toList()));
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("summon")) {
            return filter(args[3], List.of("Steve", "Alex"));
        }
        return List.of();
    }

    private static List<String> filter(String prefix, List<String> options) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p))
                .collect(Collectors.toList());
    }
}
