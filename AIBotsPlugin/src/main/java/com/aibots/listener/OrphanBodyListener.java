package com.aibots.listener;

import com.aibots.crew.CrewManager;
import com.aibots.npc.EntityCleanup;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Remove leftover crew ghosts when chunks load — never kill live tracked bots.
 */
public class OrphanBodyListener implements Listener {

    private final JavaPlugin plugin;
    private final CrewManager crew;

    public OrphanBodyListener(JavaPlugin plugin, CrewManager crew) {
        this.plugin = plugin;
        this.crew = crew;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        Entity[] entities = chunk.getEntities();
        if (entities.length == 0) {
            return;
        }
        int removed = 0;
        for (Entity entity : entities) {
            if (entity instanceof Player || !(entity instanceof LivingEntity)) {
                continue;
            }
            // Protect active crew bodies
            if (crew.getNpcService().isTrackedEntity(entity)) {
                continue;
            }
            boolean bodyType = entity instanceof org.bukkit.entity.Villager
                    || entity instanceof org.bukkit.entity.ArmorStand;
            if (!bodyType && !entity.getScoreboardTags().contains(EntityCleanup.TAG)) {
                continue;
            }
            String cn = EntityCleanup.resolveName(entity);
            boolean tagged = entity.getScoreboardTags().contains(EntityCleanup.TAG);
            boolean crewish = tagged || EntityCleanup.looksLikeCrewName(cn);
            if (!crewish) {
                continue;
            }
            String bare = EntityCleanup.bareName(cn);
            // Keep if a live bot has this name (body may re-link on ensureBody)
            if (bare != null && !bare.isBlank() && crew.findByName(bare).isPresent()) {
                continue;
            }
            entity.remove();
            removed++;
        }
        if (removed > 0) {
            plugin.getLogger().info("Chunk-load orphan sweep removed " + removed
                    + " ghost(s) in " + chunk.getWorld().getName()
                    + " [" + chunk.getX() + "," + chunk.getZ() + "]");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            int n = crew.sweepWorldOrphans();
            if (n > 0) {
                plugin.getLogger().info("Join orphan sweep removed " + n + " ghost(s).");
            }
            // Re-materialize any crew with missing bodies near their owner
            for (var bot : crew.botsOwnedBy(event.getPlayer().getUniqueId())) {
                crew.ensureBody(bot);
            }
        }, 40L);
    }
}
