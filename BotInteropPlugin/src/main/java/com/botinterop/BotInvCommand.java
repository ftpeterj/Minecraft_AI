package com.botinterop;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.util.Base64;

public class BotInvCommand implements CommandExecutor {

    // Mirrors BagsPlugin's BagItem key scheme (namespace "bags") so a bag's
    // contents can be decoded here without a hard dependency between the two
    // plugins — read-only, just enough to describe what's inside one.
    private static final NamespacedKey BAG_ID_KEY = new NamespacedKey("bags", "id");
    private static final NamespacedKey BAG_CONTENTS_KEY = new NamespacedKey("bags", "contents");

    private final BotInteropPlugin plugin;

    public BotInvCommand(BotInteropPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage("§cOnly a player can use this.");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage("§cUsage: /botinv <name> [list]");
            return true;
        }

        String name = args[0];
        if (!plugin.isBotAccount(name)) {
            sender.sendMessage("§c" + name + " is not a configured bot account.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(name);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("§7" + name + " is not currently online.");
            return true;
        }
        // A bot account is always allowed to inspect its own inventory (e.g.
        // self-querying via /botinv <self> list to get ground truth from the
        // server, sidestepping its own client-side item-data quirks) — the
        // trust list only gates OTHER players' access to someone else's items.
        boolean selfQuery = viewer.getUniqueId().equals(target.getUniqueId());
        if (!selfQuery && !plugin.getFriends().isTrusted(viewer.getName())) {
            sender.sendMessage("§c" + name + " doesn't know you well enough to show you their inventory.");
            return true;
        }

        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            sendInventoryListing(viewer, target);
        } else {
            BotGui.openInventory(viewer, target);
        }
        return true;
    }

    /**
     * Plain-text listing (slot -> item, including bag contents) instead of
     * the GUI — lets the bot's own chat-based decision-making read its
     * inventory reliably by issuing this command itself and parsing the
     * reply, rather than trusting a client-side inventory model that can
     * desync from the server's real state.
     */
    private void sendInventoryListing (Player viewer, Player target) {
        PlayerInventory inv = target.getInventory();
        viewer.sendMessage("§9" + target.getName() + "'s inventory:");

        boolean any = false;
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            any = true;
            sendItemLine(viewer, "slot " + i, item);
        }
        if (!any) viewer.sendMessage("§7  (nothing in main inventory)");

        sendArmorLine(viewer, "helmet", inv.getHelmet());
        sendArmorLine(viewer, "chestplate", inv.getChestplate());
        sendArmorLine(viewer, "leggings", inv.getLeggings());
        sendArmorLine(viewer, "boots", inv.getBoots());
        sendArmorLine(viewer, "offhand", inv.getItemInOffHand());
    }

    private void sendArmorLine (Player viewer, String label, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        sendItemLine(viewer, label, item);
    }

    private void sendItemLine (Player viewer, String label, ItemStack item) {
        viewer.sendMessage("§9  " + label + ": §f" + item.getAmount() + "x " + displayName(item));
        if (!isBag(item)) return;

        ItemStack[] contents = loadBagContents(item);
        boolean anyInBag = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack bagItem = contents[i];
            if (bagItem == null || bagItem.getType().isAir()) continue;
            anyInBag = true;
            viewer.sendMessage("§9    bag slot " + i + ": §f" + bagItem.getAmount() + "x " + displayName(bagItem));
        }
        if (!anyInBag) viewer.sendMessage("§7    (empty bag)");
    }

    private String displayName (ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return item.getType().name();
    }

    private boolean isBag (ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(BAG_ID_KEY, PersistentDataType.STRING);
    }

    private ItemStack[] loadBagContents (ItemStack item) {
        String data = item.getItemMeta().getPersistentDataContainer().get(BAG_CONTENTS_KEY, PersistentDataType.STRING);
        if (data == null) return new ItemStack[0];
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataIn = new BukkitObjectInputStream(byteIn)) {
            int length = dataIn.readInt();
            ItemStack[] contents = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                contents[i] = (ItemStack) dataIn.readObject();
            }
            return contents;
        } catch (Exception e) {
            return new ItemStack[0];
        }
    }
}
