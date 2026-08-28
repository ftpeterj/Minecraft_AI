package com.aibots.world;

import org.bukkit.Axis;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restores missing tree trunks where canopies or stumps remain (air only).
 */
public final class TreeHealer {

    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int CHUNKS_PER_TICK = 10;
    private static final Map<Material, Material> LEAF_TO_LOG = new EnumMap<>(Material.class);

    static {
        put("OAK_LEAVES", "OAK_LOG");
        put("BIRCH_LEAVES", "BIRCH_LOG");
        put("SPRUCE_LEAVES", "SPRUCE_LOG");
        put("JUNGLE_LEAVES", "JUNGLE_LOG");
        put("ACACIA_LEAVES", "ACACIA_LOG");
        put("DARK_OAK_LEAVES", "DARK_OAK_LOG");
        put("MANGROVE_LEAVES", "MANGROVE_LOG");
        put("CHERRY_LEAVES", "CHERRY_LOG");
        put("PALE_OAK_LEAVES", "PALE_OAK_LOG");
        put("AZALEA_LEAVES", "OAK_LOG");
        put("FLOWERING_AZALEA_LEAVES", "OAK_LOG");
    }

    private final JavaPlugin plugin;
    private BukkitTask task;

    public TreeHealer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return task != null;
    }

    public void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public int start(World world, List<Long> chunks, CommandSender notify) {
        if (task != null) {
            throw new IllegalStateException("A tree heal is already running.");
        }
        if (chunks.isEmpty()) {
            return 0;
        }
        ArrayDeque<Long> queue = new ArrayDeque<>(chunks);
        int total = queue.size();
        int[] placed = {0};
        int[] scanned = {0};
        notify.sendMessage("§aHealing trees in §f" + total + " §achunks (logs only, air → wood).");
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int n = 0;
            while (n < CHUNKS_PER_TICK && !queue.isEmpty()) {
                long key = queue.removeFirst();
                int cx = (int) (key >> 32);
                int cz = (int) key; // low 32 bits, signed
                try {
                    Chunk chunk = world.getChunkAt(cx, cz);
                    placed[0] += healChunk(chunk);
                } catch (Throwable t) {
                    plugin.getLogger().fine("healtrees chunk " + cx + "," + cz + ": " + t.getMessage());
                }
                scanned[0]++;
                n++;
            }
            if (scanned[0] % 80 == 0 || queue.isEmpty()) {
                notify.sendMessage("§7healtrees: " + scanned[0] + "/" + total
                        + " chunks, placed " + placed[0] + " logs");
            }
            if (queue.isEmpty()) {
                cancel();
                notify.sendMessage("§aTree heal done. Restored §f" + placed[0] + " §alog block(s).");
                plugin.getLogger().info("Tree heal finished: " + placed[0] + " logs in " + scanned[0] + " chunks");
            }
        }, 1L, 1L);
        return total;
    }

    public static List<Long> chunksInRadius(int cx, int cz, int chunkRadius) {
        List<Long> out = new ArrayList<>();
        for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++) {
            for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
                out.add(((x & 0xffffffffL) << 32) | (z & 0xffffffffL));
            }
        }
        return out;
    }

    public static List<Long> generatedOverworldChunks(World world) {
        List<Long> out = new ArrayList<>();
        File regionDir = resolveRegionDir(world);
        if (regionDir == null || !regionDir.isDirectory()) {
            return out;
        }
        File[] files = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
        if (files == null) {
            return out;
        }
        for (File f : files) {
            Matcher m = REGION.matcher(f.getName());
            if (!m.matches()) {
                continue;
            }
            int rx = Integer.parseInt(m.group(1));
            int rz = Integer.parseInt(m.group(2));
            int baseX = rx * 32;
            int baseZ = rz * 32;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                if (raf.length() < 4096) {
                    continue;
                }
                byte[] header = new byte[4096];
                raf.readFully(header);
                for (int i = 0; i < 1024; i++) {
                    int loc = ((header[i * 4] & 0xff) << 16)
                            | ((header[i * 4 + 1] & 0xff) << 8)
                            | (header[i * 4 + 2] & 0xff);
                    if (loc == 0) {
                        continue;
                    }
                    int lx = i & 31;
                    int lz = i >> 5;
                    out.add((((baseX + lx) & 0xffffffffL) << 32) | ((baseZ + lz) & 0xffffffffL));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static File resolveRegionDir(World world) {
        File wf = world.getWorldFolder();
        File nested = new File(wf, "dimensions/minecraft/overworld/region");
        if (nested.isDirectory()) {
            return nested;
        }
        File classic = new File(wf, "region");
        if (classic.isDirectory()) {
            return classic;
        }
        return nested.exists() ? nested : classic;
    }

    private int healChunk(Chunk chunk) {
        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        int[][] leafCount = new int[16][16];
        Material[][] logType = new Material[16][16];
        int[][] minLeafY = new int[16][16];
        int[][] maxLeafY = new int[16][16];
        int[][] logMinY = new int[16][16];
        int[][] logMaxY = new int[16][16];
        boolean[][] hasLog = new boolean[16][16];
        int[][] groundY = new int[16][16];

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                minLeafY[lx][lz] = Integer.MAX_VALUE;
                maxLeafY[lx][lz] = Integer.MIN_VALUE;
                logMinY[lx][lz] = Integer.MAX_VALUE;
                logMaxY[lx][lz] = Integer.MIN_VALUE;
                groundY[lx][lz] = minY;
                int wx = baseX + lx;
                int wz = baseZ + lz;
                Material majorityLeaf = null;
                int majorityN = 0;
                Map<Material, Integer> leafVotes = new EnumMap<>(Material.class);
                boolean seenLeaf = false;
                for (int y = maxY; y >= minY; y--) {
                    Block b = world.getBlockAt(wx, y, wz);
                    Material t = b.getType();
                    Material asLog = LEAF_TO_LOG.get(t);
                    if (asLog != null) {
                        seenLeaf = true;
                        leafCount[lx][lz]++;
                        minLeafY[lx][lz] = Math.min(minLeafY[lx][lz], y);
                        maxLeafY[lx][lz] = Math.max(maxLeafY[lx][lz], y);
                        int n = leafVotes.merge(asLog, 1, Integer::sum);
                        if (n > majorityN) {
                            majorityN = n;
                            majorityLeaf = asLog;
                        }
                    } else if (isLog(t)) {
                        hasLog[lx][lz] = true;
                        logMinY[lx][lz] = Math.min(logMinY[lx][lz], y);
                        logMaxY[lx][lz] = Math.max(logMaxY[lx][lz], y);
                        if (logType[lx][lz] == null) {
                            logType[lx][lz] = t;
                        }
                    } else if (seenLeaf && groundY[lx][lz] == minY && isSoil(t)) {
                        groundY[lx][lz] = y;
                    }
                }
                if (majorityLeaf != null && logType[lx][lz] == null) {
                    logType[lx][lz] = majorityLeaf;
                }
            }
        }

        int placed = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                Material log = logType[lx][lz];
                if (log == null || leafCount[lx][lz] == 0) {
                    continue;
                }
                boolean stump = hasLog[lx][lz];
                boolean localMax = isLocalMax(leafCount, lx, lz);
                if (!stump && !(localMax && leafCount[lx][lz] >= 3)) {
                    continue;
                }
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int top = minLeafY[lx][lz];
                if (top == Integer.MAX_VALUE) {
                    continue;
                }
                int bottom;
                if (stump) {
                    bottom = logMinY[lx][lz];
                } else {
                    int g = groundY[lx][lz];
                    if (g == minY || top - g > 40 || top - g < 2) {
                        continue;
                    }
                    bottom = g + 1;
                }
                if (bottom > top) {
                    continue;
                }
                for (int y = bottom; y <= top; y++) {
                    Block b = world.getBlockAt(wx, y, wz);
                    if (!b.getType().isAir()) {
                        continue;
                    }
                    BlockData data = log.createBlockData();
                    if (data instanceof Orientable orientable) {
                        orientable.setAxis(Axis.Y);
                    }
                    b.setBlockData(data, false);
                    placed++;
                }
            }
        }
        return placed;
    }

    private static boolean isLocalMax(int[][] counts, int x, int z) {
        int v = counts[x][z];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (nx < 0 || nz < 0 || nx > 15 || nz > 15) {
                    continue;
                }
                if (counts[nx][nz] > v) {
                    return false;
                }
            }
        }
        return v > 0;
    }

    private static boolean isLog(Material t) {
        String n = t.name();
        return n.endsWith("_LOG") && !n.contains("STRIPPED") && !n.contains("WALL");
    }

    private static boolean isSoil(Material t) {
        return switch (t) {
            case DIRT, GRASS_BLOCK, PODZOL, COARSE_DIRT, ROOTED_DIRT, MUD, MUDDY_MANGROVE_ROOTS,
                    MOSS_BLOCK, FARMLAND, SAND, RED_SAND, SNOW_BLOCK, NETHERRACK -> true;
            default -> {
                String n = t.name();
                yield n.contains("DIRT") || n.equals("PALE_MOSS_BLOCK");
            }
        };
    }

    private static void put(String leaf, String log) {
        Material l = Material.matchMaterial(leaf);
        Material g = Material.matchMaterial(log);
        if (l != null && g != null) {
            LEAF_TO_LOG.put(l, g);
        }
    }
}
