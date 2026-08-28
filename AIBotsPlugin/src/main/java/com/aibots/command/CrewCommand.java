package com.aibots.command;

import com.aibots.AIBotsPlugin;
import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import com.aibots.crew.CrewManager;
import com.aibots.llm.LLMProvider;
import com.aibots.llm.LLMRouter;
import com.aibots.npc.NpcHandle;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
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
                case "msg", "message", "tell", "ask" -> interBotMsg(sender, args);
                case "jobs", "job", "board" -> jobs(sender, args);
                case "purge" -> purge(sender);
                case "storage", "chests", "stock", "has" -> storage(sender, args);
                case "deposit", "dump" -> deposit(sender, args);
                case "give", "put" -> giveLoot(sender, args);
                case "radius", "range", "workradius" -> radius(sender, args);
                case "reload" -> reload(sender);
                case "llm" -> llmStatus(sender);
                case "healtrees", "healtree", "fixtrees", "restoretress" -> healTrees(sender, args);
                case "info" -> info(sender, args);
                case "inv", "inventory", "bag", "loot" -> inventory(sender, args);
                case "here", "come", "tp" -> here(sender, args);
                case "find", "where" -> find(sender, args);
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
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " msg <from> <to> <message...>  "
                + ChatColor.GRAY + "(inter-bot request/delegate)");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " jobs  "
                + ChatColor.GRAY + "list | post [title] <order…> | cancel <id>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " info <name>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " inv <name> [open]  "
                + ChatColor.GRAY + "(list loot bag; open = GUI)");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " here <name>  " + ChatColor.GRAY + "(bring bot to you)");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " find <name>  " + ChatColor.GRAY + "(coords + respawn body if missing)");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " purge  " + ChatColor.GRAY + "(remove all crew + orphan Citizens NPCs)");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " storage  " + ChatColor.GRAY
                + "list | register | pos1 | pos2 | clear | has | <#>");
        sender.sendMessage(ChatColor.YELLOW + "/" + label
                + " storage register <x1> <y1> <z1> <x2> <y2> <z2>");
        sender.sendMessage(ChatColor.GRAY
                + "  Same box as /fill: every chest/double/barrel/shulker inside is linked");
        sender.sendMessage(ChatColor.GRAY
                + "  Or: pos1 (look at corner) → pos2 (opposite corner) → register");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " deposit <name>  "
                + ChatColor.GRAY + "(force bag → network now)");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " radius [session|default|clear] [blocks]  "
                + ChatColor.GRAY + "(work search distance; warn >200)");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " has <item>  " + ChatColor.GRAY + "(how many in network)");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " llm  " + ChatColor.GRAY + "(current Ollama / LM Studio / cloud)");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " healtrees [world|radius]  "
                + ChatColor.GRAY + "(put wood back on cut trees)");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload  " + ChatColor.GRAY + "(re-read config.yml including LLM)");
        sender.sendMessage(ChatColor.GRAY + "Titles: " + BotTitle.usageList());
        sender.sendMessage(ChatColor.GRAY + "Builder: /crew assign Bob wall 5 cobble | platform 3x3 oak | pillar 4 | box 4x3x3");
        sender.sendMessage(ChatColor.GRAY + "All bots learn from teaching, chat, and experience (saved in learning.yml).");
        String mode = plugin.getConfig().getString("crew.avatar-mode", "villager");
        sender.sendMessage(ChatColor.GRAY + "Avatar mode: " + mode
                + (crew.getNpcService().usingCitizens() ? " (Citizens)" : ""));
    }

    private void summon(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew summon <name> <title> [skin]");
            sender.sendMessage(ChatColor.GRAY + "Console: /crew summon <name> <title> at <x> <y> <z> [world]");
            return;
        }
        String name = args[1];
        BotTitle title = BotTitle.parse(args[2])
                .orElseThrow(() -> new IllegalArgumentException("Unknown title. Use " + BotTitle.usageList()));

        // Console / automation: /crew summon Auto scavenger at x y z [world]
        if (!(sender instanceof Player) && args.length >= 7 && args[3].equalsIgnoreCase("at")) {
            try {
                int x = Integer.parseInt(args[4]);
                int y = Integer.parseInt(args[5]);
                int z = Integer.parseInt(args[6]);
                org.bukkit.World world = args.length >= 8
                        ? Bukkit.getWorld(args[7])
                        : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
                if (world == null) {
                    throw new IllegalArgumentException("World not found.");
                }
                Location loc = new Location(world, x + 0.5, y, z + 0.5);
                world.getChunkAt(loc).load(true);
                CrewBot bot = crew.summonAt(
                        new java.util.UUID(0L, 1L), "Console", loc, loc, name, title, null);
                NpcHandle body = crew.bodyOf(bot);
                sender.sendMessage(ChatColor.GREEN + "Summoned " + bot.getName()
                        + " as " + bot.getTitle().display()
                        + " @ " + x + "," + y + "," + z
                        + " body=" + (body == null ? "?" : body.backend()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid coordinates.");
            }
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players: /crew summon <name> <title> [skin]");
            sender.sendMessage(ChatColor.RED + "Console: /crew summon <name> <title> at <x> <y> <z> [world]");
            return;
        }
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
        String name = args[1];
        // Registry bot → full dismiss; missing from bots.yml → still purge world bodies
        var found = crew.findByName(name);
        if (found.isPresent()) {
            CrewBot bot = found.get();
            if (sender instanceof Player player && !player.hasPermission("aibots.admin")) {
                if (!bot.getOwnerId().equals(player.getUniqueId())) {
                    throw new IllegalArgumentException("You do not own " + bot.getName() + ".");
                }
            }
            crew.dismiss(bot);
            sender.sendMessage(ChatColor.GREEN + "Dismissed " + bot.getName() + ".");
            return;
        }
        int removed = crew.dismissOrphanByName(name);
        if (removed > 0) {
            sender.sendMessage(ChatColor.GREEN + "Removed " + removed
                    + " leftover body/bodies named '" + name + "' (not in crew list).");
        } else {
            // Also try full world orphan sweep (loaded chunks only)
            int swept = crew.sweepWorldOrphans();
            if (swept > 0) {
                sender.sendMessage(ChatColor.GREEN + "Swept " + swept
                        + " leftover crew body/bodies from loaded chunks.");
            } else {
                sender.sendMessage(ChatColor.RED + "No bot named '" + name
                        + "' in crew list, and no matching body in loaded chunks.");
                sender.sendMessage(ChatColor.GRAY
                        + "Stand near the leftover villager (so its chunk loads), then run /crew dismiss "
                        + name + " or /crew purge again.");
            }
        }
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
        java.util.List<String> report = crew.assign(bot, order);
        if (report != null && !report.isEmpty()) {
            // Bot already surveyed and is speaking (distance / choices)
            for (String line : report) {
                sender.sendMessage(line);
            }
            if (bot.getStatus() == BotStatus.BUSY) {
                sender.sendMessage(ChatColor.DARK_GRAY + "(working)");
            } else {
                sender.sendMessage(ChatColor.DARK_GRAY
                        + "(waiting for your choice — force / alternate resource / stop)");
            }
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "Assigned " + bot.getName() + ": " + order);
        crew.talk(bot, "Your owner assigned you this order: " + order
                + ". Acknowledge briefly and say what you'll do.", sender);
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

    private void here(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew here <name>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], true);
        crew.bringHere(bot, player.getLocation());
        NpcHandle body = crew.bodyOf(bot);
        sender.sendMessage(ChatColor.GREEN + bot.getName() + " brought to you"
                + (body != null && body.isValid() ? " (body OK)." : " (body still missing — try summon again)."));
    }

    private void find(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew find <name>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], false);
        NpcHandle body = crew.ensureBody(bot);
        Location loc = body != null && body.isValid() ? body.getLocation() : bot.getLastLocation();
        if (loc == null) {
            loc = bot.getHome();
        }
        if (loc == null) {
            sender.sendMessage(ChatColor.RED + bot.getName() + " has no known location.");
            return;
        }
        boolean valid = body != null && body.isValid();
        sender.sendMessage(ChatColor.GOLD + bot.getName() + ChatColor.WHITE
                + " @ " + loc.getWorld().getName()
                + " " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                + ChatColor.GRAY + " body=" + (valid ? "visible" : "was missing — respawned")
                + " status=" + bot.getStatus()
                + " order=" + (bot.getCurrentOrder() == null ? "-" : bot.getCurrentOrder()));
        if (sender instanceof Player player) {
            double d = player.getWorld().equals(loc.getWorld())
                    ? player.getLocation().distance(loc) : -1;
            if (d >= 0) {
                sender.sendMessage(ChatColor.GRAY + "Distance from you: ~" + Math.round(d) + " blocks."
                        + " Use /crew here " + bot.getName() + " to bring them.");
            }
        }
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

    /** Job board: /crew jobs [list|post|cancel] */
    private void jobs(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list", "ls" -> {
                var list = crew.getJobBoard().listForOwner(player.getUniqueId(), true);
                if (list.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "No active jobs. Post with /crew jobs post [title] <order…>");
                    return;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Crew jobs (" + list.size() + ") ===");
                for (var j : list) {
                    String assignee = "-";
                    if (j.assigneeBotId() != null) {
                        assignee = crew.allBots().stream()
                                .filter(b -> b.getId().equals(j.assigneeBotId()))
                                .map(CrewBot::getName)
                                .findFirst()
                                .orElse(j.assigneeBotId().toString().substring(0, 8));
                    }
                    String title = j.preferredTitle() == null ? "any" : j.preferredTitle().display();
                    sender.sendMessage(ChatColor.AQUA + " #" + j.shortId()
                            + ChatColor.GRAY + " [" + j.status() + "] "
                            + ChatColor.YELLOW + title + ChatColor.WHITE + " " + j.description()
                            + (j.assigneeBotId() != null ? ChatColor.DARK_AQUA + " @" + assignee : ""));
                }
            }
            case "post", "add", "queue" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /crew jobs post [title|any] <order…>");
                    sender.sendMessage(ChatColor.GRAY + "Example: /crew jobs post miner gather iron");
                    return;
                }
                BotTitle preferred = null;
                int descStart = 2;
                Optional<BotTitle> parsed = BotTitle.parse(args[2]);
                if (parsed.isPresent() || args[2].equalsIgnoreCase("any") || args[2].equalsIgnoreCase("all")) {
                    preferred = parsed.orElse(null);
                    descStart = 3;
                }
                if (args.length <= descStart) {
                    sender.sendMessage(ChatColor.RED + "Provide an order description.");
                    return;
                }
                String desc = String.join(" ", Arrays.copyOfRange(args, descStart, args.length));
                var job = crew.postJob(player, preferred, desc, 1);
                sender.sendMessage(ChatColor.GREEN + "Posted job #" + job.shortId()
                        + ChatColor.GRAY + " (" + (preferred == null ? "any title" : preferred.display()) + "): "
                        + ChatColor.WHITE + desc);
                sender.sendMessage(ChatColor.GRAY + "Idle matching bots will claim automatically.");
            }
            case "cancel", "rm", "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /crew jobs cancel <id>");
                    return;
                }
                boolean ok = crew.getJobBoard().cancel(args[2], "cancelled by " + player.getName());
                if (ok) {
                    // If a bot held it, stop them so they don't keep a cancelled order
                    crew.getJobBoard().find(args[2]).ifPresent(j -> {
                        if (j.assigneeBotId() != null) {
                            crew.allBots().stream()
                                    .filter(b -> b.getId().equals(j.assigneeBotId()))
                                    .findFirst()
                                    .ifPresent(b -> {
                                        // already released by cancel; clear order if still matching
                                        if (b.getCurrentOrder() != null
                                                && b.getCurrentOrder().equalsIgnoreCase(j.description())) {
                                            crew.stop(b);
                                        }
                                    });
                        }
                    });
                    sender.sendMessage(ChatColor.GREEN + "Cancelled job #" + args[2]);
                } else {
                    sender.sendMessage(ChatColor.RED + "No job matching '" + args[2] + "'.");
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Usage: /crew jobs [list|post|cancel]");
        }
    }

    /** Inter-bot messaging: /crew msg <from> <to> <message...> */
    private void interBotMsg(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew msg <from> <to> <message...>");
            sender.sendMessage(ChatColor.GRAY + "Example: /crew msg Builder Miner gather cobblestone");
            return;
        }
        CrewBot from = requireBot(sender, args[1], true);
        CrewBot to = requireBot(sender, args[2], true);
        String body = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        com.aibots.crew.BotMessage.Kind kind = com.aibots.crew.BotMessage.Kind.DELEGATE;
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.startsWith("need ") || lower.contains(" need ")) {
            kind = com.aibots.crew.BotMessage.Kind.REQUEST;
        }
        crew.getMessenger().send(from, to, kind, body);
        sender.sendMessage(ChatColor.GREEN + from.getName() + " → " + to.getName() + ": " + body);
        sender.sendMessage(ChatColor.GRAY + "If " + to.getName() + " is idle, they may auto-start the work.");
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
        sender.sendMessage(ChatColor.YELLOW + "Loot items: " + bot.getLoot().totalItems()
                + ChatColor.GRAY + " — /crew inv " + bot.getName()
                + "  or right-click villager");
        sender.sendMessage(ChatColor.GRAY + "Use /crew memory " + bot.getName() + " for details.");
    }

    /**
     * List or open a bot's personal loot bag.
     * /crew inv <name>
     * /crew inv <name> open
     */
    private void inventory(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew inv <name> [open]");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], true);
        boolean openGui = args.length >= 3 && args[2].equalsIgnoreCase("open");

        if (openGui) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can open the loot GUI.");
                return;
            }
            player.openInventory(bot.getLoot().getInventory());
            player.sendMessage(ChatColor.GRAY + "Opened " + bot.getName() + "'s loot bag ("
                    + bot.getLoot().totalItems() + " items).");
            return;
        }

        var tally = bot.getLoot().tally();
        int total = bot.getLoot().totalItems();
        int used = bot.getLoot().usedSlots();
        int size = bot.getLoot().size();
        sender.sendMessage(ChatColor.GOLD + "=== " + bot.getName() + " inventory ===");
        sender.sendMessage(ChatColor.GRAY + "Slots: " + used + "/" + size
                + " used  |  items: " + total
                + ChatColor.DARK_GRAY + "  (open GUI: /crew inv " + bot.getName() + " open)");
        if (tally.isEmpty()) {
            sender.sendMessage(ChatColor.DARK_GRAY + "  (empty)");
            return;
        }
        // Sort by count descending for readability
        tally.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    String pretty = e.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' ');
                    sender.sendMessage(ChatColor.WHITE + "  • " + ChatColor.YELLOW + e.getValue() + "× "
                            + ChatColor.WHITE + pretty);
                });
    }

    private void purge(CommandSender sender) {
        if (!sender.hasPermission("aibots.admin") && !(sender instanceof Player)) {
            // allow players who own bots to purge their mess; admins always
        }
        int n = crew.purgeAll();
        sender.sendMessage(ChatColor.GREEN + "Purged crew + world bodies (actions=" + n + ").");
        sender.sendMessage(ChatColor.GRAY + "If a ghost remains, look at it and run: /npc remove");
    }

    private void storage(CommandSender sender, String[] args) {
        var net = crew.getChestNetwork();
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";

        // /crew has oak log  OR  /crew storage has oak log
        if (args[0].equalsIgnoreCase("has") || args[0].equalsIgnoreCase("stock")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /crew has <item name>");
                return;
            }
            String itemQ = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            sendStockReport(sender, itemQ);
            return;
        }
        if (sub.equals("has") || sub.equals("count") || sub.equals("find")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /crew storage has <item name>");
                return;
            }
            String itemQ = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            sendStockReport(sender, itemQ);
            return;
        }
        if (sub.equals("fix")) {
            net.forceReconnectAll();
            sender.sendMessage(ChatColor.GREEN + "Tried to merge adjacent network chests into double chests.");
            return;
        }
        if (sub.equals("clear") || sub.equals("unregister-all")) {
            int n = net.clearRegistrations();
            sender.sendMessage(ChatColor.GREEN + "Cleared " + n + " chest registration(s). "
                    + ChatColor.GRAY + "Blocks still exist in the world — re-register with "
                    + "/crew storage register …");
            return;
        }
        if (sub.equals("pos1") || sub.equals("pos2")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Players only (or use full coords).");
                return;
            }
            // Prefer block you're looking at (like WorldEdit), else feet block
            Location loc = targetBlockCorner(player);
            String key = "aibots.storage." + sub;
            player.setMetadata(key, new org.bukkit.metadata.FixedMetadataValue(plugin, loc));
            sender.sendMessage(ChatColor.GREEN + "Storage " + sub + " = "
                    + ChatColor.YELLOW + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ()
                    + ChatColor.GRAY + "  (corner of the /fill-style box)");
            if (sub.equals("pos1")) {
                sender.sendMessage(ChatColor.GRAY + "Now look at the opposite corner "
                        + ChatColor.YELLOW + "at a different height"
                        + ChatColor.GRAY + " (ceiling / top of chest wall — not the same floor) and run "
                        + ChatColor.AQUA + "/crew storage pos2");
            }
            if (sub.equals("pos2") && player.hasMetadata("aibots.storage.pos1")) {
                Object v1 = player.getMetadata("aibots.storage.pos1").get(0).value();
                if (v1 instanceof Location a) {
                    int dx = Math.abs(a.getBlockX() - loc.getBlockX()) + 1;
                    int dy = Math.abs(a.getBlockY() - loc.getBlockY()) + 1;
                    int dz = Math.abs(a.getBlockZ() - loc.getBlockZ()) + 1;
                    sender.sendMessage(ChatColor.WHITE + "Box size: " + ChatColor.AQUA
                            + dx + "×" + dy + "×" + dz + ChatColor.GRAY
                            + " blocks — run " + ChatColor.YELLOW + "/crew storage register"
                            + ChatColor.GRAY + " to scan every chest inside.");
                    if (dy < 3) {
                        sender.sendMessage(ChatColor.YELLOW + "Height is only " + dy
                                + " — wall chests may be missed. Prefer floor corner + ceiling corner.");
                    }
                }
            }
            return;
        }
        if (sub.equals("register") || sub.equals("link") || sub.equals("fill")) {
            storageRegister(sender, args);
            return;
        }
        if (sub.equals("list") || sub.equals("ls") || args.length < 2) {
            var units = net.listUnits();
            if (units.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "No storage chests registered. "
                        + "Use /crew storage register <x1 y1 z1 x2 y2 z2> or /crew home <bot>");
                return;
            }
            sender.sendMessage(ChatColor.GOLD + "=== Storage network (" + units.size() + " chest"
                    + (units.size() == 1 ? "" : "s") + ", free slots: " + net.freeSlots() + ") ===");
            for (var u : units) {
                sender.sendMessage(ChatColor.AQUA + " #" + u.number
                        + ChatColor.WHITE + (u.doubleChest ? " double" : " single")
                        + ChatColor.GRAY + " @ " + u.location.getBlockX() + "," + u.location.getBlockY()
                        + "," + u.location.getBlockZ()
                        + ChatColor.YELLOW + " items=" + u.totalItems()
                        + ChatColor.DARK_GRAY + " free=" + u.freeSlots + "/" + u.size);
            }
            sender.sendMessage(ChatColor.GRAY + "Inspect: /crew storage <#>   Stock: /crew has <item>");
            sender.sendMessage(ChatColor.GRAY + "Register room: /crew storage register x1 y1 z1 x2 y2 z2");
            return;
        }
        // /crew storage 1
        try {
            int num = Integer.parseInt(sub);
            var unit = net.unit(num);
            if (unit.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No chest #" + num + ". Use /crew storage list");
                return;
            }
            var u = unit.get();
            sender.sendMessage(ChatColor.GOLD + "=== Chest #" + u.number
                    + (u.doubleChest ? " (double)" : " (single)") + " ===");
            sender.sendMessage(ChatColor.GRAY + "Location: " + u.location.getWorld().getName()
                    + " " + u.location.getBlockX() + ", " + u.location.getBlockY() + ", " + u.location.getBlockZ());
            sender.sendMessage(ChatColor.GRAY + "Slots: " + (u.size - u.freeSlots) + " used / "
                    + u.freeSlots + " free / " + u.size + " total");
            if (u.contents.isEmpty()) {
                sender.sendMessage(ChatColor.DARK_GRAY + "  (empty)");
            } else {
                u.contents.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .forEach(e -> sender.sendMessage(ChatColor.WHITE + "  • "
                                + e.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                                + ChatColor.YELLOW + " x" + e.getValue()));
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew storage [list|register|pos1|pos2|clear|fix|has|<#> ]");
        }
    }

    /**
     * Like {@code /fill x1 y1 z1 x2 y2 z2}: two corners define a solid box;
     * every chest / double-chest half / barrel / shulker inside is registered.
     * <pre>
     * /crew storage register
     * /crew storage register &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;
     * /crew storage register &lt;world&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;
     * /crew storage fill ...  (alias)
     * </pre>
     */
    private void storageRegister(CommandSender sender, String[] args) {
        var net = crew.getChestNetwork();
        Location a;
        Location b;

        // coords after "register"/"fill": args[2..]
        if (args.length >= 8) {
            int i = 2;
            org.bukkit.World world = null;
            if (args.length >= 9) {
                world = Bukkit.getWorld(args[2]);
                if (world != null) {
                    i = 3;
                }
            }
            if (world == null) {
                if (sender instanceof Player p) {
                    world = p.getWorld();
                } else {
                    world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                }
            }
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "No world available.");
                return;
            }
            try {
                int x1 = Integer.parseInt(args[i]);
                int y1 = Integer.parseInt(args[i + 1]);
                int z1 = Integer.parseInt(args[i + 2]);
                int x2 = Integer.parseInt(args[i + 3]);
                int y2 = Integer.parseInt(args[i + 4]);
                int z2 = Integer.parseInt(args[i + 5]);
                a = new Location(world, x1, y1, z1);
                b = new Location(world, x2, y2, z2);
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "Coords must be integers (same as /fill).");
                sender.sendMessage(ChatColor.GRAY
                        + "Usage: /crew storage register <x1> <y1> <z1> <x2> <y2> <z2>");
                return;
            }
        } else if (args.length == 2 && sender instanceof Player player) {
            if (!player.hasMetadata("aibots.storage.pos1") || !player.hasMetadata("aibots.storage.pos2")) {
                sender.sendMessage(ChatColor.GOLD + "Register a room of chests like /fill:");
                sender.sendMessage(ChatColor.YELLOW + "  1. /crew storage pos1  "
                        + ChatColor.GRAY + "look at a floor corner");
                sender.sendMessage(ChatColor.YELLOW + "  2. /crew storage pos2  "
                        + ChatColor.GRAY + "look at opposite corner HIGHER (ceiling / top of chests)");
                sender.sendMessage(ChatColor.DARK_GRAY + "     Same-floor corners = flat box → misses wall chests.");
                sender.sendMessage(ChatColor.YELLOW + "  3. /crew storage register  "
                        + ChatColor.GRAY + "scan every block in the box for chests");
                sender.sendMessage(ChatColor.GRAY + "Or paste coords: /crew storage register "
                        + "<x1> <y1> <z1> <x2> <y2> <z2>");
                return;
            }
            Object v1 = player.getMetadata("aibots.storage.pos1").get(0).value();
            Object v2 = player.getMetadata("aibots.storage.pos2").get(0).value();
            if (!(v1 instanceof Location) || !(v2 instanceof Location)) {
                sender.sendMessage(ChatColor.RED + "Invalid pos1/pos2 — set them again.");
                return;
            }
            a = (Location) v1;
            b = (Location) v2;
        } else {
            sender.sendMessage(ChatColor.GOLD + "Like /fill — two corners, scan the whole box:");
            sender.sendMessage(ChatColor.YELLOW
                    + "  /crew storage register <x1> <y1> <z1> <x2> <y2> <z2>");
            sender.sendMessage(ChatColor.YELLOW
                    + "  /crew storage pos1 → pos2 → register");
            sender.sendMessage(ChatColor.GRAY
                    + "Every chest, double-chest, barrel, and shulker inside is linked.");
            return;
        }

        sender.sendMessage(ChatColor.GRAY + "Scanning box " + ChatColor.WHITE
                + a.getBlockX() + " " + a.getBlockY() + " " + a.getBlockZ()
                + ChatColor.GRAY + " → " + ChatColor.WHITE
                + b.getBlockX() + " " + b.getBlockY() + " " + b.getBlockZ()
                + ChatColor.DARK_GRAY + " (loading chunks…)");

        var result = net.registerRegion(a, b);
        if (!result.ok()) {
            sender.sendMessage(ChatColor.RED + result.error);
            return;
        }

        sender.sendMessage(ChatColor.GREEN + "Storage box registered "
                + ChatColor.AQUA + result.sizeLabel()
                + ChatColor.GRAY + " = " + result.volume + " blocks");
        sender.sendMessage(ChatColor.DARK_GRAY + "  " + result.cornerLabel());
        if (result.yPadded) {
            sender.sendMessage(ChatColor.YELLOW + "  Height was auto-expanded (selection was too flat). "
                    + ChatColor.GRAY + "For a precise box, set corners with different Y (floor + ceiling).");
        }
        sender.sendMessage(ChatColor.WHITE + "  Found: " + ChatColor.YELLOW + result.found
                + ChatColor.GRAY + " container block(s)  "
                + ChatColor.DARK_GRAY + "(single=" + result.singles
                + " double-halves=" + result.doubleHalves
                + " ≈" + result.doubleChests + " doubles"
                + " barrel=" + result.barrels
                + " shulker=" + result.shulkers + ")");
        sender.sendMessage(ChatColor.WHITE + "  Linked: " + ChatColor.GREEN + result.registered + " new"
                + ChatColor.GRAY + ", " + result.already + " already in network");
        sender.sendMessage(ChatColor.WHITE + "  Network: " + ChatColor.AQUA + result.uniqueInventories
                + ChatColor.GRAY + " unique inventories, "
                + ChatColor.YELLOW + result.freeSlots + " free slots"
                + ChatColor.DARK_GRAY + " (block entries=" + result.networkSize + ")");
        if (result.found == 0) {
            sender.sendMessage(ChatColor.YELLOW + "No chests in that box. Tips:");
            sender.sendMessage(ChatColor.GRAY + "  • Box must fully enclose every chest (X, Y, and Z)");
            sender.sendMessage(ChatColor.GRAY + "  • Option A: F3 coords of two opposite room corners "
                    + "(one low, one high)");
            sender.sendMessage(ChatColor.GRAY + "  • Option B: look at ceiling corner for one pos, "
                    + "floor corner for the other");
        } else if (result.found < result.networkSize) {
            // shouldn't happen
        } else {
            sender.sendMessage(ChatColor.GRAY + "Next: /crew storage list  |  /crew deposit <bot>");
            if (result.found > 0 && result.uniqueInventories < result.found) {
                sender.sendMessage(ChatColor.DARK_GRAY + "  (double chests share one inventory — "
                        + "block count can be higher than inventory count)");
            }
        }
    }

    /** Block the player is looking at (range 64), else feet block — for /fill-style corners. */
    private static Location targetBlockCorner(Player player) {
        try {
            var target = player.getTargetBlockExact(64);
            if (target != null && !target.getType().isAir()) {
                return target.getLocation();
            }
        } catch (Throwable ignored) {
        }
        // Fallback: ray-trace via deprecated API for older builds
        try {
            @SuppressWarnings("deprecation")
            Block b = player.getTargetBlock(null, 64);
            if (b != null && !b.getType().isAir()) {
                return b.getLocation();
            }
        } catch (Throwable ignored) {
        }
        return player.getLocation().getBlock().getLocation();
    }

    /**
     * /crew radius
     * /crew radius 80                 — session override
     * /crew radius session 80
     * /crew radius default 80         — save to config.yml
     * /crew radius clear              — drop session, use default
     */
    private void radius(CommandSender sender, String[] args) {
        var rs = crew.getRadiusService();
        if (args.length == 1) {
            sender.sendMessage(ChatColor.GOLD + "=== Work radius ===");
            sender.sendMessage(ChatColor.WHITE + "  Effective: " + ChatColor.AQUA + rs.effective() + " blocks");
            sender.sendMessage(ChatColor.WHITE + "  Default (config): " + ChatColor.YELLOW + rs.defaultRadius());
            if (rs.hasSessionOverride()) {
                sender.sendMessage(ChatColor.WHITE + "  Session override: " + ChatColor.GREEN + rs.sessionRadius()
                        + ChatColor.GRAY + "  (until restart or /crew radius clear)");
            } else {
                sender.sendMessage(ChatColor.GRAY + "  Session override: (none)");
            }
            sender.sendMessage(ChatColor.DARK_GRAY + "  Warn if setting > " + rs.warnAbove()
                    + "  |  hard max " + rs.hardMax());
            sender.sendMessage(ChatColor.GRAY + "  /crew radius <n>  — this session only");
            sender.sendMessage(ChatColor.GRAY + "  /crew radius default <n>  — save new default");
            sender.sendMessage(ChatColor.GRAY + "  /crew radius clear  — back to default");
            return;
        }
        String a1 = args[1].toLowerCase(Locale.ROOT);
        if (a1.equals("clear") || a1.equals("reset")) {
            rs.clearSession();
            sender.sendMessage(ChatColor.GREEN + "Session radius cleared. Using default "
                    + rs.defaultRadius() + " (effective " + rs.effective() + ").");
            return;
        }
        if (a1.equals("status") || a1.equals("show") || a1.equals("get")) {
            radius(sender, new String[]{"radius"});
            return;
        }

        boolean saveDefault = a1.equals("default") || a1.equals("save") || a1.equals("persist");
        boolean sessionOnly = a1.equals("session") || a1.equals("temp") || a1.equals("now");
        int valueIdx = (saveDefault || sessionOnly) ? 2 : 1;
        if (args.length <= valueIdx) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew radius [session|default|clear] <blocks>");
            return;
        }
        int raw;
        try {
            raw = Integer.parseInt(args[valueIdx]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Blocks must be a number (e.g. 80).");
            return;
        }
        if (raw < rs.hardMin()) {
            sender.sendMessage(ChatColor.RED + "Minimum radius is " + rs.hardMin() + ".");
            return;
        }
        if (raw > rs.hardMax()) {
            sender.sendMessage(ChatColor.RED + "Hard max is " + rs.hardMax()
                    + " (config crew.radius-hard-max). Refusing " + raw + ".");
            return;
        }
        if (rs.needsWarning(raw)) {
            // Require explicit confirm for large values
            boolean confirmed = args.length > valueIdx + 1
                    && (args[valueIdx + 1].equalsIgnoreCase("confirm")
                    || args[valueIdx + 1].equalsIgnoreCase("yes")
                    || args[valueIdx + 1].equalsIgnoreCase("force"));
            if (!confirmed) {
                sender.sendMessage(ChatColor.YELLOW + "Warning: " + raw
                        + " is over " + rs.warnAbove()
                        + " — large scans can lag the server.");
                sender.sendMessage(ChatColor.GRAY + "Re-run with "
                        + ChatColor.AQUA + "confirm"
                        + ChatColor.GRAY + " at the end, e.g. "
                        + ChatColor.WHITE + "/crew radius " + (saveDefault ? "default " : "")
                        + raw + " confirm");
                return;
            }
        }

        if (saveDefault) {
            int v = rs.setDefaultAndSave(raw);
            sender.sendMessage(ChatColor.GREEN + "Default work radius saved as "
                    + ChatColor.AQUA + v
                    + ChatColor.GREEN + " (config.yml). Effective now: " + v + ".");
            if (rs.needsWarning(v)) {
                sender.sendMessage(ChatColor.YELLOW + "Large radius active — watch TPS while bots search.");
            }
        } else {
            int v = rs.setSession(raw);
            sender.sendMessage(ChatColor.GREEN + "Session work radius set to "
                    + ChatColor.AQUA + v
                    + ChatColor.GREEN + " (until restart or /crew radius clear)."
                    + ChatColor.GRAY + " Default remains " + rs.defaultRadius() + ".");
            if (rs.needsWarning(v)) {
                sender.sendMessage(ChatColor.YELLOW + "Large radius active — watch TPS while bots search.");
            }
        }
    }

    private void deposit(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew deposit <name>");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], true);
        int bagBefore = bot.getLoot().totalItems();
        if (bagBefore == 0) {
            sender.sendMessage(ChatColor.GRAY + bot.getName() + " bag is empty.");
            return;
        }
        if (crew.getChestNetwork().getChests().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No storage network. Register chests first:");
            sender.sendMessage(ChatColor.YELLOW + "  /crew storage register <x1> <y1> <z1> <x2> <y2> <z2>");
            return;
        }
        int moved = crew.forceDeposit(bot);
        int bagAfter = bot.getLoot().totalItems();
        if (moved <= 0 && bagAfter == bagBefore) {
            sender.sendMessage(ChatColor.RED + "Could not deposit — network full or no free slots? "
                    + "Free slots: " + crew.getChestNetwork().freeSlots());
            return;
        }
        sender.sendMessage(ChatColor.GREEN + bot.getName() + " deposited " + ChatColor.YELLOW + moved
                + ChatColor.GREEN + " item(s) into the storage network. "
                + ChatColor.GRAY + "Bag left: " + bagAfter
                + "  |  /crew storage list");
    }

    /** /crew give <name> <material> [amount] — put items in loot bag (test / restock). */
    private void giveLoot(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /crew give <name> <material> [amount]");
            return;
        }
        CrewBot bot = requireBot(sender, args[1], true);
        String matName = args[2].toUpperCase(Locale.ROOT).replace(' ', '_');
        org.bukkit.Material mat;
        try {
            mat = org.bukkit.Material.valueOf(matName);
        } catch (IllegalArgumentException e) {
            var matches = com.aibots.storage.ChestNetwork.matchMaterials(args[2]);
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("Unknown material: " + args[2]);
            }
            mat = matches.get(0);
        }
        int amount = 64;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(99 * 54, Integer.parseInt(args[3])));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Amount must be a number.");
            }
        }
        int given = 0;
        while (given < amount) {
            int stack = Math.min(mat.getMaxStackSize() > 0 ? Math.min(mat.getMaxStackSize(), 99) : 64,
                    amount - given);
            org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(mat, stack);
            var left = bot.getLoot().add(item);
            if (left != null && left.getAmount() >= stack) {
                break; // bag full
            }
            int accepted = stack - (left == null ? 0 : left.getAmount());
            given += accepted;
            if (accepted <= 0) {
                break;
            }
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + bot.getName() + " "
                + ChatColor.YELLOW + given + "× " + mat.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + ChatColor.GRAY + " (bag total: " + bot.getLoot().totalItems() + ")");
    }

    private void sendStockReport(CommandSender sender, String itemQuery) {
        var net = crew.getChestNetwork();
        var tally = net.tallyMatching(itemQuery);
        int total = tally.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            sender.sendMessage(ChatColor.GOLD + "Storage: " + ChatColor.WHITE + "No \""
                    + itemQuery + "\" in the chest network.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Storage has " + ChatColor.YELLOW + total
                + ChatColor.GOLD + " of \"" + itemQuery + "\":");
        tally.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> sender.sendMessage(ChatColor.WHITE + "  • "
                        + e.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                        + ChatColor.YELLOW + " x" + e.getValue()));
        // Per-chest breakdown
        for (var u : net.listUnits()) {
            int inChest = 0;
            StringBuilder bits = new StringBuilder();
            for (var e : u.contents.entrySet()) {
                String n = e.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' ');
                String q = itemQuery.toLowerCase(Locale.ROOT);
                if (n.contains(q) || e.getKey().name().toLowerCase(Locale.ROOT)
                        .contains(q.replace(' ', '_'))) {
                    inChest += e.getValue();
                    if (bits.length() > 0) {
                        bits.append(", ");
                    }
                    bits.append(n).append(" x").append(e.getValue());
                }
            }
            if (inChest > 0) {
                sender.sendMessage(ChatColor.AQUA + "  chest #" + u.number + ChatColor.GRAY
                        + ": " + bits);
            }
        }
    }

    private void healTrees(CommandSender sender, String[] args) {
        if (!(plugin instanceof AIBotsPlugin aibots)) {
            sender.sendMessage(ChatColor.RED + "Plugin not ready.");
            return;
        }
        var healer = aibots.getTreeHealer();
        if (healer == null) {
            sender.sendMessage(ChatColor.RED + "Tree healer not loaded.");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            healer.cancel();
            sender.sendMessage(ChatColor.YELLOW + "Tree heal cancelled.");
            return;
        }
        if (healer.isRunning()) {
            sender.sendMessage(ChatColor.RED + "Already healing. /crew healtrees cancel");
            return;
        }
        org.bukkit.World world;
        java.util.List<Long> chunks;
        boolean wholeWorld = args.length < 2
                || args[1].equalsIgnoreCase("world")
                || args[1].equalsIgnoreCase("all");
        if (!wholeWorld) {
            int radius = Integer.parseInt(args[1]);
            radius = Math.max(16, Math.min(radius, 1024));
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Radius needs a player. Use /crew healtrees world");
                return;
            }
            world = player.getWorld();
            int cr = (radius + 15) / 16;
            chunks = com.aibots.world.TreeHealer.chunksInRadius(
                    player.getLocation().getChunk().getX(),
                    player.getLocation().getChunk().getZ(),
                    cr);
            sender.sendMessage(ChatColor.GRAY + "Scanning ~" + radius + " blocks around you.");
        } else {
            if (sender instanceof Player player) {
                world = player.getWorld();
            } else {
                world = plugin.getServer().getWorlds().isEmpty()
                        ? null
                        : plugin.getServer().getWorlds().get(0);
            }
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "No world.");
                return;
            }
            chunks = com.aibots.world.TreeHealer.generatedOverworldChunks(world);
            if (chunks.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No region files found; using loaded chunks.");
                for (org.bukkit.Chunk c : world.getLoadedChunks()) {
                    chunks.add((((c.getX() & 0xffffffffL) << 32) | (c.getZ() & 0xffffffffL)));
                }
            }
        }
        int n = healer.start(world, chunks, sender);
        if (n == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Nothing to scan.");
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("aibots.admin")) {
            sender.sendMessage(ChatColor.RED + "Admin only.");
            return;
        }
        plugin.reloadConfig();
        crew.getRadiusService().reloadFromConfig();
        if (plugin instanceof AIBotsPlugin aibots) {
            aibots.reloadLlm();
        }
        sender.sendMessage(ChatColor.GREEN + "config.yml reloaded (including LLM). bots.yml not wiped.");
        sender.sendMessage(ChatColor.GRAY + crew.getRadiusService().statusLine());
        llmStatus(sender);
    }

    private void llmStatus(CommandSender sender) {
        LLMProvider provider = crew.getLlm();
        if (provider instanceof LLMRouter router) {
            LLMProvider primary = router.primary();
            sender.sendMessage(ChatColor.GOLD + "=== LLM ===");
            sender.sendMessage(ChatColor.YELLOW + "primary: " + ChatColor.WHITE + router.primaryId()
                    + ChatColor.GRAY + " (" + primary.displayName() + ")");
            sender.sendMessage(ChatColor.YELLOW + "model: " + ChatColor.WHITE
                    + (primary.getModel() == null || primary.getModel().isBlank()
                    ? "(auto)" : primary.getModel()));
            sender.sendMessage(ChatColor.YELLOW + "url: " + ChatColor.WHITE + primary.getBaseUrl());
            String fb = router.fallbackToId();
            sender.sendMessage(ChatColor.YELLOW + "fallback: " + ChatColor.WHITE
                    + (fb == null || fb.isBlank() ? "(none)" : fb));
            sender.sendMessage(ChatColor.GRAY + "providers: " + router.providers().keySet());
            sender.sendMessage(ChatColor.DARK_GRAY + "Switch: set llm.primary to ollama or lm-studio, then /crew reload");
        } else if (provider != null) {
            sender.sendMessage(ChatColor.YELLOW + "LLM: " + ChatColor.WHITE + provider.displayName()
                    + ChatColor.GRAY + " model=" + provider.getModel());
        } else {
            sender.sendMessage(ChatColor.RED + "No LLM provider loaded.");
        }
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
                    "assign", "stop", "home", "here", "find", "say", "broadcast", "teach", "memory",
                    "share", "msg", "ask", "jobs", "info", "inv", "inventory", "bag", "loot",
                    "deposit", "dump", "give", "put", "radius", "purge", "storage", "has", "stock", "reload", "llm",
                    "healtrees"
            ));
        }
        if (args.length == 2 && List.of("healtrees", "healtree", "fixtrees").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(args[1], List.of("world", "128", "256", "512", "cancel"));
        }
        if (args.length == 2 && List.of("radius", "range", "workradius").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(args[1], List.of("48", "80", "120", "200", "session", "default", "clear", "status"));
        }
        if (args.length == 3 && List.of("radius", "range", "workradius").contains(args[0].toLowerCase(Locale.ROOT))) {
            String m = args[1].toLowerCase(Locale.ROOT);
            if (m.equals("session") || m.equals("default") || m.equals("save") || m.equals("temp")) {
                return filter(args[2], List.of("48", "80", "120", "200", "250", "confirm"));
            }
            return filter(args[2], List.of("confirm", "yes", "force"));
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("dismiss", "title", "skin", "assign", "stop", "home", "here", "find", "say", "info",
                    "inv", "inventory", "bag", "loot", "deposit", "dump", "give", "put",
                    "teach", "memory", "share", "msg", "message", "tell", "ask").contains(sub)) {
                return filter(args[1], crew.allBots().stream().map(CrewBot::getName).collect(Collectors.toList()));
            }
            if (sub.equals("summon")) {
                return List.of("<name>");
            }
            if (sub.equals("jobs") || sub.equals("job") || sub.equals("board")) {
                return filter(args[1], List.of("list", "post", "cancel"));
            }
            if (sub.equals("storage") || sub.equals("chests")) {
                return filter(args[1], List.of("list", "has", "fix", "register", "pos1", "pos2",
                        "clear", "1", "2", "3"));
            }
            if (sub.equals("has") || sub.equals("stock")) {
                return filter(args[1], List.of("oak_log", "cobblestone", "iron", "coal", "sand"));
            }
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("summon") || sub.equals("title") || sub.equals("role")) {
                return filter(args[2], List.of(
                        "scavenger", "miner", "woodsman", "hunter", "farmer",
                        "warrior", "protector", "builder"));
            }
            if (sub.equals("skin")) {
                return filter(args[2], List.of("Steve", "Alex"));
            }
            if (sub.equals("teach")) {
                return filter(args[2], List.of("share"));
            }
            if (sub.equals("share") || sub.equals("msg") || sub.equals("message")
                    || sub.equals("tell") || sub.equals("ask")) {
                return filter(args[2], crew.allBots().stream().map(CrewBot::getName).collect(Collectors.toList()));
            }
            if (sub.equals("inv") || sub.equals("inventory") || sub.equals("bag") || sub.equals("loot")) {
                return filter(args[2], List.of("open"));
            }
            if (sub.equals("jobs") || sub.equals("job") || sub.equals("board")) {
                if (args[1].equalsIgnoreCase("post")) {
                    return filter(args[2], List.of("any", "scavenger", "miner", "woodsman", "hunter",
                            "farmer", "warrior", "protector", "builder"));
                }
                if (args[1].equalsIgnoreCase("cancel")) {
                    return filter(args[2], crew.getJobBoard().listForOwner(
                                    sender instanceof Player p ? p.getUniqueId() : null, true).stream()
                            .map(j -> j.shortId())
                            .collect(Collectors.toList()));
                }
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
