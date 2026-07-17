package com.aibots.listener;

import com.aibots.crew.CrewBot;
import com.aibots.crew.CrewManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

/**
 * Route chat that mentions a bot name to that bot's LLM.
 */
public class ChatListener implements Listener {

    private final JavaPlugin plugin;
    private final CrewManager crew;

    public ChatListener(JavaPlugin plugin, CrewManager crew) {
        this.plugin = plugin;
        this.crew = crew;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getConfig().getBoolean("crew.chat-mention-enabled", true)) {
            return;
        }
        String msg = event.getMessage();
        Optional<CrewBot> match = crew.matchMention(msg);
        if (match.isEmpty()) {
            return;
        }
        CrewBot bot = match.get();
        // Don't cancel global chat — bots overhear mentions and reply
        crew.talk(bot, event.getPlayer().getName() + " says: " + msg, event.getPlayer());
    }
}
