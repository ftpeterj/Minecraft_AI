package com.joingate;

import org.bukkit.plugin.java.JavaPlugin;

public class JoinGatePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new JoinAttemptListener(this), this);

        ApproveCommand approveCommand = new ApproveCommand(this);
        getCommand("approve").setExecutor(approveCommand);
        getCommand("approve").setTabCompleter(approveCommand);

        getLogger().info("JoinGate enabled — blocked (non-whitelisted) join attempts will be logged"
                + " and broadcast to online ops. Use /approve <name> to let someone in.");
    }
}
