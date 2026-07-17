package com.aibots.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Reliable Citizens skins: copy from online player, else Mojang texture value+signature.
 */
public final class SkinApplier {

    private SkinApplier() {
    }

    public static void apply(Object npc, String skinName, JavaPlugin plugin) {
        if (npc == null) {
            return;
        }
        String name = skinName == null || skinName.isBlank() ? "Steve" : skinName.trim();

        // 1) Best: copy from online player with matching name
        Player online = Bukkit.getPlayerExact(name);
        if (online == null) {
            // case-insensitive scan
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().equalsIgnoreCase(name)) {
                    online = p;
                    break;
                }
            }
        }
        if (online != null && applyFromPlayer(npc, online)) {
            plugin.getLogger().info("[AIBots] Skin copied from online player " + online.getName());
            return;
        }

        // 2) Async Mojang profile → setSkinPersistent(name, signature, value)
        final String fetchName = name;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Texture tex = fetchMojangTexture(fetchName, plugin);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (tex != null && applyPersistent(npc, fetchName, tex.signature, tex.value)) {
                    plugin.getLogger().info("[AIBots] Skin applied from Mojang for " + fetchName);
                } else if (!applySkinName(npc, fetchName)) {
                    plugin.getLogger().warning("[AIBots] All skin methods failed for " + fetchName);
                } else {
                    plugin.getLogger().info("[AIBots] Skin name fallback applied for " + fetchName);
                }
            });
        });
    }

    private static boolean applyFromPlayer(Object npc, Player player) {
        try {
            Object trait = skinTrait(npc);
            // setSkinPersistent(Player)
            try {
                Method m = trait.getClass().getMethod("setSkinPersistent", Player.class);
                m.invoke(trait, player);
                return true;
            } catch (NoSuchMethodException ignored) {
            }
            // setSkinName(playerName, true)
            return applySkinName(npc, player.getName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean applyPersistent(Object npc, String name, String signature, String value) {
        try {
            Object trait = skinTrait(npc);
            tryInvoke(trait, "setFetchDefaultSkin", new Class[]{boolean.class}, true);
            tryInvoke(trait, "setShouldUpdateSkins", new Class[]{boolean.class}, true);
            Method m = trait.getClass().getMethod("setSkinPersistent", String.class, String.class, String.class);
            // Citizens signature: setSkinPersistent(String name, String signature, String data)
            m.invoke(trait, name, signature, value);
            return true;
        } catch (Throwable t) {
            try {
                Object trait = skinTrait(npc);
                Method m = trait.getClass().getMethod("applyTextureInternal", String.class, String.class);
                m.invoke(trait, value, signature);
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    private static boolean applySkinName(Object npc, String name) {
        try {
            Object trait = skinTrait(npc);
            tryInvoke(trait, "setFetchDefaultSkin", new Class[]{boolean.class}, true);
            tryInvoke(trait, "setShouldUpdateSkins", new Class[]{boolean.class}, true);
            try {
                Method m = trait.getClass().getMethod("setSkinName", String.class, boolean.class);
                m.invoke(trait, name, true);
                return true;
            } catch (NoSuchMethodException e) {
                Method m = trait.getClass().getMethod("setSkinName", String.class);
                m.invoke(trait, name);
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object skinTrait(Object npc) throws Exception {
        Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
        Method getOrAddTrait = npc.getClass().getMethod("getOrAddTrait", Class.class);
        return getOrAddTrait.invoke(npc, skinTraitClass);
    }

    private static void tryInvoke(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getMethod(method, types);
            m.invoke(target, args);
        } catch (Throwable ignored) {
        }
    }

    private static Texture fetchMojangTexture(String username, JavaPlugin plugin) {
        try {
            // Profile UUID
            String profileJson = httpGet("https://api.mojang.com/users/profiles/minecraft/" + username);
            if (profileJson == null || profileJson.isBlank() || profileJson.contains("errorMessage")) {
                // try Ashcon / Mineman proxy as fallback for some names
                return fetchAshcon(username, plugin);
            }
            JsonObject profile = JsonParser.parseString(profileJson).getAsJsonObject();
            String id = profile.get("id").getAsString();
            String sessionJson = httpGet(
                    "https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
            if (sessionJson == null || sessionJson.isBlank()) {
                return fetchAshcon(username, plugin);
            }
            JsonObject session = JsonParser.parseString(sessionJson).getAsJsonObject();
            JsonArray props = session.getAsJsonArray("properties");
            if (props == null || props.isEmpty()) {
                return null;
            }
            JsonObject tex = props.get(0).getAsJsonObject();
            String value = tex.get("value").getAsString();
            String sig = tex.has("signature") ? tex.get("signature").getAsString() : "";
            return new Texture(value, sig);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[AIBots] Mojang skin fetch failed for " + username + ": " + e.getMessage());
            return fetchAshcon(username, plugin);
        }
    }

    /** Public API used by some skin tools when Mojang rate-limits */
    private static Texture fetchAshcon(String username, JavaPlugin plugin) {
        try {
            String json = httpGet("https://api.ashcon.app/mojang/v2/user/" + username);
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("textures") || !obj.getAsJsonObject("textures").has("raw")) {
                return null;
            }
            JsonObject raw = obj.getAsJsonObject("textures").getAsJsonObject("raw");
            String value = raw.get("value").getAsString();
            String sig = raw.has("signature") ? raw.get("signature").getAsString() : "";
            plugin.getLogger().info("[AIBots] Skin fetched via Ashcon for " + username);
            return new Texture(value, sig);
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "Ashcon fetch failed: " + e.getMessage());
            return null;
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "AIBots-Minecraft/1.2");
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) {
            return null;
        }
        try (Scanner sc = new Scanner(stream, StandardCharsets.UTF_8)) {
            sc.useDelimiter("\\A");
            String body = sc.hasNext() ? sc.next() : "";
            if (code < 200 || code >= 300) {
                return null;
            }
            return body;
        }
    }

    private record Texture(String value, String signature) {
    }
}
