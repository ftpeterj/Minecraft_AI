package com.aibots;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AIBotsPlugin extends JavaPlugin implements Listener {

    private Map<String, AIBot> bots = new HashMap<>();
    private LMStudioClient client;
    private Map<String, ArmorStand> activeNPCs = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        String baseUrl = getConfig().getString("lm-studio.base-url", "http://dadsbox.local:1234/v1");
        String model = getConfig().getString("lm-studio.model");

        client = new LMStudioClient(baseUrl, model);

        List<String> names = getConfig().getStringList("bots.names");
        List<String> personalities = getConfig().getStringList("bots.personalities");

        for (int i = 0; i < Math.min(names.size(), personalities.size()); i++) {
            spawnBot(names.get(i), personalities.get(i));
        }

        getLogger().info("§a[AIBots] Loaded!");
    }

    private void spawnBot(String name, String personality) {
        AIBot bot = new AIBot(name, personality, client, this);
        bots.put(name.toLowerCase(), bot);
    }

    public void summonNPC(String botName, Player player) {
        String key = botName.toLowerCase();
        if (activeNPCs.containsKey(key)) {
            activeNPCs.get(key).remove();
        }

        // Spawn higher
        Location loc = player.getLocation().add(player.getLocation().getDirection().multiply(4)).add(0, 2.5, 0);

        ArmorStand npc = loc.getWorld().spawn(loc, ArmorStand.class);
        npc.setCustomName("§b" + botName + " §7[Builder] §c❤❤❤❤❤");
        npc.setCustomNameVisible(true);
        npc.setGravity(true);
        npc.setVisible(true);
        npc.setInvulnerable(false);
        npc.setArms(true);
        npc.setBasePlate(false);

        npc.getEquipment().setHelmet(new ItemStack(Material.PLAYER_HEAD));
        npc.getEquipment().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        npc.getEquipment().setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        npc.getEquipment().setBoots(new ItemStack(Material.IRON_BOOTS));
        npc.getEquipment().setItemInMainHand(new ItemStack(Material.WOODEN_AXE));

        activeNPCs.put(key, npc);
        player.sendMessage("§a✅ Summoned " + botName);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Clean old NPCs on login
        activeNPCs.values().removeIf(npc -> {
            if (npc == null || !npc.isValid()) return true;
            return false;
        });
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage().toLowerCase();

        for (String botKey : bots.keySet()) {
            if (msg.contains(botKey)) {
                AIBot bot = bots.get(botKey);
                if (bot != null) {
                    event.setCancelled(true);
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> bot.chat(event.getMessage(), player));
                    return;
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("aibot")) return false;

        if (args.length == 0) {
            sender.sendMessage("§e/aibot summon <name> | /aibot clear");
            return true;
        }

        if (args[0].equalsIgnoreCase("summon")) {
            if (args.length < 2 || !(sender instanceof Player p)) return true;
            summonNPC(args[1], p);
            return true;
        }

        if (args[0].equalsIgnoreCase("clear")) {
            if (sender instanceof Player p) clearBotBuilds(p);
            return true;
        }

        return true;
    }

    public void clearBotBuilds(Player player) {
        Location center = player.getLocation();
        int radius = 25;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -8; y <= 12; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block b = center.clone().add(x, y, z).getBlock();
                    if (b.getType() != Material.AIR && b.getType() != Material.BEDROCK) {
                        if (b.getType().name().contains("OAK") || b.getType() == Material.GLASS || b.getType() == Material.GLASS_PANE) {
                            b.setType(Material.AIR);
                        }
                    }
                }
            }
        }
        player.sendMessage("§aCleared nearby bot builds.");
    }

    @Override
    public void onDisable() {
        activeNPCs.values().forEach(ArmorStand::remove);
        activeNPCs.clear();
    }
}