# Minecraft_AI Setup Guide

Personal offline Paper server + AIBots multi-role crew. No extra Minecraft accounts required.

Agent handoff (Claude / Grok): see **`CLAUDE.md`**. Local RCON/SSH: **`ops.local.yml`** (gitignored; start from `ops.local.yml.example`).

## 1. Server setup (offline mode)

1. Edit `server.properties`:
   ```
   online-mode=false
   ```
2. Restart Paper. Any username works for bots/NPCs.
3. Recommended: Paper 1.21.x / 26.x. BlueMap works on 26.x; Dynmap often does not.

## 2. Build & install AIBots

```bash
cd AIBotsPlugin
mvn clean package
```

Copy `AIBotsPlugin/target/AIBots-*-SNAPSHOT.jar` into the server `plugins/` folder.  
Restart (or load) the server.

Plugin data folder (created on first run):

```
plugins/AIBots/
  config.yml      # edit LLM, titles, storage
  bots.yml         # wiped when crew.clear-on-load=true
  learning.yml
  storage.yml
```

> Config path is **`plugins/AIBots/`** (plugin name), not `AIBotsPlugin/`.

## 3. LLM setup

### Local (primary) — Ollama
1. Install [Ollama](https://ollama.com/) on the box with the GPU (currently `dadsbox`).
2. Pull a chat model. Current crew model:
   ```
   ollama pull qwen2.5:14b
   ```
   `llama3.2` is a lighter spare.
3. If the Minecraft server is on another machine, Ollama must listen on the LAN (it binds localhost by default):
   - Windows: set user env var `OLLAMA_HOST=0.0.0.0:11434`, then restart Ollama.
   - Linux: `systemctl edit ollama` → `Environment=OLLAMA_HOST=0.0.0.0:11434`, then restart.
4. Set in `plugins/AIBots/config.yml`:
   ```yaml
   ollama:
     base-url: "http://YOUR-HOST:11434"
     model: ""   # empty = first pulled model
   llm:
     primary: ollama
     fallback-to: lm-studio
   ```
5. Restart the plugin (or `/crew reload`). Check with `/crew llm`.

LM Studio model names (GGUF ids) do **not** work in Ollama. Use an Ollama tag (`llama3.2`, `qwen2.5:14b`, …).

### Fallback — LM Studio
If Ollama is down, chat falls through to LM Studio when `fallback-to: lm-studio` is set:
```yaml
lm-studio:
  base-url: "http://YOUR-HOST:1234/v1"
  model: ""   # empty = first available model
```
To go back to LM Studio as primary: set `llm.primary: lm-studio` and `/crew reload`.

### Optional cloud
Add `api-key` under `llm.providers.openai` or `llm.providers.grok`. Leave blank to ignore.

## 4. In-game quick start

```
/crew summon Rusty gatherer
/crew home Rusty
/crew assign Rusty gather wood
/crew summon Bob defender
/crew assign Bob wall 5 cobble
/crew msg Bob Rusty gather cobblestone
/crew jobs list
/crew jobs post gatherer gather iron
/crew storage list
/crew has oak_log
```

### Titles
`gatherer | defender`

### Useful commands
| Command | Purpose |
|--------|---------|
| `/crew summon <name> <title>` | Spawn crew bot |
| `/crew assign <name> <order…>` | Direct order |
| `/crew jobs list` | Open + claimed jobs |
| `/crew jobs post [title] <order…>` | Queue work for matching idle bot |
| `/crew jobs cancel <id>` | Cancel a job |
| `/crew msg <from> <to> <text>` | Inter-bot message / delegate |
| `/crew stop <name>` | Halt bot |
| `/crew storage list \| has <item>` | Chest network |
| `/crew storage register …` | Link room of chests (see below) |
| `/crew deposit <name>` | Force bag → network |
| `/crew inv <name>` | List bot loot bag |
| `/crew teach <name> [share] <fact>` | Learning |
| `/crew say <name> <msg>` | LLM chat (does **not** deposit items) |

### Register a room of chests (like `/fill`)

Full guide: **`docs/STORAGE.md`**.

```
/crew storage pos1          # look at one corner
/crew storage pos2          # opposite corner — DIFFERENT HEIGHT
/crew storage register
/crew storage list
```

Or:

```
/crew storage register <x1> <y1> <z1> <x2> <y2> <z2>
```

**Pesky detail (remember):** both corners must enclose the room’s **height**.  
If pos1 and pos2 are on the same floor Y, the box is a flat slab and wall chests are missed.  
**What worked in play:** floor corner for pos1, **ceiling / top of chest wall** for pos2, then register.

### Defender build orders
- `wall 5` / `wall 5x2 cobble`
- `platform 3x3 oak`
- `pillar 4`
- `box 4x3x3 stone_bricks`

## 5. Testing checklist

- [ ] Ollama health in server log on enable (`/crew llm`)  
- [ ] Summon gatherer → home → chest appears nearby  
- [ ] Assign gather → bot walks / deposits  
- [ ] Register storage room with pos1/pos2 at **different Y** → list shows all chests  
- [ ] `/crew deposit <bot>` moves bag items into network  
- [ ] `/crew jobs post gatherer gather oak` → idle gatherer claims  
- [ ] Defender short on blocks → material request / job  
- [ ] Right-click villager → loot bag (not trades)

## 6. Monorepo layout

```
Minecraft_AI/
  AIBotsPlugin/     # Paper plugin (this guide)
  LLM-craft/        # optional related LLM NPC plugin
  MinecraftBots/    # optional client-side experiments
  docs/ROADMAP.md
  docs/STORAGE.md   # chest network + pos1/pos2 height gotcha
  SETUP.md
```

Keep this file updated as features land. Full roadmap: `docs/ROADMAP.md`.  
Storage room register details: `docs/STORAGE.md`.
