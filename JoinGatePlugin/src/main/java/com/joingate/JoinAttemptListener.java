package com.joingate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanilla already decides KICK_WHITELIST before plugins see this event (it runs the
 * whitelist check first, then dispatches). We only observe that decision here — never
 * override it — and turn it into a console warning plus an in-game ping to online ops,
 * with a short per-name cooldown so a repeatedly-retrying bot can't spam chat.
 */
public class JoinAttemptListener implements Listener {

    private static final long COOLDOWN_MS = 5 * 60 * 1000L;

    private final JoinGatePlugin plugin;
    private final Map<String, Long> lastAlerted = new ConcurrentHashMap<>();

    public JoinAttemptListener(JoinGatePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST) {
            return;
        }

        String name = event.getName();
        UUID uuid = event.getUniqueId();
        String ip = event.getAddress() != null ? event.getAddress().getHostAddress() : "unknown";

        long now = System.currentTimeMillis();
        Long last = lastAlerted.put(name.toLowerCase(), now);
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }

        plugin.getLogger().warning("Blocked join attempt (not whitelisted): " + name + " (" + uuid + ") from " + ip
                + " -- run '/approve " + name + "' to let them in.");

        Bukkit.getScheduler().runTask(plugin, () -> {
            String msg = "§e[JoinGate] §f" + name + " §7(" + ip + ") §ftried to join, not whitelisted. "
                    + "§aRun /approve " + name + " to let them in.";
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("joingate.notify")) {
                    p.sendMessage(msg);
                }
            }
        });
    }
}
