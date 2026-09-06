package com.bags;

import org.bukkit.plugin.java.JavaPlugin;

public class BagsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new BagListener(this), this);
        getCommand("bag").setExecutor(new BagCommand());
        getCommand("givebag").setExecutor(new GiveBagCommand());
        getLogger().info("Bags enabled — right-click a bag in your inventory to open it, or /bag as a backup.");
    }
}
