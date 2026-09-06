package com.botinterop;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BotFriendCommand implements CommandExecutor {

    private final BotFriendService friends;

    public BotFriendCommand(BotFriendService friends) {
        this.friends = friends;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean isOwnerPlayer = sender instanceof Player p && friends.isOwner(p.getName());
        if (!isOwnerPlayer && !sender.isOp()) {
            sender.sendMessage("§cOnly the bot's owner can manage its friends list.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(friends.list().isEmpty() ? "§7No friends yet." : "§aFriends: " + String.join(", ", friends.list()));
            return true;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            String name = args[1];
            if (args[0].equalsIgnoreCase("add")) {
                friends.add(name);
                sender.sendMessage("§a" + name + " can now see/open the bot's inventory.");
            } else {
                friends.remove(name);
                sender.sendMessage("§7" + name + " removed from the bot's friends list.");
            }
            return true;
        }

        sender.sendMessage("§cUsage: /botfriend <add|remove> <name>  or  /botfriend list");
        return true;
    }
}
