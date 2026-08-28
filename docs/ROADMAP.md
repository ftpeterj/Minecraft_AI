# Minecraft_AI Roadmap & Setup Guide

## Project Vision
A cooperative Minecraft session on a **personal offline server**: you are the leader; AI teammates look and act like other players, take useful initiative, and always defer to you. Local + optional cloud LLMs. No extra Minecraft accounts.

## Key Advantages (Personal Server)
- Offline mode: Unlimited AI "players"/NPCs without paid accounts.
- Full control: No anti-cheat worries.
- Hybrid server-side NPCs + optional client-side bots.
- Deep collaboration, learning, and multi-LLM flexibility.

## Core Features
### Inter-Bot Collaboration & Learning
- Bots discuss needs, delegate tasks, and coordinate (e.g., Builder requests Warrior to guard, Miner to fetch resources).
- Shared memory and learning from each other.
- Reactive behaviors to world events (destruction, resource shortages).

### Multi-LLM System
- Primary: Local Ollama (fast, private).
- Fallback: Local LM Studio if Ollama is down.
- Optional: Grok, ChatGPT, Claude, Gemini, etc. (OpenAI-compatible endpoints).
- Per-bot/role/task routing + hybrid escalation for complex tasks.

### Player-Like Autonomy
- Teammates, not replacements — human stays in charge.
- Player bodies, chat, tab list, tools; movement, loot, crafting, building, combat.
- Skill chaining and task execution via job board. They help; they don't hijack the session.

## Status (AIBots 1.5.x)

Shipped in monorepo under `AIBotsPlugin/`:
- Multi-role crew: scavenger, miner, woodsman, hunter, farmer, warrior, protector, builder
- Villager avatars + Paper pathfinding, loot bag UI, chest network, stack size up to 99
- Learning: teach / share / episodes (`learning.yml`)
- Multi-LLM: `LLMProvider`, `OllamaProvider`, `OpenAiCompatibleProvider`, `LLMRouter` + config
- Inter-bot messaging: `/crew msg`, material requests from builder
- Builder primitives: wall / platform / pillar / box
- **Crew skill interface + job board** (delegation queue)
- **Storage room register** (`/crew storage register` / pos1–pos2, like `/fill`)
- **Force deposit** (`/crew deposit <name>`) + inventory list (`/crew inv`)

### Storage registration note (playtested)
When using **pos1 / pos2**, corners must span **height** as well as X/Z.  
Floor-only corners produce a flat box and miss wall chests.  
**Working pattern:** floor corner → opposite **ceiling / top-of-chests** corner → `register`.  
See **`docs/STORAGE.md`**.

## Roadmap

**Phase 1: Foundation**
- [x] Multi-LLM provider abstraction *(router + OpenAI-compatible; per-role routing & empty-key skip still thin)*
- [x] Offline mode setup & basic NPC enhancements *(SETUP.md; villager crew)*
- [x] Inter-bot messaging system *(msg/delegate/need-material; multi-turn LLM chat later)*
- [~] Shared storage/RAG *(chest network + /fill-style room register + learning facts; not vector RAG yet)*

**Phase 2: Collaboration**
- [x] Skill framework with delegation *(CrewSkill + CrewJobBoard — v1)*
- [ ] World observation & reactive AI
- [ ] Hybrid LLM queries *(escalate-complex flag exists; dual-pass plans not wired)*
- [ ] Craftsman / full craft-from-storage
- [ ] Builder v2 (shopping list → auto jobs for gatherers)

**Phase 3: Polish**
- [ ] Testing/comparison tools for LLMs
- [ ] Client-side bot integration (optional)
- [ ] Documentation & examples *(SETUP + this file; playbooks TBD)*

Legend: `[x]` done · `[~]` partial · `[ ]` not started

## Next priorities
1. World event bus (shortages, damage near home, night/raid)
2. Structured shared memory query for plan/chat context
3. Hybrid LLM real path (local draft → cloud escalate)
4. Builder shopping list → job board gather jobs
5. LLM compare harness + in-game playbooks

## Setup Instructions
See `SETUP.md` for full details.
