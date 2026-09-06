package com.botinterop;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class BotOverheadListener implements Listener {

    private final BotInteropPlugin plugin;
    private final BotOverheadDisplay overhead;

    public BotOverheadListener(BotInteropPlugin plugin, BotOverheadDisplay overhead) {
        this.plugin = plugin;
        this.overhead = overhead;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.isBotAccount(event.getPlayer().getName())) {
            overhead.start(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.isBotAccount(event.getPlayer().getName())) {
            overhead.stop(event.getPlayer());
        }
    }
}
