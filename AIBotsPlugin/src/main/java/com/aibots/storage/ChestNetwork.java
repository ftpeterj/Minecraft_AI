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
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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
        // Hub is a preferred place-near point only — do NOT register as a chest
        // until a real chest block is placed (prevents "phantom" outdoor slots).
        this.hub = hub == null ? null : hub.getBlock().getLocation().clone();
        save();
    }

    public List<Location> getChests() {
        return List.copyOf(chests);
    }

    public void registerChest(Location loc) {
        if (registerChestQuiet(loc)) {
            save();
        }
    }

    /** @return true if newly added */
    private boolean registerChestQuiet(Location loc) {
        if (loc == null) {
            return false;
        }
        Location blockLoc = loc.getBlock().getLocation();
        for (Location existing : chests) {
            if (sameBlock(existing, blockLoc)) {
                return false;
            }
        }
        chests.add(blockLoc);
        if (hub == null) {
            hub = blockLoc.clone();
        }
        return true;
    }

    /** True if this location is already in the network. */
    public boolean isRegistered(Location loc) {
        if (loc == null) {
            return false;
        }
        Location blockLoc = loc.getBlock().getLocation();
        for (Location existing : chests) {
            if (sameBlock(existing, blockLoc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Register every storage container inside the axis-aligned box defined by two corners
     * (same geometry as Minecraft {@code /fill x1 y1 z1 x2 y2 z2}).
     * <p>
     * Loads every chunk in the box first so unloaded chests are still found.
     * Detects singles, double-chest halves, barrels, and shulkers.
     * Does not place or destroy blocks — only links existing containers.
     */
    public RegionResult registerRegion(Location cornerA, Location cornerB) {
        RegionResult result = new RegionResult();
        if (cornerA == null || cornerB == null
                || cornerA.getWorld() == null || cornerB.getWorld() == null
                || !cornerA.getWorld().equals(cornerB.getWorld())) {
            result.error = "Both corners must be in the same world.";
            return result;
        }
        World world = cornerA.getWorld();
        int x1 = Math.min(cornerA.getBlockX(), cornerB.getBlockX());
        int y1 = Math.min(cornerA.getBlockY(), cornerB.getBlockY());
        int z1 = Math.min(cornerA.getBlockZ(), cornerB.getBlockZ());
        int x2 = Math.max(cornerA.getBlockX(), cornerB.getBlockX());
        int y2 = Math.max(cornerA.getBlockY(), cornerB.getBlockY());
        int z2 = Math.max(cornerA.getBlockZ(), cornerB.getBlockZ());

        // Thin / flat selection (common with pos1/pos2 on the floor) misses wall chests.
        // Auto-expand height so a "room" scan still works.
        int padBelow = plugin.getConfig().getInt("storage.register-y-pad-below", 1);
        int padAbove = plugin.getConfig().getInt("storage.register-y-pad-above", 6);
        int minThickness = plugin.getConfig().getInt("storage.register-min-y-thickness", 3);
        if ((y2 - y1 + 1) < minThickness) {
            result.yPadded = true;
            y1 -= Math.max(0, padBelow);
            y2 += Math.max(0, padAbove);
        }

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        y1 = Math.max(minY, y1);
        y2 = Math.min(maxY, y2);

        result.x1 = x1;
        result.y1 = y1;
        result.z1 = z1;
        result.x2 = x2;
        result.y2 = y2;
        result.z2 = z2;
        result.dx = x2 - x1 + 1;
        result.dy = y2 - y1 + 1;
        result.dz = z2 - z1 + 1;

        long volume = (long) result.dx * result.dy * result.dz;
        int maxVolume = plugin.getConfig().getInt("storage.register-max-volume", 200_000);
        result.volume = volume;
        if (volume > maxVolume) {
            result.error = "Region too large (" + volume + " blocks, "
                    + result.dx + "×" + result.dy + "×" + result.dz + "). Max "
                    + maxVolume + " (config storage.register-max-volume).";
            return result;
        }

        // /fill-style: ensure every chunk in the box is loaded before reading blocks
        int cx1 = x1 >> 4;
        int cz1 = z1 >> 4;
        int cx2 = x2 >> 4;
        int cz2 = z2 >> 4;
        int chunksLoaded = 0;
        for (int cx = cx1; cx <= cx2; cx++) {
            for (int cz = cz1; cz <= cz2; cz++) {
                // Force-load so unloaded chest blocks are not read as air
                world.getChunkAt(cx, cz).load(true);
                chunksLoaded++;
            }
        }
        result.chunksLoaded = chunksLoaded;

        int before = chests.size();
        int singles = 0;
        int doubleHalves = 0;
        int barrels = 0;
        int shulkers = 0;

        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (!isStorageContainer(type)) {
                        continue;
                    }
                    result.found++;
                    if (type == Material.BARREL) {
                        barrels++;
                    } else if (type.name().endsWith("SHULKER_BOX")) {
                        shulkers++;
                    } else if (type == Material.CHEST || type == Material.TRAPPED_CHEST) {
                        if (isDoubleChestHalf(block)) {
                            doubleHalves++;
                        } else {
                            singles++;
                        }
                    }

                    if (isRegistered(block.getLocation())) {
                        result.already++;
                        continue;
                    }
                    if (registerChestQuiet(block.getLocation())) {
                        result.registered++;
                    }
                }
            }
        }
        result.singles = singles;
        result.doubleHalves = doubleHalves;
        result.barrels = barrels;
        result.shulkers = shulkers;
        // unique double chests ≈ halves/2 (both halves usually in volume)
        result.doubleChests = (doubleHalves + 1) / 2;

        // Hub = center of region (overwrite so deposits prefer this room)
        if (result.registered > 0 || result.already > 0) {
            hub = new Location(world, (x1 + x2) / 2.0, (y1 + y2) / 2.0, (z1 + z2) / 2.0)
                    .getBlock().getLocation();
            save();
        }
        result.networkSize = chests.size();
        result.added = chests.size() - before;
        result.uniqueInventories = uniqueInventories().size();
        result.freeSlots = freeSlots();

        plugin.getLogger().info("[AIBots] registerRegion /fill-box "
                + x1 + "," + y1 + "," + z1 + " → " + x2 + "," + y2 + "," + z2
                + " vol=" + result.volume
                + " found=" + result.found
                + " (single=" + singles + " doubleHalves=" + doubleHalves
                + " barrel=" + barrels + " shulker=" + shulkers + ")"
                + " new=" + result.registered + " already=" + result.already
                + " invs=" + result.uniqueInventories
                + " network=" + result.networkSize);
        return result;
    }

    private static boolean isDoubleChestHalf(Block block) {
        if (block == null) {
            return false;
        }
        if (!(block.getBlockData() instanceof Chest chest)) {
            return false;
        }
        return chest.getType() == Chest.Type.LEFT || chest.getType() == Chest.Type.RIGHT;
    }

    /** Drop all registrations (does not break blocks in the world). */
    public int clearRegistrations() {
        int n = chests.size();
        chests.clear();
        save();
        return n;
    }

    public static boolean isStorageContainer(Material type) {
        if (type == null || type.isAir()) {
            return false;
        }
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL) {
            return true;
        }
        String n = type.name();
        return n.endsWith("SHULKER_BOX");
    }

    public static final class RegionResult {
        public int found;
        public int registered;
        public int already;
        public int added;
        public int networkSize;
        public int uniqueInventories;
        public int freeSlots;
        public long volume;
        public int x1, y1, z1, x2, y2, z2;
        public int dx, dy, dz;
        public int chunksLoaded;
        public int singles;
        public int doubleHalves;
        public int doubleChests;
        public int barrels;
        public int shulkers;
        /** True when Y was auto-expanded because the box was too flat. */
        public boolean yPadded;
        public String error;

        public boolean ok() {
            return error == null || error.isBlank();
        }

        public String sizeLabel() {
            return dx + "×" + dy + "×" + dz;
        }

        public String cornerLabel() {
            return x1 + " " + y1 + " " + z1 + "  →  " + x2 + " " + y2 + " " + z2;
        }
    }

    /** Ensure at least one chest exists near home; place if needed. */
    public Location ensureStorageNear(Location home) {
        pruneInvalid();
        if (!chests.isEmpty()) {
            // Do NOT reconnect every call — that rewrote chests every scavenger tick and stalled bots
            return nearestChest(home);
        }
        if (home == null || home.getWorld() == null) {
            return null;
        }
        // If a container is already next to home, register it — never wipe/replace
        Location existing = findNearbyContainer(home, 4);
        if (existing != null) {
            registerChest(existing);
            // Register the other half of a double chest if present
            registerAdjacentChestHalves(existing.getBlock());
            plugin.getLogger().info("[AIBots] Linked existing chest at "
                    + existing.getBlockX() + "," + existing.getBlockY() + "," + existing.getBlockZ());
            return existing;
        }
        Location place = findPlaceSpot(home);
        if (place == null) {
            // Same floor as home — never highest-block (rooftop / outside ledge)
            place = home.clone().add(1, 0, 0);
            place.setY(home.getBlockY());
            if (!place.getBlock().getType().isAir()
                    || !place.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                plugin.getLogger().warning("[AIBots] No free floor spot for chest near home "
                        + home.getBlockX() + "," + home.getBlockY() + "," + home.getBlockZ());
                return null;
            }
        }
        // Never place over an existing container
        if (isStorageContainer(place.getBlock().getType())) {
            registerChest(place);
            registerAdjacentChestHalves(place.getBlock());
            return place.getBlock().getLocation();
        }
        if (preferDouble) {
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Location side = place.clone().add(o[0], 0, o[1]);
                if (side.getBlock().getType().isAir()
                        && side.clone().add(0, -1, 0).getBlock().getType().isSolid()
                        && side.getBlockY() == place.getBlockY()) {
                    placeDoubleChest(place.getBlock(), side.getBlock());
                    registerChest(place);
                    registerChest(side);
                    plugin.getLogger().info("[AIBots] Placed double chest at "
                            + place.getBlockX() + "," + place.getBlockY() + "," + place.getBlockZ());
                    return place.getBlock().getLocation();
                }
            }
        }
        placeSingleChest(place.getBlock(), BlockFace.SOUTH);
        registerChest(place);
        plugin.getLogger().info("[AIBots] Placed chest at "
                + place.getBlockX() + "," + place.getBlockY() + "," + place.getBlockZ());
        return place.getBlock().getLocation();
    }

    private Location findNearbyContainer(Location home, int radius) {
        if (home == null || home.getWorld() == null) {
            return null;
        }
        World world = home.getWorld();
        int ox = home.getBlockX();
        int oy = home.getBlockY();
        int oz = home.getBlockZ();
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block b = world.getBlockAt(ox + x, oy + y, oz + z);
                    if (!isStorageContainer(b.getType())) {
                        continue;
                    }
                    double d = b.getLocation().distanceSquared(home);
                    if (d < bestD) {
                        bestD = d;
                        best = b.getLocation();
                    }
                }
            }
        }
        return best;
    }

    private void registerAdjacentChestHalves(Block block) {
        if (block == null
                || (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST)) {
            return;
        }
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = block.getRelative(face);
            if (n.getType() == Material.CHEST || n.getType() == Material.TRAPPED_CHEST) {
                registerChest(n.getLocation());
            }
        }
    }

    public boolean isNearAny(Location loc, double blocks) {
        if (loc == null || loc.getWorld() == null || blocks <= 0) {
            return false;
        }
        pruneInvalid();
        double r2 = blocks * blocks;
        for (Location c : chests) {
            if (c.getWorld() == null || !c.getWorld().equals(loc.getWorld())) {
                continue;
            }
            if (c.distanceSquared(loc) <= r2) {
                return true;
            }
        }
        return false;
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

    /** Prefer a chest that still has empty slots (or partial stacks of anything). */
    public Location nearestChestWithSpace(Location from) {
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
            int free = freeSlotsInChest(c);
            if (free <= 0) {
                continue;
            }
            double d = c.distanceSquared(from);
            // Prefer more free space when distances are similar
            d -= free * 0.01;
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best != null ? best : nearestChest(from);
    }

    /**
     * Empty inventory slots across the network (double-chests counted once).
     */
    public int freeSlots() {
        pruneInvalid();
        int free = 0;
        for (Inventory inv : uniqueInventories()) {
            free += countFreeSlots(inv);
        }
        return free;
    }

    public boolean isNetworkFull() {
        return freeSlots() <= 0;
    }

    private List<Inventory> uniqueInventories() {
        Set<Inventory> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<Inventory> list = new ArrayList<>();
        for (Location loc : chests) {
            Inventory inv = inventoryAt(loc);
            if (inv != null && seen.add(inv)) {
                list.add(inv);
            }
        }
        return list;
    }

    private int freeSlotsInChest(Location loc) {
        Inventory inv = inventoryAt(loc);
        return inv == null ? 0 : countFreeSlots(inv);
    }

    private static int countFreeSlots(Inventory inv) {
        int free = 0;
        for (ItemStack s : inv.getContents()) {
            if (s == null || s.getType().isAir()) {
                free++;
            }
        }
        return free;
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
        int maxStack = Math.min(type.getMaxStackSize(), stack.getMaxStackSize());
        // Fill partial stacks + empty slots across unique inventories first
        for (Inventory inv : uniqueInventories()) {
            remaining = putInto(inv, type, remaining, maxStack);
            if (remaining <= 0) {
                return 0;
            }
        }
        // Network full — place another double chest near the network (same floor / indoor)
        if (remaining > 0 && expandWhenFull && chests.size() < maxChests) {
            Location anchor = nearestChest(stack.getAmount() > 0 && hub != null ? hub : null);
            if (anchor == null) {
                anchor = chests.isEmpty() ? hub : chests.get(chests.size() - 1);
            }
            // Try expand up to 3 times if still full after each place
            for (int attempt = 0; attempt < 3 && remaining > 0 && chests.size() < maxChests; attempt++) {
                if (!expandChest(anchor != null ? anchor : hub)) {
                    break;
                }
                plugin.getLogger().info("[AIBots] Expanded storage network (chests=" + chests.size()
                        + ", freeSlots=" + freeSlots() + ")");
                for (Inventory inv : uniqueInventories()) {
                    remaining = putInto(inv, type, remaining, maxStack);
                    if (remaining <= 0) {
                        return 0;
                    }
                }
                // Next expand near the newest chest
                if (!chests.isEmpty()) {
                    anchor = chests.get(chests.size() - 1);
                }
            }
        }
        return remaining;
    }

    public int count(Material material) {
        int total = 0;
        for (Inventory inv : uniqueInventories()) {
            for (ItemStack s : inv.getContents()) {
                if (s != null && s.getType() == material) {
                    total += s.getAmount();
                }
            }
        }
        return total;
    }

    /** Count items matching a fuzzy name ("oak log", "iron", "cobble"). */
    public int countMatching(String query) {
        int total = 0;
        for (var e : tallyMatching(query).entrySet()) {
            total += e.getValue();
        }
        return total;
    }

    public java.util.LinkedHashMap<Material, Integer> tallyMatching(String query) {
        java.util.LinkedHashMap<Material, Integer> map = new java.util.LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }
        java.util.List<Material> matches = matchMaterials(query);
        java.util.Set<Material> want = matches.isEmpty() ? null : new java.util.HashSet<>(matches);
        String q = query.toLowerCase(java.util.Locale.ROOT);
        String qUnder = q.replace(' ', '_');
        for (Inventory inv : uniqueInventories()) {
            for (ItemStack s : inv.getContents()) {
                if (s == null || s.getType().isAir()) {
                    continue;
                }
                Material t = s.getType();
                boolean ok;
                if (want != null) {
                    ok = want.contains(t);
                } else {
                    String n = t.name().toLowerCase(java.util.Locale.ROOT);
                    ok = n.contains(qUnder) || n.replace('_', ' ').contains(q);
                }
                if (ok) {
                    map.merge(t, s.getAmount(), Integer::sum);
                }
            }
        }
        return map;
    }

    /** Unique double/single chests as numbered storage units (1-based for players). */
    public java.util.List<ChestUnit> listUnits() {
        pruneInvalid();
        java.util.List<ChestUnit> units = new java.util.ArrayList<>();
        Set<Inventory> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        int num = 1;
        for (Location loc : chests) {
            Inventory inv = inventoryAt(loc);
            if (inv == null || !seen.add(inv)) {
                continue;
            }
            java.util.LinkedHashMap<Material, Integer> tally = new java.util.LinkedHashMap<>();
            int used = 0;
            for (ItemStack s : inv.getContents()) {
                if (s != null && !s.getType().isAir()) {
                    used++;
                    tally.merge(s.getType(), s.getAmount(), Integer::sum);
                }
            }
            boolean isDouble = inv.getSize() >= 54;
            units.add(new ChestUnit(num++, loc.clone(), inv.getSize(), inv.getSize() - used, isDouble, tally));
        }
        return units;
    }

    public java.util.Optional<ChestUnit> unit(int oneBased) {
        java.util.List<ChestUnit> units = listUnits();
        if (oneBased < 1 || oneBased > units.size()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(units.get(oneBased - 1));
    }

    public static final class ChestUnit {
        public final int number;
        public final Location location;
        public final int size;
        public final int freeSlots;
        public final boolean doubleChest;
        public final java.util.LinkedHashMap<Material, Integer> contents;

        public ChestUnit(int number, Location location, int size, int freeSlots, boolean doubleChest,
                         java.util.LinkedHashMap<Material, Integer> contents) {
            this.number = number;
            this.location = location;
            this.size = size;
            this.freeSlots = freeSlots;
            this.doubleChest = doubleChest;
            this.contents = contents;
        }

        public int totalItems() {
            return contents.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public static java.util.List<Material> matchMaterials(String query) {
        java.util.List<Material> list = new java.util.ArrayList<>();
        if (query == null || query.isBlank()) {
            return list;
        }
        String raw = query.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            list.add(Material.valueOf(raw));
            return list;
        } catch (IllegalArgumentException ignored) {
        }
        // aliases
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("cobble") && !lower.contains("deep")) {
            list.add(Material.COBBLESTONE);
            return list;
        }
        if (lower.equals("wood") || lower.equals("logs") || lower.equals("log")) {
            for (Material m : Material.values()) {
                if (m.name().endsWith("_LOG")) {
                    list.add(m);
                }
            }
            return list;
        }
        // fuzzy: material names containing all tokens
        String[] tokens = raw.split("_+");
        for (Material m : Material.values()) {
            if (!m.isItem() && !m.isBlock()) {
                continue;
            }
            String n = m.name();
            boolean ok = true;
            for (String t : tokens) {
                if (t.length() < 2) {
                    continue;
                }
                if (!n.contains(t)) {
                    ok = false;
                    break;
                }
            }
            if (ok && tokens.length > 0) {
                list.add(m);
            }
        }
        // Cap runaway matches
        if (list.size() > 40) {
            return list.subList(0, 40);
        }
        return list;
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

    /**
     * Place storage expansion near the existing network on the same floor.
     * Always prefers a full double-chest. If the network is indoors, new chests
     * prefer enclosed spots (ceiling/walls) next to existing chests.
     */
    public boolean expandChest(Location near) {
        if (chests.size() >= maxChests) {
            return false;
        }
        Location anchor = near != null ? near : (hub != null ? hub : (chests.isEmpty() ? null : chests.get(0)));
        if (anchor == null || anchor.getWorld() == null) {
            return false;
        }

        // 1) Complete any single into a double first
        if (preferDouble) {
            for (Location c : List.copyOf(chests)) {
                Block partner = c.getBlock();
                if (partner.getType() != Material.CHEST || !isSingleChest(partner)) {
                    continue;
                }
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    Block cand = partner.getRelative(o[0], 0, o[1]);
                    if (!isValidChestFootprint(cand)) {
                        continue;
                    }
                    placeDoubleChest(partner, cand);
                    registerChest(cand.getLocation());
                    plugin.getLogger().info("[AIBots] Completed double chest at "
                            + cand.getX() + "," + cand.getY() + "," + cand.getZ());
                    return true;
                }
            }
        }

        // 2) Place a brand-new double chest near the network (same floor as anchor)
        boolean anchorIndoor = looksEnclosed(anchor.getBlock());
        BlockPair best = findBestDoubleSpot(anchor, anchorIndoor);
        if (best != null) {
            placeDoubleChest(best.a, best.b);
            registerChest(best.a.getLocation());
            registerChest(best.b.getLocation());
            plugin.getLogger().info("[AIBots] Placed new double chest at "
                    + best.a.getX() + "," + best.a.getY() + "," + best.a.getZ()
                    + (anchorIndoor ? " (indoor-near-network)" : " (near network)"));
            return true;
        }

        // 3) Last resort: single chest on same floor (will complete to double next expand)
        Location single = findPlaceSpot(anchor);
        if (single != null && isValidChestFootprint(single.getBlock())) {
            placeSingleChest(single.getBlock(), BlockFace.SOUTH);
            registerChest(single);
            plugin.getLogger().info("[AIBots] Placed single chest (will double next expand) at "
                    + single.getBlockX() + "," + single.getBlockY() + "," + single.getBlockZ());
            return true;
        }
        return false;
    }

    private static final class BlockPair {
        final Block a;
        final Block b;
        final int score;

        BlockPair(Block a, Block b, int score) {
            this.a = a;
            this.b = b;
            this.score = score;
        }
    }

    private BlockPair findBestDoubleSpot(Location anchor, boolean preferIndoor) {
        World world = anchor.getWorld();
        if (world == null) {
            return null;
        }
        int floorY = anchor.getBlockY();
        // Search around every network chest + hub + anchor (keeps expansions in the house)
        List<Location> centers = new ArrayList<>();
        centers.add(anchor);
        if (hub != null) {
            centers.add(hub);
        }
        centers.addAll(chests);

        BlockPair best = null;
        int[][] pairDirs = {{1, 0}, {0, 1}}; // horizontal double orientations
        int[][] ring = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {3, 0}, {0, 3}, {-3, 0}, {0, -3},
                {2, 1}, {2, -1}, {-2, 1}, {-2, -1},
                {1, 2}, {-1, 2}, {1, -2}, {-1, -2}
        };

        for (Location center : centers) {
            if (center.getWorld() == null || !center.getWorld().equals(world)) {
                continue;
            }
            // Stay on the storage floor (enclosed house), not roof via highest-block
            int cy = center.getBlockY();
            if (Math.abs(cy - floorY) > 1) {
                cy = floorY;
            }
            for (int[] o : ring) {
                for (int[] dir : pairDirs) {
                    int x1 = center.getBlockX() + o[0];
                    int z1 = center.getBlockZ() + o[1];
                    int x2 = x1 + dir[0];
                    int z2 = z1 + dir[1];
                    Block a = world.getBlockAt(x1, cy, z1);
                    Block b = world.getBlockAt(x2, cy, z2);
                    if (!isValidChestFootprint(a) || !isValidChestFootprint(b)) {
                        continue;
                    }
                    // Don't overwrite existing chests
                    if (a.getType() == Material.CHEST || b.getType() == Material.CHEST) {
                        continue;
                    }
                    int score = scoreChestSpot(a, preferIndoor, anchor);
                    score += scoreChestSpot(b, preferIndoor, anchor);
                    // Closer to network is better
                    double dist = a.getLocation().distanceSquared(anchor);
                    score -= (int) Math.min(dist, 50);
                    if (best == null || score > best.score) {
                        best = new BlockPair(a, b, score);
                    }
                }
            }
        }
        return best;
    }

    private static boolean isValidChestFootprint(Block block) {
        if (block == null) {
            return false;
        }
        if (!block.getType().isAir() && block.getType() != Material.LIGHT) {
            return false;
        }
        Block below = block.getRelative(BlockFace.DOWN);
        if (!below.getType().isSolid()) {
            return false;
        }
        // Don't place inside solid ceiling (head-height blocked is OK for chest — chests are 1 high)
        return true;
    }

    /** Higher score = better. Indoors if original storage is enclosed. */
    private static int scoreChestSpot(Block feet, boolean preferIndoor, Location anchor) {
        int score = 10;
        if (looksEnclosed(feet)) {
            score += preferIndoor ? 40 : 5;
        } else if (preferIndoor) {
            score -= 30; // avoid dumping chests outside the house
        }
        // Near walls (furniture against wall) slightly preferred
        int walls = 0;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (feet.getRelative(f).getType().isSolid()) {
                walls++;
            }
        }
        score += walls * 3;
        return score;
    }

    /** True if there's a ceiling and/or surrounding walls — typical indoor room. */
    public static boolean looksEnclosed(Block feet) {
        if (feet == null) {
            return false;
        }
        boolean ceiling = false;
        for (int y = 1; y <= 4; y++) {
            if (feet.getRelative(0, y, 0).getType().isSolid()) {
                ceiling = true;
                break;
            }
        }
        int walls = 0;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = feet.getRelative(f);
            if (n.getType().isSolid() || n.getRelative(0, 1, 0).getType().isSolid()) {
                walls++;
            }
        }
        return ceiling || walls >= 2;
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

        // NEVER air-wipe if either half already holds items (destroys player loot)
        boolean protect = containerHasItems(westOrNorth) || containerHasItems(eastOrSouth);
        if (!protect) {
            westOrNorth.setType(Material.AIR, false);
            eastOrSouth.setType(Material.AIR, false);
            westOrNorth.setType(Material.CHEST, false);
            eastOrSouth.setType(Material.CHEST, false);
        } else if (westOrNorth.getType() != Material.CHEST) {
            westOrNorth.setType(Material.CHEST, false);
        } else if (eastOrSouth.getType() != Material.CHEST) {
            eastOrSouth.setType(Material.CHEST, false);
        }

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

    private static boolean containerHasItems(Block block) {
        if (block == null || !(block.getState() instanceof Container c)) {
            return false;
        }
        for (ItemStack s : c.getInventory().getContents()) {
            if (s != null && !s.getType().isAir()) {
                return true;
            }
        }
        return false;
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
            // Only touch singles that still need a partner — never rewrite working doubles every tick
            if (!isSingleChest(b)) {
                done.add(key);
                continue;
            }
            Block n = findAdjacentSingleChest(b);
            if (n != null) {
                placeDoubleChest(b, n);
                registerChest(n.getLocation());
                done.add(keyOf(b));
                done.add(keyOf(n));
                changed = true;
            }
        }
        if (changed) {
            plugin.getLogger().info("[AIBots] Reconnected adjacent single chests into double chests.");
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

    /**
     * Find air on the same floor as home (interior-safe). Never uses highest-block Y,
     * which drops chests onto roofs and shore ledges outside the build.
     */
    private Location findPlaceSpot(Location home) {
        if (home == null || home.getWorld() == null) {
            return null;
        }
        World world = home.getWorld();
        int hx = home.getBlockX();
        int hy = home.getBlockY();
        int hz = home.getBlockZ();
        // Rings of offsets on the same floor, then slight Y ±1 for stairs
        int[][] offsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {3, 0}, {0, 3}, {-3, 0}, {0, -3},
                {2, 1}, {2, -1}, {-2, 1}, {-2, -1}
        };
        for (int yOff : new int[]{0, -1, 1}) {
            int y = hy + yOff;
            for (int[] o : offsets) {
                int x = hx + o[0];
                int z = hz + o[1];
                Block feet = world.getBlockAt(x, y, z);
                Block below = world.getBlockAt(x, y - 1, z);
                Block head = world.getBlockAt(x, y + 1, z);
                if (!feet.getType().isAir() && feet.getType() != Material.LIGHT) {
                    continue;
                }
                if (!head.getType().isAir() && head.getType().isSolid()) {
                    continue; // inside a solid / tight ceiling
                }
                if (!below.getType().isSolid()) {
                    continue;
                }
                // Prefer spots that still have a solid block somewhere above (indoors)
                // but allow outdoor floor next to home if that's all we have
                return feet.getLocation();
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
        return putInto(inv, type, amount, type.getMaxStackSize());
    }

    private static int putInto(Inventory inv, Material type, int amount, int maxStack) {
        int left = amount;
        int cap = Math.max(1, Math.min(maxStack, 99));
        // fill existing stacks first (aware of partial empty space)
        for (int i = 0; i < inv.getSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType() == type) {
                int stackMax = Math.min(cap, s.getMaxStackSize());
                if (s.getAmount() < stackMax) {
                    int space = stackMax - s.getAmount();
                    int add = Math.min(space, left);
                    s.setAmount(s.getAmount() + add);
                    inv.setItem(i, s);
                    left -= add;
                }
            }
        }
        // empty slots
        for (int i = 0; i < inv.getSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir()) {
                int add = Math.min(cap, left);
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
