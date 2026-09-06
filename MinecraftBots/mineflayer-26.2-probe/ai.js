'use strict'
/**
 * LLM-driven conversation + action loop for the bot, via local Ollama.
 *
 * Trust model: anyone can talk to the bot and get a conversational reply.
 * If the model decides a message calls for an actual in-world action (a tool
 * call), that action only runs immediately for the OWNER or an approved
 * "friend". For anyone else, the action is held pending and the owner is
 * asked (via whisper) whether to trust that player; the owner replies
 * `approve <name>` or `deny <name>`. Approval both runs the held action and
 * remembers the player as a friend (persisted to friends.json) for next time.
 */
const fs = require('fs')
const path = require('path')
const { goals } = require('mineflayer-pathfinder')

const OLLAMA_URL = process.env.OLLAMA_URL || 'http://127.0.0.1:11434'
// qwen2.5:7b (not 14b): same tool-calling format/prompt, ~half the VRAM
// (4.7GB weights vs 9GB), noticeably faster on this 12GB card while it's
// also driving Minecraft's shaders. At num_ctx 8192 it still sits at 100%
// GPU with room to spare (~5.1GB total) — see `ollama ps` if this regresses.
const MODEL = process.env.OLLAMA_MODEL || 'qwen2.5:7b'
const NUM_CTX = Number(process.env.OLLAMA_NUM_CTX || 8192)
const OWNER = (process.env.BOT_OWNER || 'KingOfThisHouse').toLowerCase()
const FRIENDS_PATH = path.join(__dirname, 'friends.json')

function loadFriends () {
  try {
    return new Set(JSON.parse(fs.readFileSync(FRIENDS_PATH, 'utf8')))
  } catch {
    return new Set()
  }
}

function saveFriends (friends) {
  fs.writeFileSync(FRIENDS_PATH, JSON.stringify([...friends], null, 2))
}

const friends = loadFriends()
const pending = new Map() // lowercase requester name -> { toolName, toolArgs, reply }

function isTrusted (name) {
  const n = name.toLowerCase()
  return n === OWNER || friends.has(n)
}

// Proactive self-care: eats on its own once hungry rather than waiting to be
// told, same as a real player would. Only alerts the owner once it's both
// genuinely hungry AND has nothing left to eat — not on every tick, and not
// just for being below max food (that'd fire constantly under normal play).
const AUTO_EAT_THRESHOLD = 14
const LOW_FOOD_ALERT_THRESHOLD = 6
const LOW_FOOD_ALERT_COOLDOWN_MS = 5 * 60 * 1000
let autoEating = false
let lastLowFoodAlert = 0

const SYSTEM_PROMPT = `You are a helpful Minecraft player-character bot on a survival server.
Keep replies short and in-character, like a fellow player chatting, not an assistant.
If someone asks you to do something you have a tool for, call that tool. Otherwise just reply in chat.
Only call a tool when the message is clearly asking you to act, not for idle chat.
If you're unsure how to make, craft, build, or do something in Minecraft, use wiki_lookup to check
the Minecraft Wiki rather than guessing — then answer using what it tells you.
You never decide on your own who to trust. If someone asks to be friends, asks you to trust them,
or asks why you won't do something for them, call request_friendship — never say yes yourself and
never claim you're already friends unless a tool result told you so.
Your current position, health/hunger, held item, and inventory are given to you before each
message — use that when asked where you are, whether you're hurt, or what you're carrying. Never
guess or make up any of it.
Always reply only in English, using only standard Latin letters — never any other script.`

/**
 * Built fresh on every call so it reflects the bot's live state, not a stale
 * snapshot — same reasoning as the position fix: without this, the model has
 * no real data for "what do you have"/"are you hurt" and just hallucinates.
 */
function selfContext (bot) {
  const p = bot.entity?.position
  const location = p
    ? `Position: x=${p.x.toFixed(1)}, y=${p.y.toFixed(1)}, z=${p.z.toFixed(1)}, dimension=${bot.game?.dimension || 'unknown'}.`
    : 'Position: unknown right now (not fully spawned into the world yet).'

  const statusParts = []
  if (typeof bot.health === 'number') statusParts.push(`health=${bot.health.toFixed(1)}/20`)
  if (typeof bot.food === 'number') statusParts.push(`hunger=${bot.food}/20`)
  statusParts.push(`holding=${bot.heldItem ? (bot.heldItem.displayName || bot.heldItem.name) : 'nothing'}`)
  const status = `Status: ${statusParts.join(', ')}.`

  const items = bot.inventory?.items() || []
  const inventory = items.length
    ? `Inventory: ${items.map((i) => `${i.count}x ${i.displayName || i.name}`).join(', ')}.`
    : 'Inventory: empty.'

  return `${location}\n${status}\n${inventory}`
}

/** Defensive filter: strip any stray non-Latin-script characters (a known qwen2.5 quirk — it occasionally leaks CJK text) before a reply reaches chat. */
function stripNonLatinScript (text) {
  return text.replace(/[　-鿿가-힣＀-￯]+/g, '').replace(/\s{2,}/g, ' ').trim()
}

const TOOLS = [
  {
    type: 'function',
    function: {
      name: 'come_here',
      description: 'Walk to the player who is asking, using their current position.',
      parameters: { type: 'object', properties: {}, required: [] }
    }
  },
  {
    type: 'function',
    function: {
      name: 'follow_player',
      description: 'Continuously follow a named player around.',
      parameters: {
        type: 'object',
        properties: { name: { type: 'string', description: 'Player username to follow' } },
        required: ['name']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'stop',
      description: 'Stop moving/following and stand still.',
      parameters: { type: 'object', properties: {}, required: [] }
    }
  },
  {
    type: 'function',
    function: {
      name: 'eat',
      description: 'Eat food from your inventory to restore hunger.',
      parameters: { type: 'object', properties: {}, required: [] }
    }
  },
  {
    type: 'function',
    function: {
      name: 'equip_item',
      description: 'Equip an item from inventory by name.',
      parameters: {
        type: 'object',
        properties: {
          item: { type: 'string', description: 'Item name, e.g. "netherite axe"' },
          offhand: { type: 'boolean', description: 'Force off-hand instead of the default slot' }
        },
        required: ['item']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'give_item',
      description: 'Walk to a named player and drop an item from inventory for them to pick up.',
      parameters: {
        type: 'object',
        properties: {
          name: { type: 'string', description: 'Player username to give the item to' },
          item: { type: 'string', description: 'Item name, e.g. "netherite axe"' },
          count: { type: 'integer', description: 'How many to give (default: the whole stack)' }
        },
        required: ['name', 'item']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'request_friendship',
      description: 'Call this whenever someone asks to be friends, asks you to trust them, or asks why you can\'t do something for them. Never grant trust yourself — this asks the owner to decide.',
      parameters: { type: 'object', properties: {}, required: [] }
    }
  },
  {
    type: 'function',
    function: {
      name: 'wiki_lookup',
      description: 'Look up how to make, craft, build, find, or do something, from the Minecraft Wiki (minecraft.wiki). Use this whenever you are not sure of a recipe, mechanic, or game detail rather than guessing.',
      parameters: {
        type: 'object',
        properties: { topic: { type: 'string', description: 'What to look up, e.g. "brewing stand" or "how to tame a wolf"' } },
        required: ['topic']
      }
    }
  }
]

const WIKI_INFORMATIONAL_TOOL = 'wiki_lookup'

async function wikiLookup (topic) {
  const searchRes = await fetch(`https://minecraft.wiki/api.php?action=query&list=search&srsearch=${encodeURIComponent(topic)}&format=json&srlimit=1`)
  if (!searchRes.ok) throw new Error(`wiki search http ${searchRes.status}`)
  const searchData = await searchRes.json()
  const title = searchData.query?.search?.[0]?.title
  if (!title) return null

  const extractRes = await fetch(`https://minecraft.wiki/api.php?action=query&titles=${encodeURIComponent(title)}&prop=extracts&format=json&explaintext=1&exchars=1200`)
  if (!extractRes.ok) throw new Error(`wiki extract http ${extractRes.status}`)
  const extractData = await extractRes.json()
  const page = Object.values(extractData.query?.pages || {})[0]
  return page?.extract ? { title, extract: page.extract } : null
}

async function ollamaChat (bot, sender, message) {
  const res = await fetch(`${OLLAMA_URL}/api/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      model: MODEL,
      stream: false,
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'system', content: selfContext(bot) },
        { role: 'user', content: `${sender} says: ${message}` }
      ],
      tools: TOOLS,
      options: { num_ctx: NUM_CTX, temperature: 0.3 }
    })
  })
  if (!res.ok) throw new Error(`ollama http ${res.status}`)
  const data = await res.json()
  return data.message
}

/** Sends a tool's result back to the model so it can phrase a natural reply, rather than pasting raw data into chat. */
async function ollamaChatWithToolResult (bot, sender, message, assistantMessage, toolResultContent) {
  const res = await fetch(`${OLLAMA_URL}/api/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      model: MODEL,
      stream: false,
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'system', content: selfContext(bot) },
        { role: 'user', content: `${sender} says: ${message}` },
        assistantMessage,
        { role: 'tool', content: toolResultContent }
      ],
      options: { num_ctx: NUM_CTX, temperature: 0.3 }
    })
  })
  if (!res.ok) throw new Error(`ollama http ${res.status}`)
  const data = await res.json()
  return data.message?.content
}

function describeCall (toolName, toolArgs) {
  const argStr = Object.keys(toolArgs).length ? ' ' + JSON.stringify(toolArgs) : ''
  return `${toolName}${argStr}`
}

function findItem (inventory, query) {
  const q = query.trim().toLowerCase().replace(/[\s-]+/g, '_')
  const items = inventory.items()
  return (
    items.find((i) => i.name === q) ||
    items.find((i) => i.name.includes(q)) ||
    items.find((i) => i.displayName?.toLowerCase().includes(query.trim().toLowerCase()))
  )
}

function findFoodItem (bot) {
  const foodNames = new Set((bot.registry?.foodsArray || []).map((f) => f.name))
  return bot.inventory.items().find((i) => foodNames.has(i.name))
}

async function eatFood (bot, food) {
  await bot.equip(food, 'hand')
  await bot.consume()
}

/** bot.players[name].entity can lag/stay unset even when the player is genuinely nearby — fall back to scanning bot.entities directly. */
function findPlayerEntity (bot, username) {
  const viaPlayers = bot.players[username]?.entity
  if (viaPlayers) return viaPlayers
  return Object.values(bot.entities).find((e) => e.type === 'player' && e.username === username)
}

async function runTool (bot, equipHandler, toolName, toolArgs, sender, reply) {
  switch (toolName) {
    case 'come_here': {
      const entity = findPlayerEntity(bot, sender)
      if (!entity) { reply("I can't see you right now"); return }
      bot.pathfinder.setGoal(new goals.GoalNear(entity.position.x, entity.position.y, entity.position.z, 1))
      reply('on my way')
      return
    }
    case 'follow_player': {
      const entity = findPlayerEntity(bot, toolArgs.name)
      if (!entity) { reply(`I can't see ${toolArgs.name} right now`); return }
      bot.pathfinder.setGoal(new goals.GoalFollow(entity, 2), true)
      reply(`following ${toolArgs.name}`)
      return
    }
    case 'stop': {
      bot.pathfinder.setGoal(null)
      reply('stopping')
      return
    }
    case 'eat': {
      const food = findFoodItem(bot)
      if (!food) { reply("I don't have any food on me"); return }
      try {
        await eatFood(bot, food)
        reply(`ate ${food.displayName || food.name}`)
      } catch (err) {
        reply(`couldn't eat: ${err.message}`)
      }
      return
    }
    case 'equip_item': {
      const words = toolArgs.item.split(/\s+/)
      if (toolArgs.offhand) words.push('offhand')
      await equipHandler(words, reply)
      return
    }
    case 'give_item': {
      const targetEntity = findPlayerEntity(bot, toolArgs.name)
      if (!targetEntity) { reply(`I can't see ${toolArgs.name} right now`); return }
      const item = findItem(bot.inventory, toolArgs.item)
      if (!item) {
        const have = bot.inventory.items().map((i) => i.name).join(', ') || '(empty)'
        reply(`I don't have "${toolArgs.item}". Have: ${have}`)
        return
      }
      try {
        await bot.pathfinder.goto(new goals.GoalNear(targetEntity.position.x, targetEntity.position.y, targetEntity.position.z, 2))
        const count = toolArgs.count ? Math.min(toolArgs.count, item.count) : item.count
        await bot.toss(item.type, null, count)
        reply(`dropped ${count} ${item.displayName || item.name} for ${toolArgs.name}`)
      } catch (err) {
        reply(`couldn't get to ${toolArgs.name}: ${err.message}`)
      }
      return
    }
    default:
      reply(`(no handler for ${toolName})`)
  }
}

/**
 * Owner-only commands:
 *   approve <name> / deny <name>  — decide the CURRENT pending request only.
 *                                   Approving does NOT grant lasting trust —
 *                                   the next actionable request from them
 *                                   needs approval again, until you add them
 *                                   as a friend explicitly.
 *   friend add <name>              — grant lasting trust (persisted).
 *   friend remove <name>           — revoke it.
 *   friend list                    — show current friends.
 */
function handleOwnerDecision (bot, equipHandler, message, ownerReply) {
  const text = message.trim()

  const approveDeny = text.match(/^(approve|deny)\s+(\S+)/i)
  if (approveDeny) {
    const decision = approveDeny[1].toLowerCase()
    const name = approveDeny[2].toLowerCase()
    const held = pending.get(name)
    if (!held) {
      ownerReply(`no pending request from ${name}`)
      return true
    }
    pending.delete(name)
    if (decision === 'deny') {
      ownerReply(`denied ${name}`)
      held.reply(held.toolName === 'request_friendship' ? `sorry, my owner said not to be friends` : `sorry, my owner said no`)
      return true
    }
    if (held.toolName === 'request_friendship') {
      friends.add(name)
      saveFriends(friends)
      ownerReply(`${name} is now a trusted friend`)
      held.reply(`my owner said yes — we're friends now!`)
      return true
    }
    ownerReply(`approved this one request from ${name} (not a friend yet — say "friend add ${name}" for that)`)
    runTool(bot, equipHandler, held.toolName, held.toolArgs, held.originalSender, held.reply)
      .catch((err) => held.reply(`error running that: ${err.message}`))
    return true
  }

  const friendCmd = text.match(/^friend\s+(add|remove|list)(?:\s+(\S+))?/i)
  if (friendCmd) {
    const action = friendCmd[1].toLowerCase()
    const name = friendCmd[2]?.toLowerCase()
    if (action === 'list') {
      ownerReply(friends.size ? [...friends].join(', ') : 'no friends yet')
      return true
    }
    if (!name) {
      ownerReply(`usage: friend ${action} <name>`)
      return true
    }
    if (action === 'add') {
      friends.add(name)
      saveFriends(friends)
      ownerReply(`${name} is now a trusted friend`)
    } else {
      friends.delete(name)
      saveFriends(friends)
      ownerReply(`${name} removed from friends`)
    }
    return true
  }

  return false
}

/**
 * Entry point for any chat/whisper aimed at the bot that isn't one of the
 * existing deterministic commands (equip/durability). `equipHandler` is
 * persistent-bot.js's existing handleEquipCommand, reused as the equip_item tool.
 */
async function handleAiMessage (bot, equipHandler, sender, message, reply, ownerReplyFn) {
  if (sender.toLowerCase() === OWNER && handleOwnerDecision(bot, equipHandler, message, reply)) {
    return
  }

  let assistantMessage
  try {
    assistantMessage = await ollamaChat(bot, sender, message)
  } catch (err) {
    reply("(brain's not responding right now)")
    console.log(`[ai] ollama error: ${err.stack || err}`)
    return
  }

  const toolCall = assistantMessage.tool_calls?.[0]
  if (!toolCall) {
    if (assistantMessage.content) reply(stripNonLatinScript(assistantMessage.content))
    return
  }

  const toolName = toolCall.function.name
  const toolArgs = toolCall.function.arguments || {}

  if (toolName === WIKI_INFORMATIONAL_TOOL) {
    // Read-only knowledge lookup — no action taken, so no trust gate needed.
    let resultText
    try {
      const info = await wikiLookup(toolArgs.topic)
      resultText = info ? `${info.title}: ${info.extract}` : `No wiki page found for "${toolArgs.topic}"`
    } catch (err) {
      console.log(`[ai] wiki lookup error: ${err.stack || err}`)
      resultText = 'wiki lookup failed'
    }
    try {
      const finalReply = await ollamaChatWithToolResult(bot, sender, message, assistantMessage, resultText)
      reply(stripNonLatinScript(finalReply || resultText))
    } catch (err) {
      console.log(`[ai] wiki lookup error: ${err.stack || err}`)
      reply(resultText)
    }
    return
  }

  if (toolName === 'request_friendship' && isTrusted(sender)) {
    reply(`we're already friends!`)
    return
  }

  if (toolName !== 'request_friendship' && isTrusted(sender)) {
    await runTool(bot, equipHandler, toolName, toolArgs, sender, reply)
    return
  }

  pending.set(sender.toLowerCase(), { toolName, toolArgs, originalSender: sender, reply })
  if (toolName === 'request_friendship') {
    reply('let me check with my owner first...')
    ownerReplyFn(`${sender} wants to be friends. Reply "approve ${sender}" or "deny ${sender}".`)
    return
  }
  reply('let me check with my owner first...')
  ownerReplyFn(`${sender} asked me to: ${describeCall(toolName, toolArgs)}. Reply "approve ${sender}" or "deny ${sender}".`)
}

async function maybeEatOrAlert (bot, notifyOwner) {
  if (autoEating) return
  if (typeof bot.food !== 'number' || bot.food > AUTO_EAT_THRESHOLD) return

  const food = findFoodItem(bot)
  if (food) {
    autoEating = true
    try {
      await eatFood(bot, food)
    } catch (err) {
      console.log(`[ai] auto-eat error: ${err.stack || err}`)
    } finally {
      autoEating = false
    }
    return
  }

  if (bot.food > LOW_FOOD_ALERT_THRESHOLD) return
  const now = Date.now()
  if (now - lastLowFoodAlert < LOW_FOOD_ALERT_COOLDOWN_MS) return
  lastLowFoodAlert = now
  notifyOwner(`I'm hungry (${bot.food}/20) and out of food!`)
}

/** Call once after the bot spawns. Runs on every health/food update (mineflayer's 'health' event covers both). */
function attachAutoSurvival (bot, notifyOwner) {
  bot.on('health', () => {
    maybeEatOrAlert(bot, notifyOwner).catch((err) => console.log(`[ai] maybeEatOrAlert error: ${err.stack || err}`))
  })
}

module.exports = { handleAiMessage, attachAutoSurvival }
