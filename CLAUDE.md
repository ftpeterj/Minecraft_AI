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
BotInteropPlugin/   Paper plugin — /botinv, /botarmor, /botstatus, /botrepair, /botfriend, /bottrade, overhead hologram, for a mineflayer bot account
BagsPlugin/         Paper plugin — large (53-slot) portable bag item, shift+right-click to open, /bag, /givebag
StackSize99Plugin/  Paper plugin — bumps normally-64-max item stacks to 99
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
- `persistent-bot.js` in that folder — connects and stays connected indefinitely with exponential-backoff auto-reconnect. Loads `mineflayer-pathfinder` for real movement.
- Bot account: **`bloodypuddlekos`**, a real purchased Java Edition Microsoft account, whitelisted on Survival. Never use the owner's own account for a bot — logging a bot in on an account that's already online kicks that live session ("logged in from another location").

**`ai.js`** (same folder) — Ollama-driven conversation + action loop, local model `qwen2.5:7b` (tool-calling capable, switched from `14b` on 2026-09-06 — same format, ~half the VRAM, faster, and leaves headroom for Minecraft's shaders on the same 12GB card), talked to via `/api/chat`.
- Anyone can talk to the bot: whisper it, prefix public chat with its name, or (as of 2026-09-06) just stand within `NEARBY_CHAT_RADIUS` (default 3) blocks of it and chat normally, no prefix needed.
- Deterministic fast-path commands (bypass the LLM): `equip <item> [offhand]`, `durability`.
- Everything else goes to the model with a small tool set: `come_here`, `follow_player <name>`, `stop`, `eat` (equips a food item and calls `bot.consume()`), `equip_item`, `give_item <name> <item> [count]` (paths to the player and `bot.toss()`s the item — needs `bot.players[name].entity` to exist, i.e. the target must actually be loaded/visible to the bot's client, or it replies "I can't see them"), and `wiki_lookup <topic>` (queries the real `minecraft.wiki` MediaWiki API for search + plain-text extract, then feeds the result back to the model for a natural reply — no trust gate, it's read-only).
- **Auto-survival (2026-09-06):** `attachAutoSurvival()` runs on every mineflayer `health` event, independent of the LLM/trust system entirely (it's not sender-initiated). Eats on its own once hunger drops below 14/20, same as a real player would. Only pings the owner (same RCON `tellraw` path as approval requests) if it's genuinely hungry (≤6/20) AND has no food left, rate-limited to once per 5 minutes.
- **Trust model (separate from the Java plugin's own, below — different machines, no shared file):** owner is `KingOfThisHouse` (`BOT_OWNER` env var). Owner/friends' tool calls run immediately. A stranger's tool call is held pending and the owner is whispered for a decision. Owner commands (say to the bot): `approve <name>` / `deny <name>` (decides only that one pending action, grants no lasting trust), `friend add|remove|list [name]` (persisted to `friends.json`, explicit and separate from approve/deny).
- **Gotcha found 2026-09-06:** "will you be my friend?" has no tool of its own, so the model would just answer conversationally and agree — the trust gate only ever fires on a tool call, so plain chat replies bypassed it completely (a stranger got a verbal "sure!" with zero owner involvement, no actual trust granted). Fixed with a dedicated `request_friendship` tool the model is instructed to call for any ask-to-be-trusted message; approving it now grants lasting friendship (not a one-off action) via the same pending/approve flow. If the bot ever agrees to something odd in plain chat again with no tool call logged, suspect the same class of bug — the model chatting past a boundary that's only enforced on tool calls.
- `findPlayerEntity()` falls back to scanning `bot.entities` when `bot.players[name].entity` is unset (it can lag even for a genuinely nearby player) — `come_here`/`follow_player`/`give_item` use this, not the raw `bot.players` lookup.
- **Owner notifications don't use whisper at all anymore (2026-09-06).** Survival enforces secure/signed chat; this bot's unofficial protocol patch can't produce a valid signing key for its account, so `bot.whisper()`'s `/tell` silently never reached the owner even though the command "succeeded" server-side (confirmed live: a friend request correctly triggered the approval flow, but the whisper never arrived). Fixed by routing owner notifications through RCON `tellraw` instead (`rcon.js`, a small dependency-free client) — that's console-issued, not signed player chat, so it bypasses the problem, and stays private to the owner. **Requires `RCON_PASSWORD` set in the bot process's environment** (same password as `ops.local.yml`) — if it's ever restarted without that env var set, notifications silently no-op again (logged as `owner notify skipped: RCON_PASSWORD not set`, easy to miss). `RCON_HOST` defaults to `minecraft.local`, not `127.0.0.1` — the bot process runs on DadsBox, a different machine than the server, unlike the ad-hoc `mcrcon`-over-SSH usage elsewhere in this doc where `127.0.0.1` is correct (that runs the RCON client on the server box itself, via SSH).
- **Ollama/GPU gotcha:** at the default 32K context, a 14B-class model's weights+KV-cache don't fit in the RX 6700 XT's 12GB VRAM and Ollama silently splits layers to CPU (`ollama ps` shows a CPU/GPU split — much slower). Every call here passes `options: { num_ctx }` (`OLLAMA_NUM_CTX` env var, default 8192 as of the `qwen2.5:7b` switch) to force `100% GPU`. Check with `ollama ps` right after a call if this ever regresses — `qwen2.5:7b` at 8192 context sits at ~5.1GB, well under budget even with shaders running.
- **2026-09-06, transient auth blip, resolved on its own:** the bot's connection dropped (`ECONNRESET`) and its immediate reconnect attempt failed Microsoft auth (`Failed to obtain profile data for bloodypuddlekos, does the account own minecraft?`). A second, unscheduled manual restart succeeded normally — so this was a one-off hiccup, not the cached-token corruption described in the auth gotcha below. If it happens repeatedly rather than as a one-off, suspect the token cache instead and follow that gotcha's fix (clear `nmp-cache`, fresh device-code sign-in).
- **`selfContext(bot)` (2026-09-06):** every call also gets a fresh system message with the bot's real position, health/hunger, held item, and full inventory — before this, none of that was fed to the model at all, so "where are you"/"are you hurt"/"what do you have" all got hallucinated. If the bot ever invents an answer about some other piece of its own state (nearby players/entities, time of day, etc.), suspect the same class of gap and extend `selfContext()` with that data rather than adding a new tool for a read-only fact.
- **Major bug found 2026-09-06: the bot's own client-side item data is unreliable, not just its behavior.** This server's real Minecraft version (26.2) has no official mineflayer support — the patch this bot runs on clones `minecraft-data` from 26.1 and relabels it as 26.2 (see the mineflayer-bot section below). Real 26.2's actual item registry doesn't match that relabeled table, so mineflayer's client-side item-id decoding can resolve an item to a completely WRONG name. Confirmed live: a real `cooked_porkchop` the bot was genuinely holding showed up in `bot.inventory.items()` as an "iron sword" — `selfContext()`'s inventory listing and the `eat` tool's `findFoodItem()` were both silently wrong as a result. **The fix pattern:** don't trust `bot.inventory.items()`'s own `.name`/`.displayName` for anything that matters — `findFoodItem()` now self-issues BotInterop's `/botinv <self> list` (read via Bukkit server-side, immune to this bug) to get the real name at a real Bukkit slot, converts that to the matching mineflayer protocol slot index, and uses whatever object sits there as an opaque handle for `bot.equip()`/`bot.consume()` — never reading that object's own name. **This same corruption risk applies to `equip_item` and `give_item`**, which still match by name against the live client-side inventory — not yet fixed, but now a known/understood class of bug rather than a mystery; apply the same ground-truth-via-`/botinv list` pattern if either misbehaves.

**`C:\Projects\BotInteropPlugin\`** — standalone Paper plugin (pattern-matches `JoinGatePlugin/`, independent of `AIBotsPlugin/`). Since the bot is a real online player, a plain `openInventory(otherPlayer.getInventory())` only shows the 36 main/hotbar slots — Bukkit restricts the full armor view to the *owning* player. So there are separate windows, all custom-synced GUIs (not a direct view):
- Right-click a bot account, or `/botinv <name>`, opens its **main inventory** (36 slots, `BotInventoryHolder`) — but only for the owner or a friend (see trust below); anyone else gets the trade screen instead. `/botinv <name> list` (2026-09-06) prints a plain-text slot -> item listing instead (main inventory, armor, offhand, and bag contents decoded read-only via BagsPlugin's PDC key scheme) — read via Bukkit server-side, so it's immune to the mineflayer client-side item-id bug described below. A bot account may always list its own inventory this way regardless of the friends list.
- `/botarmor <name>` opens a labeled 18-slot window (`BotArmorHolder`): a static icon-labeled row (Helmet/Chestplate/Leggings/Boots/Off-hand/Main-hand) with the real editable slots directly below. Helmet/chestplate/leggings/boots reject non-matching item types (returned to the editor, not lost); off-hand and main-hand accept anything. Unused padding slots are click-blocked so nothing can be dropped into them and silently lost.
- **`BotTradeHolder`/`BotTradeService`** — what a non-friend gets instead of direct inventory access when they right-click a bot: an 18-slot screen, row 0 a read-only snapshot of the bot's current items (click one to mark it "wanted" — cancelled, never actually taken), row 1 real slots to place an offer (a genuine placement, physically out of their inventory the moment they drop it in). Closing the window (if non-empty) packages offer+wants into a proposal and whispers the owner. Owner: `/bottrade list`, `/bottrade approve|deny <name>` — approve gives the offer to the bot and the wanted items (if found) to the requester; deny returns the offer.
- **`BotFriendService`** — the Java-side trust list gating GUI access (right-click/`/botinv`/`/botarmor`), **separate from `ai.js`'s `friends.json`** (different machine, no shared file — if you want one unified list, that needs a small bridge, e.g. the bot issuing an RCON command back to the server on approval). Owner name comes from `config.yml` → `owner` (default `KingOfThisHouse`). Manage with `/botfriend add|remove|list <name>` (owner or op only). Persisted to `friends.yml` in the plugin's data folder.
- Also `/botstatus <name>` (health/hunger/location/durability, in chat), a live `TextDisplay` hologram over the bot's head (health%, hunger%, durability% per worn/held item, plus any active potion effects with amplifier level, refreshed every second, cleaned up on quit/plugin reload), and `/botrepair <name> on|off` — toggleable auto-repair, heals ~1% of max durability every 10s while on (per-bot, in-memory only, resets on restart).
- Config: `BotInteropPlugin/src/main/resources/config.yml` → `bot-accounts`, `owner`.
- **Real root cause of the "armor keeps disappearing" saga (2026-09-05/06), now fixed:** `BotInventoryHolder.syncToTarget()` (the `/botinv` 36-slot main-inventory sync) called `PlayerInventory#setContents()` with a 36-element array — that call clears the *entire* underlying inventory storage (armor + offhand included) before refilling just those 36 slots, silently wiping armor as a side effect of simply opening and closing the main-inventory window. Fixed by setting each of the 36 slots individually via `setItem()` instead. The same holder was also missing a `target.updateInventory()` call after syncing (unlike `BotArmorHolder`, which already had one) — without it, edits via `/botinv` changed the real server-side data correctly but never told the bot's own client to refresh, so the bot's local inventory model (what `equip`/`durability` read from) could drift stale. Both are fixed now.
- **Standing lesson from the above, confirmed a second time in `BotTradeHolder.loadCatalog()`:** bulk `Inventory#getContents()`/`setContents()` calls are unreliable on this build — the trade screen was showing the bot's equipped armor instead of its main inventory because of the exact same bulk-read pattern. Always use per-slot `getItem(i)`/`setItem(i, ...)` loops against Bukkit inventories on this server, never the bulk array accessors, even for a read-only sync.
- **Diagnostic-tool gotcha that caused a lot of wasted debugging churn on the way to finding the above:** `/data get entity <name> ArmorItems` does not work on this Minecraft version — that NBT path doesn't exist here; armor/offhand are under `equipment` instead (`/data get entity <name> equipment`, or `equipment.head`/`.chest`/`.legs`/`.feet`/`.offhand` individually to avoid RCON's response-size truncation). Querying `ArmorItems` always silently returns "no elements," which looks exactly like "the armor disappeared" even when it's sitting there fine. Check with `equipment` before assuming a real bug.
- `StackSize99Plugin/` — a separate small plugin overriding normally-64-max items to stack to 99 (crafting/smelting/drops/pickups/inventory-click/join/container-open hooks). Deployed to Survival only so far.
- `BagsPlugin/` — a separate plugin adding a large portable-storage item (a SADDLE, 53 usable slots + a reserved Close-button slot, no external database — contents are Base64-serialized into the item's own PersistentDataContainer, so they travel with it through trading/dropping/death). Shift+right-click a bag to open it, wherever it currently is (own inventory, a chest, or another plugin's GUI like `/botinv`) — `BagHolder`/`BagGui` work against whichever real `Inventory` it was clicked in, re-locating the item by its id on every sync rather than trusting a remembered slot. `/bag` opens the first bag found in your inventory/off-hand as a fallback; `/givebag <player>` (op-only) hands one out. Nesting a bag inside itself is blocked (both at the click/drag level and as a last-resort safety net in `syncToItem()`). Closing a bag that was opened from inside another GUI reopens that GUI instead of dropping the player out of all menus. Deployed to Survival only so far.
- Survival specifics: RCON port **25575** (Creative is 25577, same password in `ops.local.yml`). Survival's Paper process runs as OS user `mc5` (Creative is `mc4`) — `xxadmin` can't write directly to `survival/plugins/`; deploy via `scp` to `/tmp` then `sudo mv` + `sudo chown mc5:mc5`.
- **Every Java plugin change needs a full survival/creative restart** (no hot-reload tooling installed) — always give a clear, loud 5-second warning before restarting, since it boots any real players online. A `persistent-bot.js`/`ai.js` (mineflayer script) change only needs the local Node process restarted, no server restart, no player disruption. Both `BotRepairService`'s auto-repair toggle and Chunky's pregeneration task (see below) are in-memory/paused across a survival restart — re-enable with `/botrepair <name> on` and `/chunky continue` after each one.
- Auth gotcha: prismarine-auth caches Microsoft tokens at `%APPDATA%\.minecraft\nmp-cache\<hash>_*-cache.json`, hashed from the exact (case-sensitive) username string. A failed auth attempt still writes a cache file that gets silently reused on retry, producing a different and more confusing error later (`Profile not found, please restart your launcher...`) even after the real problem is fixed. Clear the relevant cache files and force a fresh device-code sign-in after any auth failure rather than just retrying.

**World pregeneration (2026-09-06):** Chunky is pregenerating a 10,000-block-radius square on `world` centered on spawn (`chunky world world; chunky spawn; chunky shape square; chunky radius 10000; chunky start/confirm`), and BlueMap auto-renders newly generated chunks as they land (no manual trigger needed). This is a genuinely long-running task (millions of chunks) that competes with normal gameplay for CPU on this 4-core box — expect it to take many hours and cause some lag while it runs. It does **not** survive a server restart; run `/chunky continue` afterward or it just sits paused. Check progress with `/chunky progress`.

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
