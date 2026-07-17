package com.aibots;

import com.aibots.command.CrewCommand;
import com.aibots.crew.CrewManager;
import com.aibots.listener.ChatListener;
import com.aibots.llm.LMStudioClient;
import com.aibots.npc.NpcService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class AIBotsPlugin extends JavaPlugin {

    private CrewManager crewManager;
    private LMStudioClient llm;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String baseUrl = getConfig().getString("lm-studio.base-url", "http://dadsbox.local:1234/v1");
        String model = getConfig().getString("lm-studio.model", "");
        int timeout = getConfig().getInt("lm-studio.timeout-seconds", 60);
        int maxTokens = getConfig().getInt("lm-studio.max-tokens", 400);
        double temperature = getConfig().getDouble("lm-studio.temperature", 0.7);

        llm = new LMStudioClient(baseUrl, model, timeout, maxTokens, temperature, getLogger());
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            boolean ok = llm.healthCheck();
            getLogger().info(ok
                    ? "LM Studio reachable at " + baseUrl + " (model=" + llm.getModel() + ")"
                    : "LM Studio NOT reachable at " + baseUrl + " — chat will fail until fixed.");
        });

        NpcService npcService = new NpcService(this);
        crewManager = new CrewManager(this, npcService, llm);
        crewManager.start();

        CrewCommand crewCommand = new CrewCommand(this, crewManager);
        registerCommand("crew", crewCommand);
        registerCommand("aibot", crewCommand);

        getServer().getPluginManager().registerEvents(new ChatListener(this, crewManager), this);

        getLogger().info("AIBots crew Phase 1 enabled. Use /crew help");
    }

    private void registerCommand(String name, CrewCommand executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("Command '" + name + "' missing from plugin.yml");
            return;
        }
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        if (crewManager != null) {
            crewManager.shutdown();
        }
        getLogger().info("AIBots disabled — crew saved.");
    }

    public CrewManager getCrewManager() {
        return crewManager;
    }
}
