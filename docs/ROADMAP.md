# Minecraft_AI Roadmap & Setup Guide

## Project Vision
Build a personal Minecraft AI ecosystem on a **single personal server** (offline mode) with collaborative, intelligent NPC teams powered by local + optional cloud LLMs. No extra Minecraft accounts required.

## Key Advantages (Personal Server)
- Offline mode: Unlimited AI "players"/NPCs without paid accounts.
- Full control: No anti-cheat worries.
- Hybrid server-side NPCs + optional client-side bots.
- Deep collaboration, learning, and multi-LLM flexibility.

## Core Features
### Inter-Bot Collaboration & Learning
- Bots discuss needs, delegate tasks, and coordinate (e.g., Builder requests Warrior to guard, Miner to fetch resources, Craftsman to craft tools).
- Shared memory and learning from each other.
- Reactive behaviors to world events (destruction, resource shortages).

### Multi-LLM System
- Primary: Local LM Studio (fast, private).
- Optional: Grok, ChatGPT, Claude, Gemini, etc.
- Per-bot/role/task routing + hybrid escalation for complex tasks (e.g., detailed building plans).

### Player-Like Autonomy
- NPCs/bots with full movement, inventory, crafting, building, combat.
- Skill chaining and task execution.

## Roadmap

**Phase 1: Foundation**
- [ ] Multi-LLM provider abstraction
- [ ] Offline mode setup & basic NPC enhancements
- [ ] Inter-bot messaging system
- [ ] Shared storage/RAG

**Phase 2: Collaboration**
- [ ] Skill framework with delegation
- [ ] World observation & reactive AI
- [ ] Hybrid LLM queries

**Phase 3: Polish**
- [ ] Testing/comparison tools for LLMs
- [ ] Client-side bot integration (optional)
- [ ] Documentation & examples

## Setup Instructions
See `SETUP.md` for full details.