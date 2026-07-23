package com.aibots.skill;

import com.aibots.crew.BotStatus;
import com.aibots.crew.BotTitle;
import com.aibots.crew.CrewBot;
import com.aibots.crew.CrewMessenger;
import com.aibots.learn.LearningService;
import com.aibots.npc.NpcHandle;
import com.aibots.npc.NpcService;
import com.aibots.npc.VillagerHandle;
import com.aibots.storage.ChestNetwork;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builder: place simple structures from loot/storage (wall, platform, pillar, line).
 * Materials pulled from personal loot then chest network; can request gatherers via messenger.
 */
public final class BuilderSkill {

    private static final Pattern WALL = Pattern.compile(
            "(?:build\\s+)?wall(?:\\s+of)?\\s+(\\d+)(?:\\s*x\\s*(\\d+))?(?:\\s+(?:of\\s+)?([a-z_ ]+))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLATFORM = Pattern.compile(
            "(?:build\\s+)?(?:platform|floor|pad)(?:\\s+)?(\\d+)\\s*[x×]\\s*(\\d+)(?:\\s+(?:of\\s+)?([a-z_ ]+))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PILLAR = Pattern.compile(
            "(?:build\\s+)?pillar(?:\\s+)?(\\d+)(?:\\s+(?:of\\s+)?([a-z_ ]+))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BOX = Pattern.compile(
            "(?:build\\s+)?(?:box|room|hut)(?:\\s+)?(\\d+)\\s*[x×]\\s*(\\d+)\\s*[x×]\\s*(\\d+)(?:\\s+(?:of\\s+)?([a-z_ ]+))?",
            Pattern.CASE_INSENSITIVE);

    private final JavaPlugin plugin;
    private final NpcService npcService;
    private final ChestNetwork chests;
    private final LearningService learning;
    private final Map<UUID, BuildJob> jobs = new ConcurrentHashMap<>();
    private CrewMessenger messenger;

    public BuilderSkill(JavaPlugin plugin, NpcService npcService, ChestNetwork chests, LearningService learning) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.chests = chests;
        this.learning = learning;
    }

    public void setMessenger(CrewMessenger messenger) {
        this.messenger = messenger;
    }

    public List<String> startJob(CrewBot bot, String order, Location origin) {
        List<String> lines = new ArrayList<>();
        BuildJob job = parseOrder(order, origin, bot);
        if (job == null) {
            lines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.GRAY
                    + ": Builder orders — try: wall 5, platform 3x3, pillar 4, box 4x3x3 [material]");
            return lines;
        }
        jobs.put(bot.getId(), job);
        bot.setStatus(BotStatus.BUSY);
        bot.setCurrentOrder(order);
        lines.add(org.bukkit.ChatColor.GOLD + bot.getName() + org.bukkit.ChatColor.WHITE
                + ": Building " + job.label + " (" + job.remaining() + " blocks, "
                + pretty(job.material) + ").");
        learning.observe(bot, "build_start", job.label + " " + job.material, true, job.material.name());
        return lines;
    }

    public void clear(CrewBot bot) {
        jobs.remove(bot.getId());
    }

    public void tick(CrewBot bot) {
        if (bot.getTitle() == null || bot.getTitle() != BotTitle.BUILDER) {
            return;
        }
        if (bot.getStatus() == BotStatus.STOPPED || bot.getStatus() == BotStatus.DISMISSED) {
            return;
        }

        BuildJob job = jobs.get(bot.getId());
        if (job == null) {
            String order = bot.getCurrentOrder();
            if (order != null && looksLikeBuild(order) && bot.getStatus() == BotStatus.BUSY) {
                NpcHandle body = npcService.ensureBody(bot);
                Location origin = body != null && body.isValid() ? body.getLocation() : bot.getHome();
                startJob(bot, order, origin);
                job = jobs.get(bot.getId());
            }
            if (job == null) {
                return;
            }
        }

        NpcHandle body = npcService.ensureBody(bot);
        if (body == null || !body.isValid()) {
            return;
        }
        Location loc = body.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }

        if (job.done()) {
            jobs.remove(bot.getId());
            bot.setStatus(BotStatus.IDLE);
            bot.setCurrentOrder(null);
            learning.observe(bot, "build_done", job.label, true, job.material.name());
            return;
        }

        // Ensure we have a block to place
        if (!ensureMaterial(bot, body, job)) {
            return;
        }

        Location placeAt = job.nextPlace();
        if (placeAt == null) {
            jobs.remove(bot.getId());
            bot.setStatus(BotStatus.IDLE);
            bot.setCurrentOrder(null);
            return;
        }

        // Walk close then place
        double dist = loc.distance(placeAt);
        if (dist > 3.5) {
            body.walkTo(placeAt.clone().add(0.5, 0, 0.5), 1.05);
            return;
        }

        Block block = placeAt.getBlock();
        if (!block.getType().isAir() && block.getType() != Material.WATER && block.getType() != Material.CAVE_AIR
                && block.getType() != Material.VOID_AIR && block.getType() != Material.SHORT_GRASS
                && block.getType() != Material.TALL_GRASS && block.getType() != Material.SNOW) {
            job.advance();
            return;
        }

        // Consume one from loot
        if (bot.getLoot().count(job.material) < 1) {
            if (!ensureMaterial(bot, body, job)) {
                return;
            }
        }
        if (bot.getLoot().remove(job.material, 1) < 1) {
            return;
        }

        block.setType(job.material, true);
        if (body instanceof VillagerHandle vh) {
            vh.lookAt(placeAt.clone().add(0.5, 0.5, 0.5));
        }
        if (body.getEntity() instanceof LivingEntity living) {
            living.swingMainHand();
        }
        job.advance();
        learning.observe(bot, "build_place", job.material.name() + " @ " + block.getX() + "," + block.getY() + "," + block.getZ(),
                true, job.material.name());
    }

    private boolean ensureMaterial(CrewBot bot, NpcHandle body, BuildJob job) {
        if (bot.getLoot().count(job.material) > 0) {
            return true;
        }
        // Withdraw from storage
        int need = Math.min(16, Math.max(1, job.remaining()));
        ItemStack got = chests.withdraw(job.material, need);
        if (got != null && got.getAmount() > 0) {
            bot.getLoot().add(got);
            if (bot.getLoot().count(job.material) > 0) {
                return true;
            }
        }

        // Walk to nearest chest and try again next tick
        Location chest = chests.nearestChest(body.getLocation());
        if (chest != null && body.getLocation().distance(chest) > 2.5) {
            body.walkTo(chest.clone().add(0.5, 0, 0.5), 1.0);
            return false;
        }

        // Request help once
        if (!job.requestedHelp && messenger != null) {
            job.requestedHelp = true;
            messenger.needMaterial(bot, job.material, Math.max(8, job.remaining()))
                    .ifPresent(name -> bot.remember("Asked " + name + " for " + job.material));
            learning.observe(bot, "build_need", "Need " + job.material, false, job.material.name());
        }
        return bot.getLoot().count(job.material) > 0;
    }

    public static boolean looksLikeBuild(String order) {
        if (order == null) {
            return false;
        }
        String o = order.toLowerCase(Locale.ROOT);
        return o.contains("build") || o.contains("wall") || o.contains("platform")
                || o.contains("pillar") || o.contains("floor") || o.contains("hut")
                || o.contains("room") || o.contains("box");
    }

    private BuildJob parseOrder(String order, Location origin, CrewBot bot) {
        if (order == null || origin == null || origin.getWorld() == null) {
            return null;
        }
        Location base = origin.clone();
        String lower = order.toLowerCase(Locale.ROOT);
        // "build at home" uses home anchor; otherwise build at current position
        if (lower.contains("home") && bot.getHome() != null && bot.getHome().getWorld() != null) {
            base = bot.getHome().clone();
        }
        float yaw = base.getYaw();
        BlockFace face = yawToFace(yaw);

        Matcher box = BOX.matcher(order);
        if (box.find()) {
            int w = clamp(parseInt(box.group(1), 4), 2, 16);
            int h = clamp(parseInt(box.group(2), 3), 2, 8);
            int d = clamp(parseInt(box.group(3), 4), 2, 16);
            Material mat = resolveMaterial(box.group(4), Material.COBBLESTONE);
            List<Location> cells = new ArrayList<>();
            World world = base.getWorld();
            int x0 = base.getBlockX();
            int y0 = base.getBlockY();
            int z0 = base.getBlockZ();
            // Hollow box walls
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    for (int z = 0; z < d; z++) {
                        boolean edge = x == 0 || x == w - 1 || z == 0 || z == d - 1 || y == 0 || y == h - 1;
                        if (edge) {
                            // open doorway on front bottom center
                            if (y < 2 && z == 0 && x == w / 2) {
                                continue;
                            }
                            cells.add(new Location(world, x0 + x, y0 + y, z0 + z));
                        }
                    }
                }
            }
            return new BuildJob("box " + w + "x" + h + "x" + d, mat, cells);
        }

        Matcher plat = PLATFORM.matcher(order);
        if (plat.find()) {
            int w = clamp(parseInt(plat.group(1), 3), 1, 16);
            int d = clamp(parseInt(plat.group(2), 3), 1, 16);
            Material mat = resolveMaterial(plat.group(3), Material.OAK_PLANKS);
            List<Location> cells = new ArrayList<>();
            World world = base.getWorld();
            int x0 = base.getBlockX() - w / 2;
            int y0 = base.getBlockY() - 1;
            int z0 = base.getBlockZ() - d / 2;
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    cells.add(new Location(world, x0 + x, y0, z0 + z));
                }
            }
            return new BuildJob("platform " + w + "x" + d, mat, cells);
        }

        Matcher wall = WALL.matcher(order);
        if (wall.find()) {
            int length = clamp(parseInt(wall.group(1), 5), 1, 32);
            int height = clamp(parseInt(wall.group(2), 2), 1, 8);
            Material mat = resolveMaterial(wall.group(3), Material.COBBLESTONE);
            List<Location> cells = new ArrayList<>();
            World world = base.getWorld();
            int dx = face.getModX();
            int dz = face.getModZ();
            // wall extends left-right relative to facing
            int sx = dz != 0 ? 1 : 0;
            int sz = dx != 0 ? 1 : 0;
            if (sx == 0 && sz == 0) {
                sx = 1;
            }
            int x0 = base.getBlockX() + dx;
            int y0 = base.getBlockY();
            int z0 = base.getBlockZ() + dz;
            for (int i = 0; i < length; i++) {
                for (int y = 0; y < height; y++) {
                    cells.add(new Location(world, x0 + i * sx, y0 + y, z0 + i * sz));
                }
            }
            return new BuildJob("wall " + length + "x" + height, mat, cells);
        }

        Matcher pillar = PILLAR.matcher(order);
        if (pillar.find()) {
            int height = clamp(parseInt(pillar.group(1), 4), 1, 16);
            Material mat = resolveMaterial(pillar.group(2), Material.COBBLESTONE);
            List<Location> cells = new ArrayList<>();
            World world = base.getWorld();
            int x0 = base.getBlockX() + face.getModX();
            int y0 = base.getBlockY();
            int z0 = base.getBlockZ() + face.getModZ();
            for (int y = 0; y < height; y++) {
                cells.add(new Location(world, x0, y0 + y, z0));
            }
            return new BuildJob("pillar " + height, mat, cells);
        }

        return null;
    }

    private static Material resolveMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        // common aliases
        return switch (key) {
            case "COBBLE", "COBBLESTONE" -> Material.COBBLESTONE;
            case "STONE" -> Material.STONE;
            case "DIRT" -> Material.DIRT;
            case "OAK", "WOOD", "PLANKS", "OAK_PLANKS" -> Material.OAK_PLANKS;
            case "SPRUCE", "SPRUCE_PLANKS" -> Material.SPRUCE_PLANKS;
            case "BRICK", "BRICKS" -> Material.BRICKS;
            case "STONE_BRICKS", "STONEBRICK" -> Material.STONE_BRICKS;
            case "DEEPSLATE", "COBBLED_DEEPSLATE" -> Material.COBBLED_DEEPSLATE;
            case "NETHERRACK" -> Material.NETHERRACK;
            case "SANDSTONE" -> Material.SANDSTONE;
            default -> {
                try {
                    Material m = Material.valueOf(key);
                    yield m.isBlock() ? m : fallback;
                } catch (IllegalArgumentException e) {
                    List<Material> hits = ChestNetwork.matchMaterials(raw);
                    for (Material m : hits) {
                        if (m.isBlock() && m.isSolid()) {
                            yield m;
                        }
                    }
                    yield fallback;
                }
            }
        };
    }

    private static BlockFace yawToFace(float yaw) {
        float rot = (yaw % 360 + 360) % 360;
        if (rot >= 315 || rot < 45) {
            return BlockFace.SOUTH;
        }
        if (rot < 135) {
            return BlockFace.WEST;
        }
        if (rot < 225) {
            return BlockFace.NORTH;
        }
        return BlockFace.EAST;
    }

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static final class BuildJob {
        final String label;
        final Material material;
        final List<Location> cells;
        int index;
        boolean requestedHelp;

        BuildJob(String label, Material material, List<Location> cells) {
            this.label = label;
            this.material = material;
            this.cells = cells;
            this.index = 0;
        }

        Location nextPlace() {
            if (index >= cells.size()) {
                return null;
            }
            return cells.get(index);
        }

        void advance() {
            index++;
        }

        boolean done() {
            return index >= cells.size();
        }

        int remaining() {
            return Math.max(0, cells.size() - index);
        }
    }
}
