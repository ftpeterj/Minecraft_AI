package com.bags;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class BagCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly a player can use this.");
            return true;
        }

        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (BagItem.isBag(item)) {
                BagGui.open(player, inv, BagItem.idOf(item));
                return true;
            }
        }
        if (BagItem.isBag(inv.getItemInOffHand())) {
            BagGui.open(player, inv, BagItem.idOf(inv.getItemInOffHand()));
            return true;
        }

        player.sendMessage("§7You aren't carrying a bag.");
        return true;
    }
}
