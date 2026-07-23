package com.aibots;

import com.aibots.command.CrewCommand;
import com.aibots.crew.CrewManager;
import com.aibots.item.StackSizeService;
import com.aibots.listener.ChatListener;
import com.aibots.listener.CrewInteractListener;
import com.aibots.listener.OrphanBodyListener;
import com.aibots.listener.StackSizeListener;
import com.aibots.llm.LLMRouter;
import com.aibots.llm.LMStudioClient;
import com.aibots.npc.NpcService;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public class AIBotsPlugin extends JavaPlugin {

    private CrewManager crewManager;
    private LLMRouter llmRouter;
    private NpcService npcService;
    private StackSizeService stackSizeService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        llmRouter = new LLMRouter(getConfig(), getLogger());
        LMStudioClient legacy = llmRouter.asLegacyClient();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            boolean ok = llmRouter.healthCheck();
            getLogger().info(ok
                    ? "LLM primary '" + llmRouter.primaryId() + "' reachable"
                    + " (model=" + legacy.getModel() + ", providers=" + llmRouter.providers().keySet() + ")"
                    : "LLM primary NOT reachable — chat will fail until fixed.");
        });

        stackSizeService = new StackSizeService(this);

        npcService = new NpcService(this);
        crewManager = new CrewManager(this, npcService, llmRouter);
        crewManager.start();

        CrewCommand crewCommand = new CrewCommand(this, crewManager);
        registerCommand("crew", crewCommand, crewCommand);
        registerCommand("aibot", crewCommand, crewCommand);

        getServer().getPluginManager().registerEvents(new ChatListener(this, crewManager), this);
        getServer().getPluginManager().registerEvents(new CrewInteractListener(crewManager, npcService), this);
        getServer().getPluginManager().registerEvents(new OrphanBodyListener(this, crewManager), this);
        getServer().getPluginManager().registerEvents(new StackSizeListener(this, stackSizeService), this);

        getLogger().info("AIBots crew enabled. default-max-stack=" + stackSizeService.configuredMax()
                + ". Multi-LLM router + inter-bot messaging + builder. /crew help");
    }

    private void registerCommand(String name, CommandExecutor executor, TabCompleter tab) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("Command '" + name + "' missing from plugin.yml");
            return;
        }
        cmd.setExecutor(executor);
        if (tab != null) {
            cmd.setTabCompleter(tab);
        }
    }

    @Override
    public void onDisable() {
        if (crewManager != null) {
            crewManager.shutdown();
        }
        if (llmRouter != null) {
            llmRouter.close();
        }
        getLogger().info("AIBots disabled — crew saved.");
    }

    public CrewManager getCrewManager() {
        return crewManager;
    }
}
