# Claude / Grok handoff — Minecraft_AI

Local clone: `C:\Projects`  
Remote: https://github.com/ftpeterj/Minecraft_AI (`main`)  
This file is auto-loaded by Claude Code. Grok should read it too.

You and Grok take turns on this repo so tokens last. **Pull before you start. Push when you finish.** Do not leave the only copy of a change on one machine.

## What this is

A cooperative Minecraft session on a **personal Paper server**. The human is the leader. AI crew look and act like other players, take useful work, and always defer. No extra Minecraft accounts.

Plugin: `AIBotsPlugin/` (Paper, Java 21, Maven). Live version **1.6.5**.

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
- Paper: `paper-26.1.2-69.jar` (API 1.21). Also Citizens `2.0.42-b4187`.
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
AIBotsPlugin/     Paper plugin — this is the product
docs/             ROADMAP.md, STORAGE.md
SETUP.md          Human setup
CLAUDE.md         This file
ops.local.yml.example
```

`MinecraftBots/` and `LLM-craft/` are optional related trees. **`pihole/` and `cryptobot/` are local-only — never add them.**

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

Titles: scavenger, miner, woodsman, hunter, farmer, warrior, protector, builder.

## Status snapshot (2026-08-28)

Shipped in 1.6.5: Ollama primary, player avatars, deposit-to-home, storage keepout, unstick, nearby tree heal, co-op prompts.

Not done: idle initiative that still defers to the player (Slice 3), world event bus, builder shopping-list → job board, hybrid local-then-cloud LLM.

Choppiness: Kappa shaders at render/sim/shadow 32 + Qwen 14B on the same 12GB 6700 XT. Lower shadow/render or quit LM Studio.

## Secrets policy

| Store | GitHub |
|-------|--------|
| `ops.local.yml` (RCON) | no |
| `~/.ssh/claude_tool` | no |
| `~/.ssh/id_ed25519` (passphrase-protected; GitHub HTTPS preferred) | no |
| `plugins/AIBots/config.yml` LLM URLs | already in repo (`dadsbox.local`) — no API keys filled in |
| `gh` keyring | local only |
