package com.aibots;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class AIBot {
    private final String name;
    private final String personality;
    private final LMStudioClient client;
    private final JavaPlugin plugin;

    public AIBot(String name, String personality, LMStudioClient client, JavaPlugin plugin) {
        this.name = name;
        this.personality = personality;
        this.client = client;
        this.plugin = plugin;
    }

    public void chat(String message, CommandSender sender) {
        Player player = (sender instanceof Player) ? (Player) sender : null;

        // Much stronger prompt to force building instead of endless questions
        String systemPrompt = "You are " + name + ", a practical Minecraft builder bot. " +
                personality + " " +
                "When the player asks you to build something, you MUST start building immediately. " +
                "Do NOT ask too many questions. Build a decent structure that matches their request as closely as possible. " +
                "Reply with a short confirmation like 'Building now!' and then let the code handle the construction.";

        String fullPrompt = systemPrompt + "\nPlayer said: " + message;

        plugin.getLogger().info("[AIBot-" + name + "] Sending to LM Studio...");

        String reply = client.sendMessage(fullPrompt);

        Bukkit.broadcastMessage("§b[" + name + "] §f" + reply);

        String lower = message.toLowerCase();

        // Expanded trigger - catches most build requests
        if (player != null && 
            (lower.contains("build") || lower.contains("house") || lower.contains("pier") || 
             lower.contains("bedroom") || lower.contains("fireplace") || lower.contains("structure"))) {
            
            new BukkitRunnable() {
                @Override
                public void run() {
                    buildBetterHouse(player, message);
                }
            }.runTask(plugin);
        }
    }

    private void buildBetterHouse(Player player, String request) {
        Location base = player.getLocation().clone().add(8, 0, 0);
        int groundY = player.getWorld().getHighestBlockYAt(base.getBlockX(), base.getBlockZ());
        base.setY(groundY);

        player.sendMessage("§b[" + name + "] §eStarting construction...");

        String lowerRequest = request.toLowerCase();
        boolean wantsPier = lowerRequest.contains("pier") || lowerRequest.contains("fishing");
        boolean wantsFireplace = lowerRequest.contains("fireplace");
        boolean wantsLighting = lowerRequest.contains("lighting") || lowerRequest.contains("light");

        int size = 9; // Bigger house

        // === FLOOR ===
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                base.clone().add(x, 0, z).getBlock().setType(Material.OAK_PLANKS);
            }
        }

        // === WALLS ===
        for (int y = 1; y <= 5; y++) {
            for (int x = 0; x < size; x++) {
                base.clone().add(x, y, 0).getBlock().setType(Material.OAK_LOG);
                base.clone().add(x, y, size - 1).getBlock().setType(Material.OAK_LOG);
            }
            for (int z = 1; z < size - 1; z++) {
                base.clone().add(0, y, z).getBlock().setType(Material.OAK_LOG);
                base.clone().add(size - 1, y, z).getBlock().setType(Material.OAK_LOG);
            }
        }

        // === ROOF (simple sloped) ===
        for (int y = 6; y <= 8; y++) {
            int inset = y - 6;
            for (int x = inset; x < size - inset; x++) {
                for (int z = inset; z < size - inset; z++) {
                    base.clone().add(x, y, z).getBlock().setType(Material.OAK_PLANKS);
                }
            }
        }

        // === DOOR ===
        base.clone().add(4, 1, 0).getBlock().setType(Material.OAK_DOOR);
        base.clone().add(4, 2, 0).getBlock().setType(Material.OAK_DOOR);

        // === WINDOWS ===
        for (int x = 2; x < size - 2; x += 3) {
            for (int y = 2; y <= 4; y++) {
                base.clone().add(x, y, 0).getBlock().setType(Material.GLASS_PANE);
                base.clone().add(x, y, size - 1).getBlock().setType(Material.GLASS_PANE);
            }
        }

        // === FIREPLACE (if requested) ===
        if (wantsFireplace) {
            Location fp = base.clone().add(2, 1, size - 2);
            fp.getBlock().setType(Material.NETHERRACK);
            fp.clone().add(0, 1, 0).getBlock().setType(Material.COBBLESTONE_WALL);
            fp.clone().add(0, 2, 0).getBlock().setType(Material.COBBLESTONE_WALL);
            // Simple fire effect
            fp.clone().add(0, 1, 0).getBlock().setType(Material.FIRE);
        }

        // === BEDS for bedrooms (simple 2x2 area) ===
        for (int i = 0; i < 4; i++) { // 4 bedrooms idea
            int bx = 1 + (i % 2) * 3;
            int bz = 2 + (i / 2) * 3;
            Location bedLoc = base.clone().add(bx, 1, bz);
            bedLoc.getBlock().setType(Material.RED_BED);
            bedLoc.clone().add(1, 0, 0).getBlock().setType(Material.RED_BED);
        }

        // === LIGHTING ===
        if (wantsLighting) {
            for (int x = 2; x < size - 2; x += 3) {
                for (int z = 2; z < size - 2; z += 3) {
                    base.clone().add(x, 5, z).getBlock().setType(Material.TORCH);
                }
            }
        }

        // === PIER / DOCK (if requested) ===
        if (wantsPier) {
            Location pierStart = base.clone().add(size, 0, 3);
            for (int i = 0; i < 8; i++) {
                pierStart.clone().add(i, 0, 0).getBlock().setType(Material.OAK_PLANKS);
                pierStart.clone().add(i, 0, 1).getBlock().setType(Material.OAK_PLANKS);
            }
            // Add some fence posts
            for (int i = 0; i < 8; i += 2) {
                pierStart.clone().add(i, 1, 0).getBlock().setType(Material.OAK_FENCE);
                pierStart.clone().add(i, 1, 1).getBlock().setType(Material.OAK_FENCE);
            }
        }

        player.sendMessage("§b[" + name + "] §aDone! Built a house" + 
            (wantsPier ? " with fishing pier" : "") + 
            (wantsFireplace ? " + fireplace" : "") + 
            (wantsLighting ? " + lighting" : "") + 
            ". Let me know if you want changes!");
        
        plugin.getLogger().info("[AIBot-" + name + "] Finished building for " + player.getName());
    }
}