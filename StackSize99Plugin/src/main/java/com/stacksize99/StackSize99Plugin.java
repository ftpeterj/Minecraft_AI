package com.stacksize99;

import org.bukkit.plugin.java.JavaPlugin;

public class StackSize99Plugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new StackSizeListener(), this);
        getLogger().info("StackSize99 enabled — items normally capped at 64 now stack to " + StackSizeUtil.TARGET);
    }
}
