package com.botinterop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Pending trade proposals from non-friends, keyed by requester name, awaiting owner approve/deny. */
public class BotTradeService {

    public static class Proposal {
        final Player target;
        final Player requester;
        final List<ItemStack> offer;
        final Set<String> wanted;

        Proposal(Player target, Player requester, List<ItemStack> offer, Set<String> wanted) {
            this.target = target;
            this.requester = requester;
            this.offer = offer;
            this.wanted = wanted;
        }
    }

    private final Map<String, Proposal> pending = new HashMap<>();

    public void submit(Player target, Player requester, List<ItemStack> offer, Set<String> wanted) {
        pending.put(requester.getName().toLowerCase(), new Proposal(target, requester, offer, wanted));
    }

    public String listPending() {
        if (pending.isEmpty()) {
            return "No pending trades.";
        }
        StringBuilder sb = new StringBuilder();
        for (Proposal p : pending.values()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(describe(p));
        }
        return sb.toString();
    }

    public String describe(Proposal p) {
        String offerStr = p.offer.isEmpty() ? "nothing"
                : p.offer.stream().map(i -> i.getAmount() + " " + i.getType().name().toLowerCase())
                        .reduce((a, b) -> a + ", " + b).orElse("nothing");
        String wantStr = p.wanted.isEmpty() ? "nothing" : String.join(", ", p.wanted).toLowerCase();
        return p.requester.getName() + " offers [" + offerStr + "] and wants [" + wantStr + "] from " + p.target.getName();
    }

    /** @return a human-readable result message, or null if there was no pending proposal for that name. */
    public String approve(String requesterName) {
        Proposal p = pending.remove(requesterName.toLowerCase());
        if (p == null) {
            return null;
        }
        if (!p.target.isOnline()) {
            returnOffer(p);
            return p.target.getName() + " is no longer online — offer returned to " + p.requester.getName() + ".";
        }
        for (ItemStack item : p.offer) {
            p.target.getInventory().addItem(item);
        }
        StringBuilder given = new StringBuilder();
        if (p.requester.isOnline()) {
            for (String materialName : p.wanted) {
                ItemStack match = findByMaterialName(p.target, materialName);
                if (match != null) {
                    p.target.getInventory().removeItem(match);
                    p.requester.getInventory().addItem(match);
                    if (given.length() > 0) given.append(", ");
                    given.append(match.getAmount()).append(' ').append(materialName.toLowerCase());
                }
            }
            p.requester.sendMessage("§a[BotInterop] Trade approved! You received: " + (given.length() > 0 ? given : "nothing (none of the requested items were found)"));
        } else {
            given.append("(requester offline, could not deliver requested items)");
        }
        p.target.updateInventory();
        return "Trade approved: " + describe(p) + " — gave: " + given;
    }

    public String deny(String requesterName) {
        Proposal p = pending.remove(requesterName.toLowerCase());
        if (p == null) {
            return null;
        }
        returnOffer(p);
        return "Trade denied for " + p.requester.getName() + ", offer returned.";
    }

    private void returnOffer(Proposal p) {
        if (p.requester.isOnline()) {
            for (ItemStack item : p.offer) {
                p.requester.getInventory().addItem(item);
            }
            p.requester.sendMessage("§9[BotInterop] Your trade offer was declined; items returned.");
        } else {
            Bukkit.getLogger().warning("[BotInterop] " + p.requester.getName() + " went offline before their trade offer could be returned — items lost.");
        }
    }

    private ItemStack findByMaterialName(Player target, String materialName) {
        for (ItemStack item : target.getInventory().getContents()) {
            if (item != null && item.getType().name().equals(materialName)) {
                return item;
            }
        }
        return null;
    }
}
