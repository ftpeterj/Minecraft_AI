# Claude / Grok handoff — Minecraft_AI

Local clone: `C:\Projects`  
Remote: https://github.com/ftpeterj/Minecraft_AI (`main`)  
This file is auto-loaded by Claude Code. Grok should read it too.

You and Grok take turns on this repo so tokens last. **Pull before you start. Push when you finish.** Do not leave the only copy of a change on one machine.

## What this is

A cooperative Minecraft session on a **personal Paper server**. The human is the leader. AI crew look and act like other players, take useful work, and always defer.

**Primary direction as of 2026-09-02: mineflayer AI bot.** The bots are moving to real, purchased Java Edition player accounts driven by Node.js/mineflayer (`MinecraftBots/mineflayer-26.2-probe/`), not Citizens NPCs. The owner considers most of the prior Citizens/AIBotsPlugin villager-avatar work a failure — see "Mineflayer bot (current direction)" below. `AIBotsPlugin/` (Paper, Java 21, Maven, live version **1.6.5**) still runs live on Creative but is legacy; default new bot-presence work to the mineflayer path unless told otherwise.

## Do not

- Do not commit `pihole/`, `cryptobot/`, or anything in `ops.local.yml`
- Do not put RCON passwords, SSH private keys, or API keys on GitHub
- Do not run a world-wide tree heal (`/crew healtrees` without a small radius) — it generates chunks and tanks TPS. Nearby only, e.g. `/crew healtrees 256`
- Do not treat bots as replacements for the player
- Do not assume `/crew reload` swapped the jar — it only reloads YAML. New jars need a server restart
- Do not use `git@github.com` from an unattended agent — the GitHub SSH key is **passphrase-protected** and there is no ssh-agent. Use **HTTPS + `gh`**

## Connectivity (this PC = DadsBox)

| What | How |
|------|-----|
| GitHub git | `https://github.com/ftpeterj/Minecraft_AI.git` via `gh` keyring (`ftpeterj`, scopes: `repo`, `gist`, `read:org`, `admin:public_key`). `gh auth git-credential` is already the helper. |
| GitHub CLI | `gh` — logged in. No `GITHUB_TOKEN` env needed. |
| Claude Code | Logged in (OAuth). No `ANTHROPIC_API_KEY` env needed. |
| Minecraft SSH | `ssh minecraft` → `xxadmin@minecraft.local` with `~/.ssh/claude_tool` (unencrypted). Passwordless sudo. |
| RCON | On the Minecraft box: `mcrcon -H 127.0.0.1 -P 25577 -p <password> '<cmd>'`. Password is in **`ops.local.yml`** (gitignored). Copy `ops.local.yml.example` if that file is missing. |
| Ollama | Native Windows on DadsBox. `http://127.0.0.1:11434` and `http://dadsbox.local:11434`. Models: `qwen2.5:14b` (primary), `llama3.2`. GPU: AMD RX 6700 XT (`OLLAMA_HOST=0.0.0.0:11434`, `OLLAMA_VULKAN=1`, `GGML_VK_VISIBLE_DEVICES=0`). Binary: `%LOCALAPPDATA%\Programs\Ollama\ollama.exe` |
| LM Studio | Fallback only, port **1234**. Quit it when the 6700 XT is busy with Qwen 14B + Minecraft shaders. |
| Build | Maven 3.9 + JDK 21 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` (JDK 25 is also installed; prefer 21 for this plugin). |

LAN SSH inventory for other boxes: `~/.claude/skills/remote-troubleshoot/hosts.yml` (lucas, pj-pc, jens-laptop, guest-laptop, minecraft).

## Live Creative server

- Host: `minecraft.local` (Ubuntu, Multicraft, daemon user `mc4`)
- World: `/home/minecraft/multicraft/servers/Creative/`
- Plugins: `.../Creative/plugins/`
- AIBots data: `.../Creative/plugins/AIBots/` (`config.yml`, `bots.yml`, `learning.yml`, `storage.yml`)
- Paper: `paper-26.2-121.jar` (Minecraft 26.2, latest stable as of 2026-09-02). Also Citizens `2.0.43-b4239`. Previous: `paper-26.1.2-69.jar`. World backup at `/home/minecraft/backups/Creative-world-pre-26.2-20260902-0104.tgz`.
- Game port 25565, RCON 25577, `enable-rcon=true`
- `online-mode` is currently **true** on disk (SETUP.md still describes offline-mode). Check before changing it.
- Plugin data files are `xxadmin:mc4`. If `config.yml` is not group-readable, mc4 cannot load LLM URLs.

### Deploy a new jar

```text
cd C:\Projects\AIBotsPlugin
mvn -q -DskipTests package
scp target\AIBots-*-SNAPSHOT.jar minecraft:/home/minecraft/multicraft/servers/Creative/plugins/
```

Then **restart** Creative (Multicraft or RCON `stop` if the daemon respawns). Copying the jar is not enough while the classloader holds the old one.

After editing live `config.yml`: `chown xxadmin:mc4` and `chmod 770` if needed, then `/crew reload` (or restart).

### RCON from DadsBox

```text
ssh minecraft "mcrcon -H 127.0.0.1 -P 25577 -p \"PASSWORD\" \"list\""
```

Read `PASSWORD` from `C:\Projects\ops.local.yml`. Never echo it into git, README, or commit messages.

## Repo layout (what belongs here)

```text
AIBotsPlugin/       Paper plugin (Citizens/villager crew) — legacy, still live on Creative
BotInteropPlugin/   Paper plugin — right-click a mineflayer bot account for its live inventory, /botstatus
MinecraftBots/       mineflayer-26.2-probe/ (the mineflayer bot), plus misc ops scripts
docs/               ROADMAP.md, STORAGE.md
SETUP.md            Human setup
CLAUDE.md           This file
ops.local.yml.example
```

`LLM-craft/` is an optional related tree. **`pihole/` and `cryptobot/` are local-only — never add them.**

## Product constraints

- Teammates, not replacements. Human stays in charge.
- Player-like crew: Citizens **PLAYER** NPCs (`crew.avatar-mode: player`), walkTo, tab list, tools, chat.
- Deposit an armful (`deposit-threshold: 64`) at **home** / storage, then go back out.
- Do not mine inside `storage-keepout` (8 blocks) of home or registered chests.
- Unstick from water / inside blocks; do not path into fluids.
- Default skin: owner (`KingOfThisHouse` in current sessions).
- LLM: Ollama `qwen2.5:14b` primary, LM Studio fallback. Cloud keys in config are empty on purpose.

## Known live drift (check first)

Repo `AIBotsPlugin/src/main/resources/config.yml` is the intended 1.6.5 defaults (`avatar-mode: player`, `deposit-threshold: 64`, `storage-keepout: 8`).

Live `plugins/AIBots/config.yml` has been seen still on **villager** + **deposit-threshold: 0** even with the 1.6.5 jar loaded. Plugin YAML is not overwritten from the jar if the file already exists. If bots still look like villagers or never deposit, fix live config (or copy the intended keys) and `/crew reload`.

## Commands worth knowing

`/crew summon <name> <title>` · `/crew home <name>` · `/crew assign` · `/crew jobs` · `/crew deposit` · `/crew inv` · `/crew llm` · `/crew reload` · `/crew healtrees <radius>` · `/crew storage register`

Titles: gatherer (mining, woodcutting, scavenging, farming, fishing), defender (building, hunting, guard/patrol). Consolidated from the old 8-title roster (scavenger/miner/woodsman/hunter/farmer/warrior/protector/builder) on 2026-08-30 — too many single-purpose roles to maintain well. Old title names no longer parse.

## Status snapshot (2026-08-30)

Shipped in 1.6.5: Ollama primary, deposit-to-home, storage keepout, unstick, nearby tree heal, co-op prompts, idle liveliness (wander/look/emote when idle), per-bot skin pool, villager-body collision fix, gatherer/defender title merge + new fishing skill.

**Avatar mode is back on `villager`** (not `player`/Citizens) — Citizens `PLAYER`-type NPCs render with corrupted/warped body geometry on this server's Minecraft version even after upgrading Citizens and fixing a teleport-vs-rotation bug in our own code. **Update 2026-09-02: the owner traced this to the client-side Iris shader pack**, not an upstream Citizens/MC-version bug as previously assumed — worth retesting Citizens `PLAYER` mode without Iris before ruling it out again. Regardless, the project has pivoted to the mineflayer path below rather than continuing to chase Citizens.

**Mineflayer bot (current direction).** Mineflayer doesn't officially support this server's Minecraft version (26.2, protocol 776 — PrismarineJS/mineflayer only documents through 26.1) but an unofficial patch works:

- `MinecraftBots/mineflayer-26.2-probe/` — mineflayer `4.38.0`, patched via `patch-mcdata-26.2.js` (runs as npm `postinstall`): clones `minecraft-data` 26.1→26.2 and aliases `prismarine-chunk`/`prismarine-physics` 26.2→1.18. Confirmed working end-to-end (login/spawn/chat/block-read) against both Creative and Survival.
- `persistent-bot.js` in that folder — connects and stays connected indefinitely with exponential-backoff auto-reconnect. No AI behavior yet, intentionally minimal.
- Bot account: **`bloodypuddlekos`**, a real purchased Java Edition Microsoft account, whitelisted on Survival. Never use the owner's own account for a bot — logging a bot in on an account that's already online kicks that live session ("logged in from another location").
- `C:\Projects\BotInteropPlugin\` — standalone Paper plugin (pattern-matches `JoinGatePlugin/`, independent of `AIBotsPlugin/`). Since the bot is a real online player, right-clicking a configured bot account opens its **live** `PlayerInventory` for the clicking player to edit directly (the `/invsee` trick) — real equip/give/take, no sync layer. Also adds `/botstatus <name>` for health/hunger/location on demand. Config: `BotInteropPlugin/src/main/resources/config.yml` → `bot-accounts`. Deployed to Survival 2026-09-02.
- Survival specifics: RCON port **25575** (Creative is 25577, same password in `ops.local.yml`). Survival's Paper process runs as OS user `mc5` (Creative is `mc4`) — `xxadmin` can't write directly to `survival/plugins/`; deploy via `scp` to `/tmp` then `sudo mv` + `sudo chown mc5:mc5`.
- Auth gotcha: prismarine-auth caches Microsoft tokens at `%APPDATA%\.minecraft\nmp-cache\<hash>_*-cache.json`, hashed from the exact (case-sensitive) username string. A failed auth attempt still writes a cache file that gets silently reused on retry, producing a different and more confusing error later (`Profile not found, please restart your launcher...`) even after the real problem is fixed. Clear the relevant cache files and force a fresh device-code sign-in after any auth failure rather than just retrying.

Not done: idle initiative that still defers to the player (Slice 3, though idle liveliness now covers the cosmetic half), world event bus, builder shopping-list → job board, hybrid local-then-cloud LLM.

Choppiness: Kappa shaders at render/sim/shadow 32 + Qwen 14B on the same 12GB 6700 XT. Lower shadow/render or quit LM Studio.

## Secrets policy

| Store | GitHub |
|-------|--------|
| `ops.local.yml` (RCON) | no |
| `~/.ssh/claude_tool` | no |
| `~/.ssh/id_ed25519` (passphrase-protected; GitHub HTTPS preferred) | no |
| `plugins/AIBots/config.yml` LLM URLs | already in repo (`dadsbox.local`) — no API keys filled in |
| `gh` keyring | local only |
