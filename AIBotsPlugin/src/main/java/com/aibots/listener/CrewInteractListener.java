package com.aibots.listener;

import com.aibots.crew.CrewBot;
import com.aibots.crew.CrewLootHolder;
import com.aibots.crew.CrewManager;
import com.aibots.npc.EntityCleanup;
import com.aibots.npc.NpcService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.MerchantInventory;

import java.util.Optional;
import java.util.UUID;

/**
 * Block villager trade menus on crew bots; open loot inventory instead.
 */
public class CrewInteractListener implements Listener {

    private final CrewManager crew;
    private final NpcService npcService;

    public CrewInteractListener(CrewManager crew, NpcService npcService) {
        this.crew = crew;
        this.npcService = npcService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        Optional<CrewBot> bot = resolveCrewBot(target);
        if (bot.isEmpty()) {
            return;
        }

        // Never open vanilla villager trades
        event.setCancelled(true);

        Player player = event.getPlayer();
        CrewBot b = bot.get();
        // Owner or ops can open loot; others get a short message
        if (!player.getUniqueId().equals(b.getOwnerId()) && !player.hasPermission("aibots.admin")) {
            player.sendMessage("§cOnly " + b.getName() + "'s owner can inspect their loot.");
            return;
        }

        player.openInventory(b.getLoot().getInventory());
        int n = b.getLoot().totalItems();
        player.sendMessage("§e" + b.getName() + " §7is carrying §f" + n + " §7item(s). "
                + (n == 0 ? "§8(empty — assign gather work)" : "§8(take items or wait for deposit to chests)"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        // Extra guard if something still tries to open a merchant UI on our villagers
        if (!(event.getInventory() instanceof MerchantInventory)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Villager villager) {
            if (resolveCrewBot(villager).isPresent()
                    || villager.getScoreboardTags().contains(EntityCleanup.TAG)) {
                event.setCancelled(true);
            }
        }
    }

    private Optional<CrewBot> resolveCrewBot(Entity entity) {
        Optional<UUID> id = npcService.botIdForEntity(entity);
        if (id.isPresent()) {
            for (CrewBot b : crew.allBots()) {
                if (b.getId().equals(id.get())) {
                    return Optional.of(b);
                }
            }
        }
        // Fallback: tagged entity with crew-style name
        if (entity.getScoreboardTags().contains(EntityCleanup.TAG)
                || EntityCleanup.looksLikeCrewName(entity.getCustomName())) {
            String bare = EntityCleanup.bareName(entity.getCustomName());
            return crew.findByName(bare);
        }
        return Optional.empty();
    }
}
