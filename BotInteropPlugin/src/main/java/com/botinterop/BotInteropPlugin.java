package com.botinterop;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class BotInteropPlugin extends JavaPlugin {

    private final Set<String> botAccounts = new HashSet<>();
    private BotOverheadDisplay overhead;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBotAccounts();

        overhead = new BotOverheadDisplay(this);
        overhead.cleanupOrphans();

        getServer().getPluginManager().registerEvents(new BotInventoryListener(this), this);
        getServer().getPluginManager().registerEvents(new BotOverheadListener(this, overhead), this);
        getCommand("botstatus").setExecutor(new BotStatusCommand(this));
        getCommand("botinv").setExecutor(new BotInvCommand(this));
        getCommand("botarmor").setExecutor(new BotArmorCommand(this));

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isBotAccount(player.getName())) {
                overhead.start(player);
            }
        }

        getLogger().info("BotInterop enabled — bot accounts: " + botAccounts);
    }

    @Override
    public void onDisable() {
        if (overhead != null) {
            overhead.stopAll();
        }
    }

    void loadBotAccounts() {
        botAccounts.clear();
        List<String> configured = getConfig().getStringList("bot-accounts");
        for (String name : configured) {
            botAccounts.add(name.toLowerCase());
        }
    }

    public boolean isBotAccount(String playerName) {
        return botAccounts.contains(playerName.toLowerCase());
    }
}
