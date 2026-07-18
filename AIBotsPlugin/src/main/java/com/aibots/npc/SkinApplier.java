package com.aibots.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
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
 * Apply player skins to Citizens NPCs.
 * On Paper 26 + remapped Citizens, SkinTrait alone often yields a solid-black body;
 * we also push textures through Paper's PlayerProfile onto the living entity.
 */
public final class SkinApplier {

    private SkinApplier() {
    }

    public static void apply(Object npc, String skinName, JavaPlugin plugin) {
        if (npc == null || plugin == null) {
            return;
        }
        String name = skinName == null || skinName.isBlank() ? "Steve" : skinName.trim();

        Player online = findOnline(name);
        if (online != null) {
            boolean ok = applyFromOnlinePlayer(npc, online, plugin);
            if (ok) {
                plugin.getLogger().info("[AIBots] Skin applied from online player " + online.getName()
                        + " (Citizens trait + Paper profile)");
                // Re-apply a moment later — entity may not be ready on first tick
                Bukkit.getScheduler().runTaskLater(plugin, () -> applyFromOnlinePlayer(npc, online, plugin), 10L);
                Bukkit.getScheduler().runTaskLater(plugin, () -> applyFromOnlinePlayer(npc, online, plugin), 40L);
                return;
            }
        }

        final String fetchName = name;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Texture tex = fetchMojangTexture(fetchName, plugin);
            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean ok = false;
                if (tex != null) {
                    ok = applyPersistent(npc, fetchName, tex.signature, tex.value);
                    ok = applyPaperProfileFromTexture(npc, fetchName, tex, plugin) || ok;
                }
                if (!ok) {
                    applySkinName(npc, fetchName);
                    plugin.getLogger().warning("[AIBots] Skin fallback name-only for " + fetchName
                            + " — may appear black on Paper 26");
                } else {
                    plugin.getLogger().info("[AIBots] Skin applied from Mojang textures for " + fetchName);
                }
                if (tex != null) {
                    final Texture t = tex;
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> applyPaperProfileFromTexture(npc, fetchName, t, plugin), 15L);
                }
            });
        });
    }

    private static Player findOnline(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            return p;
        }
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getName().equalsIgnoreCase(name)) {
                return o;
            }
        }
        return null;
    }

    private static boolean applyFromOnlinePlayer(Object npc, Player player, JavaPlugin plugin) {
        boolean any = false;
        any |= applyCitizensFromPlayer(npc, player);
        any |= applyPaperProfileFromPlayer(npc, player, plugin);
        return any;
    }

    private static boolean applyCitizensFromPlayer(Object npc, Player player) {
        try {
            Object trait = skinTrait(npc);
            tryInvoke(trait, "setFetchDefaultSkin", new Class[]{boolean.class}, true);
            tryInvoke(trait, "setShouldUpdateSkins", new Class[]{boolean.class}, true);
            try {
                Method m = trait.getClass().getMethod("setSkinPersistent", Player.class);
                m.invoke(trait, player);
                return true;
            } catch (NoSuchMethodException e) {
                Method m = trait.getClass().getMethod("setSkinName", String.class, boolean.class);
                m.invoke(trait, player.getName(), true);
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Push the online player's profile onto the NPC's Player entity (Paper).
     * This is what actually makes the client render the skin on Paper 26.
     */
    private static boolean applyPaperProfileFromPlayer(Object npc, Player source, JavaPlugin plugin) {
        try {
            Entity entity = entityOf(npc);
            if (!(entity instanceof Player npcPlayer)) {
                plugin.getLogger().info("[AIBots] NPC entity is not Player (" +
                        (entity == null ? "null" : entity.getType()) + ") — cannot set PlayerProfile");
                return false;
            }

            // Preferred: copy profile object from source player
            try {
                Method getProf = source.getClass().getMethod("getPlayerProfile");
                Object srcProfile = getProf.invoke(source);
                if (srcProfile != null) {
                    // clone if possible
                    Object toSet = srcProfile;
                    try {
                        Method clone = srcProfile.getClass().getMethod("clone");
                        toSet = clone.invoke(srcProfile);
                    } catch (NoSuchMethodException ignored) {
                    }
                    Method setProf = findSetPlayerProfile(npcPlayer);
                    if (setProf != null) {
                        setProf.invoke(npcPlayer, toSet);
                        plugin.getLogger().info("[AIBots] Paper setPlayerProfile copied from " + source.getName());
                        return true;
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "getPlayerProfile copy failed: " + t.getMessage());
            }

            // Paper/Bukkit profile via reflection only (API types differ across Paper versions)
            try {
                Method getProf = Player.class.getMethod("getPlayerProfile");
                Object srcProfile = getProf.invoke(source);
                Method setProf = findSetPlayerProfile(npcPlayer);
                if (srcProfile != null && setProf != null) {
                    // Prefer cloning textures onto a new profile with same UUID/name
                    try {
                        Object dest = Bukkit.class.getMethod("createProfile", UUID.class, String.class)
                                .invoke(null, source.getUniqueId(), source.getName());
                        // copy properties if Paper profile
                        try {
                            Method getProps = srcProfile.getClass().getMethod("getProperties");
                            Object props = getProps.invoke(srcProfile);
                            if (props instanceof Iterable<?> iterable) {
                                Method setProperty = null;
                                for (Method m : dest.getClass().getMethods()) {
                                    if (m.getName().equals("setProperty") && m.getParameterCount() == 1) {
                                        setProperty = m;
                                        break;
                                    }
                                }
                                if (setProperty != null) {
                                    for (Object prop : iterable) {
                                        String propName = String.valueOf(prop.getClass().getMethod("getName").invoke(prop));
                                        if ("textures".equals(propName)) {
                                            setProperty.invoke(dest, prop);
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                            dest = srcProfile;
                        }
                        setProf.invoke(npcPlayer, dest);
                    } catch (Throwable t) {
                        setProf.invoke(npcPlayer, srcProfile);
                    }
                    plugin.getLogger().info("[AIBots] Paper profile applied on NPC entity from " + source.getName());
                    return true;
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "[AIBots] Paper profile apply failed: " + t.getMessage());
            }
            return false;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[AIBots] Paper profile apply error: " + t.getMessage());
            return false;
        }
    }

    private static boolean applyPaperProfileFromTexture(Object npc, String name, Texture tex, JavaPlugin plugin) {
        try {
            Entity entity = entityOf(npc);
            if (!(entity instanceof Player npcPlayer)) {
                return false;
            }

            UUID uuid = uuidFromName(name);
            String shortName = name.length() > 16 ? name.substring(0, 16) : name;
            Method setProf = findSetPlayerProfile(npcPlayer);
            if (setProf == null) {
                return false;
            }

            // Paper createProfile + ProfileProperty(textures)
            try {
                Object pp = Bukkit.class.getMethod("createProfile", UUID.class, String.class)
                        .invoke(null, uuid, shortName);
                Class<?> propCl;
                try {
                    propCl = Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
                } catch (ClassNotFoundException e) {
                    propCl = Class.forName("io.papermc.paper.profile.ProfileProperty");
                }
                Object prop = propCl.getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", tex.value, tex.signature == null ? "" : tex.signature);
                Method setProperty = null;
                for (Method m : pp.getClass().getMethods()) {
                    if (m.getName().equals("setProperty") && m.getParameterCount() == 1) {
                        setProperty = m;
                        break;
                    }
                }
                if (setProperty != null) {
                    setProperty.invoke(pp, prop);
                }
                setProf.invoke(npcPlayer, pp);
                return true;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "createProfile textures: " + t.getMessage());
            }
            return false;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "texture profile apply: " + t.getMessage());
            return false;
        }
    }

    private static Method findSetPlayerProfile(Player player) {
        for (Method m : player.getClass().getMethods()) {
            if (m.getName().equals("setPlayerProfile") && m.getParameterCount() == 1) {
                return m;
            }
        }
        return null;
    }

    private static Entity entityOf(Object npc) throws Exception {
        return (Entity) npc.getClass().getMethod("getEntity").invoke(npc);
    }

    private static UUID uuidFromName(String name) {
        // offline-style UUID — fine for NPC display
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean applyPersistent(Object npc, String name, String signature, String value) {
        try {
            Object trait = skinTrait(npc);
            tryInvoke(trait, "setFetchDefaultSkin", new Class[]{boolean.class}, true);
            tryInvoke(trait, "setShouldUpdateSkins", new Class[]{boolean.class}, true);
            try {
                Method m = trait.getClass().getMethod("setSkinPersistent", String.class, String.class, String.class);
                m.invoke(trait, name, signature == null ? "" : signature, value);
                return true;
            } catch (NoSuchMethodException e) {
                Method m = trait.getClass().getMethod("applyTextureInternal", String.class, String.class);
                m.invoke(trait, value, signature == null ? "" : signature);
                return true;
            }
        } catch (Throwable t) {
            return false;
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
            } catch (NoSuchMethodException e) {
                Method m = trait.getClass().getMethod("setSkinName", String.class);
                m.invoke(trait, name);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object skinTrait(Object npc) throws Exception {
        Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
        return npc.getClass().getMethod("getOrAddTrait", Class.class).invoke(npc, skinTraitClass);
    }

    private static void tryInvoke(Object target, String method, Class<?>[] types, Object... args) {
        try {
            target.getClass().getMethod(method, types).invoke(target, args);
        } catch (Throwable ignored) {
        }
    }

    /** Public for ArmorStand heads and other avatars. */
    public static Texture fetchTexturePublic(String username, JavaPlugin plugin) {
        return fetchMojangTexture(username, plugin);
    }

    public record Texture(String value, String signature) {
    }

    private static Texture fetchMojangTexture(String username, JavaPlugin plugin) {
        try {
            String profileJson = httpGet("https://api.mojang.com/users/profiles/minecraft/" + username);
            if (profileJson == null || profileJson.isBlank()) {
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
                return fetchAshcon(username, plugin);
            }
            JsonObject tex = props.get(0).getAsJsonObject();
            return new Texture(tex.get("value").getAsString(),
                    tex.has("signature") ? tex.get("signature").getAsString() : "");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[AIBots] Mojang fetch failed: " + e.getMessage());
            return fetchAshcon(username, plugin);
        }
    }

    private static Texture fetchAshcon(String username, JavaPlugin plugin) {
        try {
            String json = httpGet("https://api.ashcon.app/mojang/v2/user/" + username);
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonObject raw = obj.getAsJsonObject("textures").getAsJsonObject("raw");
            plugin.getLogger().info("[AIBots] Skin via Ashcon for " + username);
            return new Texture(raw.get("value").getAsString(),
                    raw.has("signature") ? raw.get("signature").getAsString() : "");
        } catch (Exception e) {
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
            return (code >= 200 && code < 300 && sc.hasNext()) ? sc.next() : null;
        }
    }

}
