package com.botinterop;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BotTradeCommand implements CommandExecutor {

    private final BotFriendService friends;
    private final BotTradeService trades;

    public BotTradeCommand(BotFriendService friends, BotTradeService trades) {
        this.friends = friends;
        this.trades = trades;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean isOwnerPlayer = sender instanceof Player p && friends.isOwner(p.getName());
        if (!isOwnerPlayer && !sender.isOp()) {
            sender.sendMessage("§cOnly the bot's owner can approve/deny trades.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            sender.sendMessage("§7" + trades.listPending());
            return true;
        }
        if (args.length != 2 || !(args[0].equalsIgnoreCase("approve") || args[0].equalsIgnoreCase("deny"))) {
            sender.sendMessage("§cUsage: /bottrade <approve|deny> <name>  or  /bottrade list");
            return true;
        }

        String name = args[1];
        String result = args[0].equalsIgnoreCase("approve") ? trades.approve(name) : trades.deny(name);
        sender.sendMessage(result == null ? "§7No pending trade from " + name + "." : "§a" + result);
        return true;
    }
}
