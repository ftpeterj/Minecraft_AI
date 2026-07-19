# Minecraft_AI Setup Guide

## 1. Server Setup (Offline Mode)
1. Edit `server.properties`:
   ```
   online-mode=false
   ```
2. Restart the Paper server. Any username can be used for bots/NPCs.

## 2. Plugin Development & Installation
- Navigate to `AIBotsPlugin/` and run `mvn clean package`.
- Copy the generated JAR from `target/` to your server's `plugins/` folder.
- Restart server and configure `plugins/AIBotsPlugin/config.yml` (LLM endpoints, API keys, NPC settings).

## 3. LLM Setup
- Run LM Studio locally with OpenAI-compatible API enabled.
- For cloud models, add API keys to config.

## 4. Testing
- Use in-game commands to spawn/test NPCs.
- Monitor logs for LLM interactions and bot communications.

Keep this file updated as we add features. For full development flow, see ROADMAP.md.