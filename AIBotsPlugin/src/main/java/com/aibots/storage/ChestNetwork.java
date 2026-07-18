package com.aibots.storage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Linked chests near a home that scavengers fill and expand when full.
 */
public class ChestNetwork {

    private final JavaPlugin plugin;
    private final List<Location> chests = new ArrayList<>();
    private Location hub;
    private final File file;
    private final int maxChests;
    private final boolean expandWhenFull;
    private final boolean preferDouble;

    public ChestNetwork(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "storage.yml");
        this.maxChests = plugin.getConfig().getInt("storage.max-chests", 32);
        this.expandWhenFull = plugin.getConfig().getBoolean("storage.expand-when-full", true);
        this.preferDouble = plugin.getConfig().getBoolean("storage.prefer-double-chest", true);
    }

    public void load() {
        chests.clear();
        hub = null;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        hub = readLoc(yaml.getConfigurationSection("hub"));
        List<?> list = yaml.getList("chests");
        if (list != null) {
            for (Object o : list) {
                if (o instanceof ConfigurationSection sec) {
                    Location loc = readLoc(sec);
                    if (loc != null) {
                        chests.add(loc);
                    }
                } else if (o instanceof java.util.Map<?, ?> map) {
                    YamlConfiguration tmp = new YamlConfiguration();
                    //noinspection unchecked
                    for (var e : ((java.util.Map<String, Object>) map).entrySet()) {
                        tmp.set(e.getKey(), e.getValue());
                    }
                    Location loc = readLoc(tmp);
                    if (loc != null) {
                        chests.add(loc);
                    }
                }
            }
        }
        // Also support indexed sections
        ConfigurationSection cs = yaml.getConfigurationSection("chest-list");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                Location loc = readLoc(cs.getConfigurationSection(k));
                if (loc != null) {
                    chests.add(loc);
                }
            }
        }
        plugin.getLogger().info("Chest network loaded: " + chests.size() + " chest(s).");
        // Fix old placements that were two singles side-by-side
        Bukkit.getScheduler().runTaskLater(plugin, this::reconnectAdjacentSingles, 40L);
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        writeLoc(yaml.createSection("hub"), hub);
        int i = 0;
        for (Location loc : chests) {
            writeLoc(yaml.createSection("chest-list." + i++), loc);
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save storage.yml: " + e.getMessage());
        }
    }

    public Location getHub() {
        return hub;
    }

    public void setHub(Location hub) {
        this.hub = hub == null ? null : hub.clone();
        if (this.hub != null) {
            registerChest(this.hub);
        }
        save();
    }

    public List<Location> getChests() {
        return List.copyOf(chests);
    }

    public void registerChest(Location loc) {
        if (loc == null) {
            return;
        }
        Location blockLoc = loc.getBlock().getLocation();
        for (Location existing : chests) {
            if (sameBlock(existing, blockLoc)) {
                return;
            }
        }
        chests.add(blockLoc);
        if (hub == null) {
            hub = blockLoc.clone();
        }
        save();
    }

    /** Ensure at least one chest exists near home; place if needed. */
    public Location ensureStorageNear(Location home) {
        pruneInvalid();
        if (!chests.isEmpty()) {
            // Try to pair any existing single chests that sit adjacent
            reconnectAdjacentSingles();
            return nearestChest(home);
        }
        if (home == null || home.getWorld() == null) {
            return null;
        }
        Location place = findPlaceSpot(home);
        if (place == null) {
            place = home.clone().add(2, 0, 0);
            place.setY(home.getWorld().getHighestBlockYAt(place) + 1);
        }
        if (preferDouble) {
            Location side = place.clone().add(1, 0, 0);
            if (side.getBlock().getType().isAir()
                    && side.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                placeDoubleChest(place.getBlock(), side.getBlock());
                registerChest(place);
                registerChest(side);
                return place.getBlock().getLocation();
            }
        }
        placeSingleChest(place.getBlock(), BlockFace.SOUTH);
        registerChest(place);
        return place.getBlock().getLocation();
    }

    public Location nearestChest(Location from) {
        pruneInvalid();
        if (chests.isEmpty() || from == null) {
            return null;
        }
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (Location c : chests) {
            if (c.getWorld() == null || from.getWorld() == null || !c.getWorld().equals(from.getWorld())) {
                continue;
            }
            double d = c.distanceSquared(from);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    public int depositAll(Inventory source) {
        pruneInvalid();
        int moved = 0;
        for (int i = 0; i < source.getSize(); i++) {
            ItemStack stack = source.getItem(i);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            int left = depositStack(stack);
            if (left <= 0) {
                source.setItem(i, null);
                moved += stack.getAmount();
            } else if (left < stack.getAmount()) {
                moved += stack.getAmount() - left;
                stack.setAmount(left);
                source.setItem(i, stack);
            }
        }
        return moved;
    }

    /** @return remaining amount that could not fit */
    public int depositStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 0;
        }
        int remaining = stack.getAmount();
        Material type = stack.getType();
        for (Location loc : chests) {
            Inventory inv = inventoryAt(loc);
            if (inv == null) {
                continue;
            }
            remaining = putInto(inv, type, remaining);
            if (remaining <= 0) {
                return 0;
            }
        }
        if (remaining > 0 && expandWhenFull && chests.size() < maxChests) {
            Location anchor = chests.isEmpty() ? hub : chests.get(chests.size() - 1);
            if (anchor != null && expandChest(anchor)) {
                Inventory inv = inventoryAt(chests.get(chests.size() - 1));
                if (inv != null) {
                    remaining = putInto(inv, type, remaining);
                }
            }
        }
        return remaining;
    }

    public int count(Material material) {
        int total = 0;
        for (Location loc : chests) {
            Inventory inv = inventoryAt(loc);
            if (inv == null) {
                continue;
            }
            for (ItemStack s : inv.getContents()) {
                if (s != null && s.getType() == material) {
                    total += s.getAmount();
                }
            }
        }
        return total;
    }

    public ItemStack withdraw(Material material, int amount) {
        int need = amount;
        ItemStack result = new ItemStack(material, 0);
        for (Location loc : chests) {
            Inventory inv = inventoryAt(loc);
            if (inv == null) {
                continue;
            }
            for (int i = 0; i < inv.getSize() && need > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (s == null || s.getType() != material) {
                    continue;
                }
                int take = Math.min(need, s.getAmount());
                s.setAmount(s.getAmount() - take);
                if (s.getAmount() <= 0) {
                    inv.setItem(i, null);
                } else {
                    inv.setItem(i, s);
                }
                result.setAmount(result.getAmount() + take);
                need -= take;
            }
        }
        return result.getAmount() > 0 ? result : null;
    }

    public boolean expandChest(Location near) {
        if (chests.size() >= maxChests || near == null) {
            return false;
        }
        // Prefer completing a double-chest with an adjacent single
        if (preferDouble) {
            Block partner = near.getBlock();
            if (partner.getType() == Material.CHEST && isSingleChest(partner)) {
                int[][] adj = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] o : adj) {
                    Block cand = partner.getRelative(o[0], 0, o[1]);
                    if (!cand.getType().isAir()) {
                        continue;
                    }
                    if (!cand.getRelative(BlockFace.DOWN).getType().isSolid()) {
                        continue;
                    }
                    placeDoubleChest(partner, cand);
                    registerChest(cand.getLocation());
                    return true;
                }
            }
        }
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {0, 2}};
        for (int[] o : offsets) {
            Location candidate = near.clone().add(o[0], 0, o[1]);
            Block ground = candidate.clone().add(0, -1, 0).getBlock();
            Block block = candidate.getBlock();
            if (!block.getType().isAir()) {
                continue;
            }
            if (!ground.getType().isSolid()) {
                continue;
            }
            // If next to an existing single chest, form a double
            Block neighbor = findAdjacentSingleChest(block);
            if (preferDouble && neighbor != null) {
                placeDoubleChest(neighbor, block);
            } else if (preferDouble) {
                Block side = block.getRelative(BlockFace.EAST);
                if (side.getType().isAir() && side.getRelative(BlockFace.DOWN).getType().isSolid()) {
                    placeDoubleChest(block, side);
                    registerChest(candidate);
                    registerChest(side.getLocation());
                    return true;
                }
                placeSingleChest(block, BlockFace.SOUTH);
            } else {
                placeSingleChest(block, BlockFace.SOUTH);
            }
            registerChest(candidate);
            return true;
        }
        Location up = near.clone().add(0, 1, 0);
        if (up.getBlock().getType().isAir()) {
            placeSingleChest(up.getBlock(), BlockFace.SOUTH);
            registerChest(up);
            return true;
        }
        return false;
    }

    /**
     * Place two adjacent chests as a proper double-chest (shared 54-slot inventory).
     * Simply setType(CHEST) twice leaves two unconnected singles.
     * <p>
     * Minecraft {@code type=left|right} is relative to the chest's {@code facing}
     * (the direction the latch points). Standing looking the same way as {@code facing}:
     * left half = LEFT, right half = RIGHT.
     */
    public static void placeDoubleChest(Block a, Block b) {
        if (a == null || b == null) {
            return;
        }
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();
        if (dy != 0 || Math.abs(dx) + Math.abs(dz) != 1) {
            placeSingleChest(a, BlockFace.NORTH);
            placeSingleChest(b, BlockFace.NORTH);
            return;
        }

        // Pair along X → face north/south; along Z → face east/west
        final BlockFace facing = (dx != 0) ? BlockFace.NORTH : BlockFace.WEST;

        // Identify west/east or north/south block
        Block westOrNorth;
        Block eastOrSouth;
        if (dx != 0) {
            westOrNorth = a.getX() < b.getX() ? a : b; // west
            eastOrSouth = a.getX() < b.getX() ? b : a; // east
        } else {
            westOrNorth = a.getZ() < b.getZ() ? a : b; // north (smaller Z)
            eastOrSouth = a.getZ() < b.getZ() ? b : a; // south
        }

        Chest.Type typeWestOrNorth;
        Chest.Type typeEastOrSouth;
        // Facing NORTH: look north → left=west, right=east
        // Facing SOUTH: look south → left=east, right=west
        // Facing WEST: look west → left=south, right=north
        // Facing EAST: look east → left=north, right=south
        if (facing == BlockFace.NORTH) {
            typeWestOrNorth = Chest.Type.LEFT;   // west
            typeEastOrSouth = Chest.Type.RIGHT;  // east
        } else if (facing == BlockFace.SOUTH) {
            typeWestOrNorth = Chest.Type.RIGHT;  // west
            typeEastOrSouth = Chest.Type.LEFT;   // east
        } else if (facing == BlockFace.WEST) {
            // westOrNorth is north, eastOrSouth is south
            typeWestOrNorth = Chest.Type.RIGHT;  // north
            typeEastOrSouth = Chest.Type.LEFT;   // south
        } else { // EAST
            typeWestOrNorth = Chest.Type.LEFT;   // north
            typeEastOrSouth = Chest.Type.RIGHT;  // south
        }

        // Clear first so clients don't keep stale single-chest states
        westOrNorth.setType(Material.AIR, false);
        eastOrSouth.setType(Material.AIR, false);

        westOrNorth.setType(Material.CHEST, false);
        eastOrSouth.setType(Material.CHEST, false);

        Chest dataW = (Chest) Bukkit.createBlockData(Material.CHEST);
        Chest dataE = (Chest) Bukkit.createBlockData(Material.CHEST);
        dataW.setFacing(facing);
        dataE.setFacing(facing);
        dataW.setType(typeWestOrNorth);
        dataE.setType(typeEastOrSouth);

        westOrNorth.setBlockData(dataW, false);
        eastOrSouth.setBlockData(dataE, false);
        // Physics/update so inventory links and clients refresh
        westOrNorth.getState().update(true, true);
        eastOrSouth.getState().update(true, true);
    }

    public static void placeSingleChest(Block block, BlockFace facing) {
        if (block == null) {
            return;
        }
        block.setType(Material.CHEST, false);
        Chest data = (Chest) Material.CHEST.createBlockData();
        data.setFacing(facing == null ? BlockFace.SOUTH : facing);
        data.setType(Chest.Type.SINGLE);
        block.setBlockData(data, true);
    }

    private static boolean isSingleChest(Block block) {
        if (block.getType() != Material.CHEST) {
            return false;
        }
        if (block.getBlockData() instanceof Chest chest) {
            return chest.getType() == Chest.Type.SINGLE;
        }
        return true;
    }

    private static Block findAdjacentSingleChest(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = block.getRelative(face);
            if (isSingleChest(n)) {
                return n;
            }
        }
        return null;
    }

    /** Fix already-placed adjacent singles into double chests (e.g. old bot placements). */
    public void reconnectAdjacentSingles() {
        boolean changed = false;
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Location loc : List.copyOf(chests)) {
            Block b = loc.getBlock();
            if (b.getType() != Material.CHEST) {
                continue;
            }
            String key = keyOf(b);
            if (done.contains(key)) {
                continue;
            }
            // Pair with any cardinal neighbor chest (even if already typed wrong)
            Block n = null;
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block cand = b.getRelative(face);
                if (cand.getType() == Material.CHEST) {
                    n = cand;
                    break;
                }
            }
            if (n != null) {
                placeDoubleChest(b, n);
                registerChest(n.getLocation());
                done.add(keyOf(b));
                done.add(keyOf(n));
                changed = true;
            }
        }
        if (changed) {
            plugin.getLogger().info("[AIBots] Reconnected adjacent chests into double chests.");
            save();
        }
    }

    private static String keyOf(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /** Public for /crew storage fix */
    public int forceReconnectAll() {
        int before = chests.size();
        reconnectAdjacentSingles();
        return before;
    }

    private Location findPlaceSpot(Location home) {
        int[][] offsets = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {3, 1}, {1, 3}};
        for (int[] o : offsets) {
            Location c = home.clone().add(o[0], 0, o[1]);
            c.setY(home.getWorld().getHighestBlockYAt(c) + 1);
            if (c.getBlock().getType().isAir() && c.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                return c;
            }
        }
        return null;
    }

    private void pruneInvalid() {
        Iterator<Location> it = chests.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Location loc = it.next();
            if (loc.getWorld() == null || !(loc.getBlock().getState() instanceof Container)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    private static Inventory inventoryAt(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        if (loc.getBlock().getState() instanceof Container container) {
            return container.getInventory();
        }
        return null;
    }

    private static int putInto(Inventory inv, Material type, int amount) {
        int left = amount;
        // fill existing stacks
        for (int i = 0; i < inv.getSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType() == type && s.getAmount() < s.getMaxStackSize()) {
                int space = s.getMaxStackSize() - s.getAmount();
                int add = Math.min(space, left);
                s.setAmount(s.getAmount() + add);
                inv.setItem(i, s);
                left -= add;
            }
        }
        // empty slots
        for (int i = 0; i < inv.getSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir()) {
                int add = Math.min(type.getMaxStackSize(), left);
                inv.setItem(i, new ItemStack(type, add));
                left -= add;
            }
        }
        return left;
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private static void writeLoc(ConfigurationSection section, Location loc) {
        if (section == null) {
            return;
        }
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        section.set("world", loc.getWorld().getName());
        section.set("x", loc.getBlockX());
        section.set("y", loc.getBlockY());
        section.set("z", loc.getBlockZ());
    }

    private static Location readLoc(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        String w = section.getString("world");
        World world = w == null ? null : Bukkit.getWorld(w);
        if (world == null) {
            return null;
        }
        return new Location(world, section.getInt("x"), section.getInt("y"), section.getInt("z"));
    }
}
