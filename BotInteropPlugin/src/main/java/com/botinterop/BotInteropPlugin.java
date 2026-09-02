package com.botinterop;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.plugin.java.JavaPlugin;

public class BotInteropPlugin extends JavaPlugin {

    private final Set<String> botAccounts = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBotAccounts();

        getServer().getPluginManager().registerEvents(new BotInventoryListener(this), this);
        getCommand("botstatus").setExecutor(new BotStatusCommand(this));

        getLogger().info("BotInterop enabled — bot accounts: " + botAccounts);
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
