package com.bags;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * A bag is a plain item (SADDLE — not placeable, no vanilla interaction of
 * its own) carrying its own contents in NBT: a unique id and the serialized
 * 54-slot inventory, both stored in its PersistentDataContainer. Fully
 * portable — contents travel with the item through trading, dropping,
 * dying, moving between inventories — with no external database dependency.
 */
final class BagItem {

    static final int SIZE = 54;
    /**
     * The last slot is always a reserved "Close" button, not real storage —
     * needed because a bag opened from inside another window (e.g.
     * BotInterop's /botinv) replaces that window with the bag's own GUI, so
     * the physical bag item is no longer visible anywhere to click again.
     * Usable storage is therefore SIZE - 1.
     */
    static final int CLOSE_BUTTON_SLOT = SIZE - 1;
    private static final Material MATERIAL = Material.SADDLE;
    private static final NamespacedKey ID_KEY = new NamespacedKey("bags", "id");
    private static final NamespacedKey CONTENTS_KEY = new NamespacedKey("bags", "contents");

    private BagItem() {
    }

    static ItemStack closeButton() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§cClose");
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack create() {
        ItemStack item = new ItemStack(MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6Bag");
        meta.setLore(java.util.List.of("§7Shift+right-click in your inventory to open", "§7(" + CLOSE_BUTTON_SLOT + " slots)"));
        meta.getPersistentDataContainer().set(ID_KEY, PersistentDataType.STRING, UUID.randomUUID().toString());
        meta.getPersistentDataContainer().set(CONTENTS_KEY, PersistentDataType.STRING, serialize(new ItemStack[SIZE]));
        item.setItemMeta(meta);
        return item;
    }

    static boolean isBag(ItemStack item) {
        if (item == null || item.getType() != MATERIAL || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(ID_KEY, PersistentDataType.STRING);
    }

    static String idOf(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().get(ID_KEY, PersistentDataType.STRING);
    }

    static ItemStack[] loadContents(ItemStack item) {
        String data = item.getItemMeta().getPersistentDataContainer().get(CONTENTS_KEY, PersistentDataType.STRING);
        return data != null ? deserialize(data) : new ItemStack[SIZE];
    }

    static void saveContents(ItemStack item, ItemStack[] contents) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(CONTENTS_KEY, PersistentDataType.STRING, serialize(contents));
        item.setItemMeta(meta);
    }

    private static String serialize(ItemStack[] contents) {
        try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOut = new BukkitObjectOutputStream(byteOut)) {
            dataOut.writeInt(contents.length);
            for (ItemStack item : contents) {
                dataOut.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(byteOut.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize bag contents", e);
        }
    }

    private static ItemStack[] deserialize(String data) {
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataIn = new BukkitObjectInputStream(byteIn)) {
            int length = dataIn.readInt();
            ItemStack[] contents = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                contents[i] = (ItemStack) dataIn.readObject();
            }
            return contents;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize bag contents", e);
        }
    }
}
