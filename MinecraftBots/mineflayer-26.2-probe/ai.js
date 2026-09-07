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
const { Vec3 } = require('vec3')

const OLLAMA_URL = process.env.OLLAMA_URL || 'http://127.0.0.1:11434'
// qwen2.5:7b (not 14b): same tool-calling format/prompt, ~half the VRAM
// (4.7GB weights vs 9GB), noticeably faster on this 12GB card while it's
// also driving Minecraft's shaders. At num_ctx 8192 it still sits at 100%
// GPU with room to spare (~5.1GB total) — see `ollama ps` if this regresses.
const MODEL = process.env.OLLAMA_MODEL || 'qwen2.5:7b'
const NUM_CTX = Number(process.env.OLLAMA_NUM_CTX || 8192)
const OWNER_DISPLAY = process.env.BOT_OWNER || 'KingOfThisHouse'
const OWNER = OWNER_DISPLAY.toLowerCase()
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

// Proactive self-care: eats on its own once hungry (below 70%) rather than
// waiting to be told, same as a real player would — checking inventory,
// then nearby chests, before bothering the owner. Only alerts once both
// have failed, and only once per cooldown, not on every tick below the
// threshold.
const AUTO_EAT_THRESHOLD = 14
const LOW_FOOD_ALERT_COOLDOWN_MS = 5 * 60 * 1000
let autoEating = false
let lastLowFoodAlert = 0

// Self-defense: same reactive-autonomy pattern as auto-eat above, checked
// periodically rather than every physics tick to avoid needless scanning.
const HOSTILE_MOB_NAMES = new Set([
  'zombie', 'skeleton', 'creeper', 'spider', 'cave_spider', 'enderman', 'witch', 'phantom',
  'drowned', 'husk', 'stray', 'pillager', 'vindicator', 'evoker', 'ravager', 'zombie_villager',
  'silverfish', 'blaze', 'ghast', 'slime', 'magma_cube', 'hoglin', 'zoglin', 'piglin_brute', 'warden'
])
const WEAPON_NAMES = new Set([
  'wooden_sword', 'stone_sword', 'iron_sword', 'golden_sword', 'diamond_sword', 'netherite_sword',
  'wooden_axe', 'stone_axe', 'iron_axe', 'golden_axe', 'diamond_axe', 'netherite_axe', 'trident'
])
const FUEL_NAMES = new Set(['coal', 'charcoal', 'coal_block', 'blaze_rod', 'lava_bucket', 'oak_planks', 'stick'])
const DEFEND_RADIUS = 6
const DEFEND_CHECK_EVERY_TICKS = 10
let autoDefending = false

// follow_player can't use mineflayer-pathfinder's entity-based GoalFollow —
// the target entity object it needs isn't reliably available (see
// queryPlayerPosition's note). Polls the real position instead and reissues
// a fresh GoalNear periodically, closest thing to continuous following
// without a live entity reference.
const FOLLOW_POLL_MS = 2000
let followState = null // { name, interval }

// "Fish until I say stop" — one cast/catch cycle per bot.fish() call, so
// this loops internally rather than needing the model to keep re-issuing
// the tool call. Capped so a stuck bobber/water edge-case can't loop forever.
const FISH_MAX_CATCHES_PER_CALL = 10
let fishingCancelled = false

function stopFollowing () {
  if (followState) {
    clearInterval(followState.interval)
    followState = null
  }
}

function startFollowing (bot, name) {
  stopFollowing()
  const interval = setInterval(() => {
    queryPlayerPosition(bot, name).then((pos) => {
      if (pos) bot.pathfinder.setGoal(new goals.GoalNear(pos.x, pos.y, pos.z, 2))
    }).catch((err) => console.log(`[ai] follow poll error: ${err.stack || err}`))
  }, FOLLOW_POLL_MS)
  followState = { name, interval }
}

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
You are also told who your owner is, who your trusted friends are, and each of their last known
positions (when visible to you) before each message — use that fact, don't ask who someone is or
who owns you, and never claim someone is your owner or a friend unless it's actually in that list.
You are also told what block you're standing on and notable blocks nearby (ores, trees, water,
lava, crafting tables, furnaces, brewing stands, farmland) — use that for mining, farming, and
crafting decisions instead of guessing what's around you.
You have real survival skills: mine_block, craft_item, smelt (furnace/smoker), farm, fish,
brew_potion, enter_boat/exit_boat, and attack_nearby_hostile if something is threatening you or
your owner. Use them when asked, or on your own initiative if it's clearly needed (e.g. fighting
back if attacked) — you don't need to narrate every step, just act and report the outcome.
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

/**
 * Owner + friends, with a live position lookup (via queryPlayerPosition —
 * see its own note on why this can't just read bot.entities). Lets the
 * model correctly recognize "I am your owner" instead of treating it as a
 * random claim, and answer "where is <friend>" without guessing.
 * Short-cached since this is a real network round trip per person now, not
 * a free client-side read — avoids re-querying twice in the same exchange
 * (the initial reply, then again when a tool result comes back).
 */
const TRUSTED_POSITIONS_CACHE_MS = 3000
let cachedTrustedContext = null
let cachedTrustedContextAt = 0

async function trustedPeopleContext (bot) {
  const now = Date.now()
  if (cachedTrustedContext && now - cachedTrustedContextAt < TRUSTED_POSITIONS_CACHE_MS) return cachedTrustedContext

  const names = [{ label: OWNER_DISPLAY, key: OWNER }, ...[...friends].map((f) => ({ label: f, key: f }))]
  const lines = await Promise.all(names.map(async ({ label, key }) => {
    const pos = await queryPlayerPosition(bot, key)
    if (!pos) return `${label}: not currently visible to you.`
    return `${label}: x=${pos.x.toFixed(1)}, y=${pos.y.toFixed(1)}, z=${pos.z.toFixed(1)}.`
  }))
  const result = `Your owner is ${OWNER_DISPLAY}. Trusted people and their last known positions:\n${lines.join('\n')}`
  cachedTrustedContext = result
  cachedTrustedContextAt = now
  return result
}

// Block names are far more version-stable than items (Mojang adds new items
// far more often than new fundamental blocks), so unlike inventory reads
// this doesn't need the ground-truth workaround above — bot.findBlocks()
// against the local registry is trusted directly.
const NOTABLE_BLOCKS = [
  'oak_log', 'birch_log', 'spruce_log', 'jungle_log', 'acacia_log', 'dark_oak_log', 'mangrove_log', 'cherry_log',
  'stone', 'coal_ore', 'iron_ore', 'copper_ore', 'gold_ore', 'redstone_ore', 'diamond_ore', 'lapis_ore', 'emerald_ore',
  'deepslate_coal_ore', 'deepslate_iron_ore', 'deepslate_copper_ore', 'deepslate_gold_ore',
  'deepslate_redstone_ore', 'deepslate_diamond_ore', 'deepslate_lapis_ore', 'deepslate_emerald_ore',
  'water', 'lava', 'crafting_table', 'furnace', 'smoker', 'blast_furnace', 'brewing_stand', 'chest',
  'wheat', 'carrots', 'potatoes', 'beetroots'
]
const SURROUNDINGS_RADIUS = 16
const SURROUNDINGS_CACHE_MS = 5000
let cachedSurroundings = null
let cachedSurroundingsAt = 0

/** Cached — re-scanning ~30 block types across a 16-block radius on every single message would add real latency for little benefit, since terrain doesn't change that fast. */
function surroundingsContext (bot) {
  const now = Date.now()
  if (cachedSurroundings && now - cachedSurroundingsAt < SURROUNDINGS_CACHE_MS) return cachedSurroundings
  if (!bot.entity) return 'Surroundings: unknown right now.'

  const standingOn = bot.blockAt(bot.entity.position.offset(0, -1, 0))
  const counts = []
  for (const name of NOTABLE_BLOCKS) {
    const blockType = bot.registry.blocksByName[name]
    if (!blockType) continue
    const found = bot.findBlocks({ matching: blockType.id, maxDistance: SURROUNDINGS_RADIUS, count: 5 })
    if (found.length) counts.push(`${name}${found.length >= 5 ? '+' : ` x${found.length}`}`)
  }

  const result = `Standing on: ${standingOn?.name || 'unknown'}. Notable blocks within ${SURROUNDINGS_RADIUS} blocks: ${counts.length ? counts.join(', ') : 'nothing notable'}.`
  cachedSurroundings = result
  cachedSurroundingsAt = now
  return result
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
      name: 'goto_location',
      description: 'Walk to specific x/y/z coordinates — use this when given exact coordinates instead of a player or landmark name.',
      parameters: {
        type: 'object',
        properties: {
          x: { type: 'number' },
          y: { type: 'number' },
          z: { type: 'number' }
        },
        required: ['x', 'y', 'z']
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
      name: 'fish',
      description: 'Go fishing with a fishing rod from your inventory. Call repeatedly to keep fishing.',
      parameters: { type: 'object', properties: {}, required: [] }
    }
  },
  {
    type: 'function',
    function: {
      name: 'craft_item',
      description: 'Craft an item from materials in your inventory, using a nearby crafting table if the recipe needs one.',
      parameters: {
        type: 'object',
        properties: {
          item: { type: 'string', description: 'Item to craft, e.g. "stick" or "wooden pickaxe"' },
          count: { type: 'integer', description: 'How many to craft (default 1)' }
        },
        required: ['item']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'farm',
      description: 'Harvest fully grown crops near you and replant seeds where possible.',
      parameters: { type: 'object', properties: {}, required: [] }
    }
  },
  {
    type: 'function',
    function: {
      name: 'enter_boat',
      description: 'Place a boat from your inventory in the water ahead and get in.',
      parameters: { type: 'object', properties: {}, required: [] }
    }
  },
  {
    type: 'function',
    function: {
      name: 'exit_boat',
      description: 'Get out of the boat you are currently riding.',
      parameters: { type: 'object', properties: {}, required: [] }
    }
  },
  {
    type: 'function',
    function: {
      name: 'mine_block',
      description: 'Walk to the nearest block of the given type and mine it.',
      parameters: {
        type: 'object',
        properties: { block: { type: 'string', description: 'Block name, e.g. "iron ore" or "oak log"' } },
        required: ['block']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'smelt',
      description: 'Smelt/cook an item in a nearby furnace or smoker (fuel is added automatically if you have any).',
      parameters: {
        type: 'object',
        properties: { item: { type: 'string', description: 'Item to smelt, e.g. "raw iron" or "raw porkchop"' } },
        required: ['item']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'brew_potion',
      description: 'Start brewing a potion at a nearby brewing stand using water bottles and the given ingredient.',
      parameters: {
        type: 'object',
        properties: { ingredient: { type: 'string', description: 'Brewing ingredient, e.g. "nether wart" or "blaze powder"' } },
        required: ['ingredient']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'attack_nearby_hostile',
      description: 'Fight off the nearest hostile mob threatening you.',
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
        { role: 'system', content: await trustedPeopleContext(bot) },
        { role: 'system', content: surroundingsContext(bot) },
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
        { role: 'system', content: await trustedPeopleContext(bot) },
        { role: 'system', content: surroundingsContext(bot) },
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

/**
 * Ground-truth inventory workaround: this server's real Minecraft version
 * (26.2) doesn't have official mineflayer support, so the patch this bot
 * runs on clones minecraft-data from 26.1 and relabels it as 26.2. Real
 * 26.2's actual item registry doesn't match that patched table 1:1, so
 * mineflayer's own item-id decoding silently resolves items to the WRONG
 * name (confirmed live: a real cooked porkchop showed up client-side as an
 * "iron sword"). `bot.inventory.slots[]` still holds the real underlying
 * item object at the right protocol slot — only its `.name`/`.type` label
 * is wrong — so this sidesteps the bug by asking the SERVER (via BotInterop's
 * `/botinv <self> list`, read via Bukkit, immune to this client-side issue)
 * which Bukkit slot has real food, then grabs whatever object mineflayer has
 * sitting at the matching protocol slot — never trusting its name.
 */
function stripColorCodes (text) {
  return text.replace(/§./g, '')
}

function queryOwnInventoryText (bot, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const lines = []
    const onMessage = (jsonMsg) => {
      lines.push(stripColorCodes(jsonMsg.toString()))
    }
    bot.on('message', onMessage)
    bot.chat(`/botinv ${bot.username} list`)
    setTimeout(() => {
      bot.removeListener('message', onMessage)
      resolve(lines)
    }, timeoutMs)
  })
}

/** Same ground-truth pattern as queryOwnInventoryText, but for any container block (chest/barrel/etc.) via BotInterop's /whatsin — checking a chest's contents client-side would hit the same item-id corruption. */
function queryContainerText (bot, pos, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const lines = []
    const onMessage = (jsonMsg) => {
      lines.push(stripColorCodes(jsonMsg.toString()))
    }
    bot.on('message', onMessage)
    bot.chat(`/whatsin ${Math.floor(pos.x)} ${Math.floor(pos.y)} ${Math.floor(pos.z)}`)
    setTimeout(() => {
      bot.removeListener('message', onMessage)
      resolve(lines)
    }, timeoutMs)
  })
}

/**
 * Ground-truth position workaround: confirmed live that this bot's own
 * client-side entity tracking is unreliable on the unofficial 26.2 protocol
 * patch — a player only ~12 blocks away never appeared in bot.entities or
 * bot.players[x].entity at all (the same general class of bug as the
 * item-id mismatch, just affecting entity-spawn packets instead of item
 * decoding). Self-issues BotInterop's `/whereis <player>` (server-side,
 * Bukkit-authoritative) instead of trusting bot.entities for navigation.
 * Filters by the target's name in the reply so concurrent calls (e.g.
 * Promise.all over several friends) don't cross-match each other's replies.
 */
function queryPlayerPosition (bot, username, timeoutMs = 1500) {
  return new Promise((resolve) => {
    let result = null
    const onMessage = (jsonMsg) => {
      const text = stripColorCodes(jsonMsg.toString())
      const match = text.match(/^(.+)'s position: x=(-?[\d.]+), y=(-?[\d.]+), z=(-?[\d.]+), dimension=(.+)$/)
      if (match && match[1].toLowerCase() === username.toLowerCase()) {
        result = { x: Number(match[2]), y: Number(match[3]), z: Number(match[4]), dimension: match[5] }
      }
    }
    bot.on('message', onMessage)
    bot.chat(`/whereis ${username}`)
    setTimeout(() => {
      bot.removeListener('message', onMessage)
      resolve(result)
    }, timeoutMs)
  })
}

function bukkitMainSlotToProtocolSlot (bukkitSlot) {
  if (bukkitSlot >= 0 && bukkitSlot <= 8) return 36 + bukkitSlot // hotbar
  if (bukkitSlot >= 9 && bukkitSlot <= 35) return bukkitSlot // main inventory (same numbering)
  return null
}

/**
 * General ground-truth item finder: returns { item, realName, bukkitSlot }
 * for the first main-inventory item whose SERVER-VERIFIED name satisfies
 * `matchFn`. `item` is only safe to use as an opaque handle for equip/craft/
 * place calls — its own .name/.displayName are not trustworthy (see note
 * above) — `realName` (from the listing) is what to show the player or
 * reason about.
 */
async function findItemByRealName (bot, matchFn) {
  const lines = await queryOwnInventoryText(bot)
  for (const line of lines) {
    const match = line.match(/^\s*slot (\d+): (\d+)x (.+)$/)
    if (!match) continue
    const realName = match[3].trim()
    if (!matchFn(realName.toLowerCase().replace(/\s+/g, '_'))) continue
    const bukkitSlot = Number(match[1])
    const protocolSlot = bukkitMainSlotToProtocolSlot(bukkitSlot)
    const item = protocolSlot != null ? bot.inventory.slots[protocolSlot] : null
    if (item) return { item, realName, bukkitSlot }
  }
  return null
}

function findFoodItem (bot) {
  const foodNames = new Set((bot.registry?.foodsArray || []).map((f) => f.name))
  return findItemByRealName(bot, (name) => foodNames.has(name))
}

async function eatFood (bot, food) {
  await bot.equip(food, 'hand')
  await bot.consume()
}

const CHEST_SEARCH_RADIUS = 24

/** Returns { pos, slot } for the first food item found in a nearby chest/barrel, or null. Checking a chest's contents client-side would hit the same item-id corruption as everything else — uses /whatsin (ground truth) instead. */
async function findFoodInNearbyChests (bot) {
  const containerTypeIds = ['chest', 'trapped_chest', 'barrel']
    .map((n) => bot.registry.blocksByName[n]?.id).filter((id) => id != null)
  const positions = bot.findBlocks({ matching: (b) => containerTypeIds.includes(b.type), maxDistance: CHEST_SEARCH_RADIUS, count: 10 })
  const foodNames = new Set((bot.registry?.foodsArray || []).map((f) => f.name))

  for (const pos of positions) {
    const lines = await queryContainerText(bot, pos)
    for (const line of lines) {
      const match = line.match(/^\s*slot (\d+): (\d+)x (.+)$/)
      if (!match) continue
      const normalized = match[3].trim().toLowerCase().replace(/\s+/g, '_')
      if (foodNames.has(normalized)) return { pos, slot: Number(match[1]) }
    }
  }
  return null
}

/** Walks to the container, withdraws one food item from the ground-truth-verified slot, and eats it. */
async function eatFromChest (bot, pos, slot) {
  await bot.pathfinder.goto(new goals.GoalNear(pos.x, pos.y, pos.z, 2))
  const block = bot.blockAt(pos)
  const window = await bot.openContainer(block)
  try {
    const slotItem = window.slots[slot]
    if (!slotItem) return false
    // Opaque handle, same principle as findItemByRealName: use the type this
    // exact (ground-truth-verified) slot already reports, never a name-based
    // lookup through the corrupted local table.
    await bot.transfer({
      window, itemType: slotItem.type, metadata: slotItem.metadata, count: 1,
      sourceStart: 0, sourceEnd: window.inventoryStart, destStart: window.inventoryStart, destEnd: window.inventoryEnd
    })
  } finally {
    window.close()
  }
  const found = await findFoodItem(bot)
  if (!found) return false
  await eatFood(bot, found.item)
  return true
}

// bot.fish()'s bite detection depends on parsing world_particles packets —
// confirmed broken on this server (repeated PartialReadError decoding the
// Particle variant; real 26.2's packet structure doesn't match what this
// patched/26.1-based protocol schema expects, the same general class of bug
// as the item-id and entity-tracking issues found earlier). That leaves
// bot.fish() hanging forever on the very first cast, since the condition it
// waits for structurally can't ever fire.
//
// Real signal used instead: the fishing_bobber entity has its own `biting`
// metadata field (confirmed via bot.registry.entitiesByName.fishing_bobber
// — index 9 in its metadataKeys), delivered via entity_metadata packets.
// That's a different, much simpler field than the broken particle payload
// (a plain boolean, not a variant-typed value), and spawn_entity/basic
// entity_metadata haven't shown any decode errors in testing — only the
// particle-type payload has. This tracks the bobber's own spawn packet,
// then watches for its `biting` flag going true, and reels in immediately
// when it does, rather than guessing on a fixed timer.
const FISH_BOBBER_SPAWN_TIMEOUT_MS = 3000
const FISH_BITE_TIMEOUT_MS = 30000
const BOBBER_BITING_METADATA_KEY = 9

function trackBobberSpawn (bot, timeoutMs = FISH_BOBBER_SPAWN_TIMEOUT_MS) {
  return new Promise((resolve) => {
    const bobberTypeId = bot.registry.entitiesByName.fishing_bobber?.id
    let settled = false
    const onSpawn = (packet) => {
      if (packet.type !== bobberTypeId) return
      finish(packet.entityId)
    }
    const timeout = setTimeout(() => finish(null), timeoutMs)
    function finish (result) {
      if (settled) return
      settled = true
      bot._client.removeListener('spawn_entity', onSpawn)
      clearTimeout(timeout)
      resolve(result)
    }
    bot._client.on('spawn_entity', onSpawn)
  })
}

function waitForBite (bot, bobberEntityId, timeoutMs = FISH_BITE_TIMEOUT_MS) {
  return new Promise((resolve) => {
    let settled = false
    const onMetadata = (packet) => {
      if (packet.entityId !== bobberEntityId) return
      const bitingEntry = packet.metadata?.find((m) => m.key === BOBBER_BITING_METADATA_KEY)
      if (bitingEntry?.value) finish(true)
    }
    const timeout = setTimeout(() => finish(false), timeoutMs)
    function finish (result) {
      if (settled) return
      settled = true
      bot._client.removeListener('entity_metadata', onMetadata)
      clearTimeout(timeout)
      resolve(result)
    }
    bot._client.on('entity_metadata', onMetadata)
  })
}

/** Returns true if a bite was actually detected, false if it reeled in on a timeout/fallback instead. */
async function fishOnce (bot) {
  const bobberSpawned = trackBobberSpawn(bot)
  bot.activateItem() // cast
  const bobberId = await bobberSpawned
  if (bobberId == null) {
    // Couldn't identify the bobber (spawn packet never arrived/matched) —
    // fall back to a fixed wait rather than hanging forever.
    await new Promise((resolve) => setTimeout(resolve, 7000))
    bot.activateItem()
    return false
  }
  const bit = await waitForBite(bot, bobberId)
  bot.activateItem() // reel in — whether a real bite or the timeout fallback
  return bit
}


async function runTool (bot, equipHandler, toolName, toolArgs, sender, reply) {
  switch (toolName) {
    case 'come_here': {
      const pos = await queryPlayerPosition(bot, sender)
      if (!pos) { reply("I can't see you right now"); return }
      bot.pathfinder.setGoal(new goals.GoalNear(pos.x, pos.y, pos.z, 1))
      reply('on my way')
      return
    }
    case 'follow_player': {
      const pos = await queryPlayerPosition(bot, toolArgs.name)
      if (!pos) { reply(`I can't see ${toolArgs.name} right now`); return }
      startFollowing(bot, toolArgs.name)
      bot.pathfinder.setGoal(new goals.GoalNear(pos.x, pos.y, pos.z, 2))
      reply(`following ${toolArgs.name}`)
      return
    }
    case 'goto_location': {
      stopFollowing()
      try {
        await bot.pathfinder.goto(new goals.GoalNear(toolArgs.x, toolArgs.y, toolArgs.z, 1))
        reply('arrived')
      } catch (err) {
        reply(`couldn't get there: ${err.message}`)
      }
      return
    }
    case 'stop': {
      stopFollowing()
      fishingCancelled = true
      bot.pathfinder.setGoal(null)
      reply('stopping')
      return
    }
    case 'eat': {
      const found = await findFoodItem(bot)
      if (!found) { reply("I don't have any food on me"); return }
      try {
        await eatFood(bot, found.item)
        reply(`ate ${found.realName}`)
      } catch (err) {
        reply(`couldn't eat: ${err.message}`)
      }
      return
    }
    case 'fish': {
      // bot.fish() just activates the held item facing wherever the bot is
      // currently looking — without explicitly facing open water first it
      // can (and did, live) hook whoever's standing in front of it instead.
      const water = bot.findBlock({ matching: (b) => b.name === 'water', maxDistance: 5 })
      if (!water) { reply("I don't see open water close enough to fish in"); return }
      const rod = await findItemByRealName(bot, (name) => name === 'fishing_rod')
      if (!rod) { reply("I don't have a fishing rod"); return }
      try {
        await bot.equip(rod.item, 'hand')
        await bot.lookAt(water.position.offset(0.5, 0.5, 0.5))
        fishingCancelled = false
        let casts = 0
        let bites = 0
        while (!fishingCancelled && casts < FISH_MAX_CATCHES_PER_CALL) {
          if (await fishOnce(bot)) bites++
          casts++
        }
        reply(casts > 0 ? `done fishing for now — ${bites} bite${bites === 1 ? '' : 's'} out of ${casts} cast${casts === 1 ? '' : 's'}` : 'stopped fishing')
      } catch (err) {
        reply(`fishing didn't pan out: ${err.message}`)
      }
      return
    }
    case 'craft_item': {
      // Caveat: recipe matching and ingredient search both go through the
      // same locally-patched item table implicated in the eat/inventory bug
      // above. Self-consistent internally, but if the server's real recipe
      // validation disagrees with what this client thinks it's placing,
      // this can fail or (rarer) produce something other than intended.
      const normalized = toolArgs.item.trim().toLowerCase().replace(/\s+/g, '_')
      const itemData = bot.registry.itemsByName[normalized]
      if (!itemData) { reply(`I don't know what "${toolArgs.item}" is`); return }
      const count = toolArgs.count || 1
      const tableType = bot.registry.blocksByName.crafting_table?.id
      const craftingTable = tableType ? bot.findBlock({ matching: tableType, maxDistance: 4 }) : null
      const recipes = bot.recipesFor(itemData.id, null, 1, craftingTable)
      if (!recipes.length) {
        reply(`can't craft ${toolArgs.item} right now — missing materials${craftingTable ? '' : ' or a crafting table nearby'}`)
        return
      }
      try {
        await bot.craft(recipes[0], count, craftingTable)
        reply(`crafted ${count}x ${toolArgs.item}`)
      } catch (err) {
        reply(`couldn't craft that: ${err.message}`)
      }
      return
    }
    case 'farm': {
      const CROPS = [
        { block: 'wheat', maxAge: 7, seed: 'wheat_seeds' },
        { block: 'carrots', maxAge: 7, seed: 'carrot' },
        { block: 'potatoes', maxAge: 7, seed: 'potato' },
        { block: 'beetroots', maxAge: 3, seed: 'beetroot_seeds' }
      ]
      let harvested = 0
      for (const crop of CROPS) {
        const blockType = bot.registry.blocksByName[crop.block]
        if (!blockType) continue
        for (let i = 0; i < 20; i++) {
          const found = bot.findBlock({
            matching: (b) => b.type === blockType.id && b.getProperties().age === String(crop.maxAge),
            maxDistance: 24
          })
          if (!found) break
          try {
            await bot.pathfinder.goto(new goals.GoalNear(found.position.x, found.position.y, found.position.z, 1))
            const pos = found.position.clone()
            await bot.dig(found)
            harvested++
            const seedGT = await findItemByRealName(bot, (name) => name === crop.seed)
            const below = bot.blockAt(pos.offset(0, -1, 0))
            if (seedGT && below) {
              await bot.equip(seedGT.item, 'hand')
              await bot.placeBlock(below, new Vec3(0, 1, 0))
            }
          } catch (err) {
            console.log(`[ai] farm error: ${err.stack || err}`)
            break
          }
        }
      }
      reply(harvested > 0 ? `harvested ${harvested} crop${harvested === 1 ? '' : 's'}` : 'no ripe crops nearby')
      return
    }
    case 'enter_boat': {
      const boat = await findItemByRealName(bot, (name) => name.endsWith('_boat') || name === 'boat')
      if (!boat) { reply("I don't have a boat"); return }
      const water = bot.findBlock({ matching: (b) => b.name === 'water', maxDistance: 4 })
      if (!water) { reply("I don't see water nearby to put a boat in"); return }
      try {
        await bot.equip(boat.item, 'hand')
        await bot.placeEntity(water, new Vec3(0, 1, 0))
        const boatEntity = Object.values(bot.entities)
          .find((e) => e.name === 'boat' && e.position.distanceTo(water.position) < 2)
        if (boatEntity) await bot.mount(boatEntity)
        reply('hopped in the boat')
      } catch (err) {
        reply(`couldn't get in a boat: ${err.message}`)
      }
      return
    }
    case 'exit_boat': {
      if (!bot.vehicle) { reply("I'm not in a boat"); return }
      bot.dismount()
      reply('got out of the boat')
      return
    }
    case 'mine_block': {
      const blockType = bot.registry.blocksByName[toolArgs.block.trim().toLowerCase().replace(/\s+/g, '_')]
      if (!blockType) { reply(`I don't know what block "${toolArgs.block}" is`); return }
      const target = bot.findBlock({ matching: blockType.id, maxDistance: 32 })
      if (!target) { reply(`no ${toolArgs.block} nearby`); return }
      try {
        await bot.pathfinder.goto(new goals.GoalNear(target.position.x, target.position.y, target.position.z, 1))
        await bot.dig(target)
        reply(`mined ${toolArgs.block}`)
      } catch (err) {
        reply(`couldn't mine that: ${err.message}`)
      }
      return
    }
    case 'smelt': {
      // Works for furnace, smoker, or blast_furnace — same window API. Smoker
      // is the fast option for cooking food specifically; furnace for
      // ores/general smelting. Caveat: ingredient/fuel matching by name goes
      // through the same locally-patched item table as craft_item above.
      const furnaceTypes = ['furnace', 'smoker', 'blast_furnace']
        .map((n) => bot.registry.blocksByName[n]?.id).filter((id) => id != null)
      const furnaceBlock = bot.findBlock({ matching: (b) => furnaceTypes.includes(b.type), maxDistance: 8 })
      if (!furnaceBlock) { reply("I don't see a furnace/smoker nearby"); return }
      const inputGT = await findItemByRealName(bot, (name) => name === toolArgs.item.trim().toLowerCase().replace(/\s+/g, '_'))
      if (!inputGT) { reply(`I don't have "${toolArgs.item}" to smelt`); return }
      const fuelGT = await findItemByRealName(bot, (name) => FUEL_NAMES.has(name))
      try {
        await bot.pathfinder.goto(new goals.GoalNear(furnaceBlock.position.x, furnaceBlock.position.y, furnaceBlock.position.z, 2))
        const furnace = await bot.openFurnace(furnaceBlock)
        if (fuelGT) await furnace.putFuel(fuelGT.item.type, null, fuelGT.item.count)
        await furnace.putInput(inputGT.item.type, null, inputGT.item.count)
        reply(`put ${toolArgs.item} in to smelt — I'll grab it once it's done`)
        furnace.on('update', () => {
          if (furnace.outputItem() && furnace.outputItem().count > 0) {
            furnace.takeOutput().then(() => furnace.close()).catch(() => {})
          }
        })
      } catch (err) {
        reply(`couldn't smelt that: ${err.message}`)
      }
      return
    }
    case 'brew_potion': {
      const standType = bot.registry.blocksByName.brewing_stand?.id
      const stand = standType ? bot.findBlock({ matching: standType, maxDistance: 8 }) : null
      if (!stand) { reply("I don't see a brewing stand nearby"); return }

      const ingredientName = toolArgs.ingredient.trim().toLowerCase().replace(/\s+/g, '_')
      const ingredientGT = await findItemByRealName(bot, (name) => name === ingredientName)
      if (!ingredientGT) { reply(`I don't have "${toolArgs.ingredient}" to brew with`); return }
      const bottleGT = await findItemByRealName(bot, (name) => name === 'potion' || name === 'glass_bottle')
      if (!bottleGT) { reply('I need water bottles to brew with'); return }
      const fuelGT = await findItemByRealName(bot, (name) => name === 'blaze_powder')

      try {
        await bot.pathfinder.goto(new goals.GoalNear(stand.position.x, stand.position.y, stand.position.z, 2))
        const window = await bot.openBlock(stand)
        if (fuelGT) {
          await bot.transfer({
            window, itemType: fuelGT.item.type, metadata: null, count: 1,
            sourceStart: window.inventoryStart, sourceEnd: window.inventoryEnd, destStart: 4, destEnd: 5
          })
        }
        await bot.transfer({
          window, itemType: bottleGT.item.type, metadata: null, count: Math.min(3, bottleGT.item.count),
          sourceStart: window.inventoryStart, sourceEnd: window.inventoryEnd, destStart: 0, destEnd: 3
        })
        await bot.transfer({
          window, itemType: ingredientGT.item.type, metadata: null, count: 1,
          sourceStart: window.inventoryStart, sourceEnd: window.inventoryEnd, destStart: 3, destEnd: 4
        })
        window.close()
        reply(`brewing with ${toolArgs.ingredient} now — come back in a bit for the potions`)
      } catch (err) {
        reply(`couldn't start brewing: ${err.message}`)
      }
      return
    }
    case 'attack_nearby_hostile': {
      const hostile = Object.values(bot.entities)
        .filter((e) => e.type === 'hostile' || HOSTILE_MOB_NAMES.has(e.name))
        .sort((a, b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position))[0]
      if (!hostile) { reply('no threats nearby'); return }
      try {
        const weapon = await findItemByRealName(bot, (name) => WEAPON_NAMES.has(name))
        if (weapon) await bot.equip(weapon.item, 'hand')
        await bot.pathfinder.goto(new goals.GoalFollow(hostile, 2))
        bot.pathfinder.setGoal(null)
        await bot.attack(hostile)
        reply(`fighting off ${hostile.name}`)
      } catch (err) {
        reply(`couldn't fight it: ${err.message}`)
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
      const pos = await queryPlayerPosition(bot, toolArgs.name)
      if (!pos) { reply(`I can't see ${toolArgs.name} right now`); return }
      const item = findItem(bot.inventory, toolArgs.item)
      if (!item) {
        const have = bot.inventory.items().map((i) => i.name).join(', ') || '(empty)'
        reply(`I don't have "${toolArgs.item}". Have: ${have}`)
        return
      }
      try {
        await bot.pathfinder.goto(new goals.GoalNear(pos.x, pos.y, pos.z, 2))
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

  const toolCalls = assistantMessage.tool_calls
  if (!toolCalls?.length) {
    if (assistantMessage.content) reply(stripNonLatinScript(assistantMessage.content))
    return
  }

  // A single reply can ask for several actions at once (e.g. "go there, then
  // fish") — the model correctly returns multiple tool_calls for that, so
  // run each in turn rather than silently dropping everything after the
  // first (confirmed live: goto_location + fish both came back for one
  // message, but only goto_location was ever executed before this fix).
  for (const toolCall of toolCalls) {
    await handleToolCall(bot, equipHandler, sender, message, assistantMessage, toolCall, reply, ownerReplyFn)
  }
}

async function handleToolCall (bot, equipHandler, sender, message, assistantMessage, toolCall, reply, ownerReplyFn) {
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

/**
 * As soon as hunger drops below 70% (14/20): eat from inventory if possible;
 * failing that, check nearby chests/barrels and eat from one if it has food;
 * only if neither has any does it bother the owner — and only once per
 * cooldown, not on every tick below the threshold.
 */
async function maybeEatOrAlert (bot, notifyOwner) {
  if (autoEating) return
  if (typeof bot.food !== 'number' || bot.food > AUTO_EAT_THRESHOLD) return

  autoEating = true
  try {
    const found = await findFoodItem(bot)
    if (found) {
      await eatFood(bot, found.item)
      return
    }

    const chestFood = await findFoodInNearbyChests(bot)
    if (chestFood) {
      const ate = await eatFromChest(bot, chestFood.pos, chestFood.slot)
      if (ate) return
    }

    const now = Date.now()
    if (now - lastLowFoodAlert < LOW_FOOD_ALERT_COOLDOWN_MS) return
    lastLowFoodAlert = now
    notifyOwner(`I'm hungry (${bot.food}/20) with no food in my inventory or nearby chests — I need to gather some.`)
  } catch (err) {
    console.log(`[ai] auto-eat error: ${err.stack || err}`)
  } finally {
    autoEating = false
  }
}

async function autoDefend (bot) {
  if (autoDefending) return
  if (!bot.entity) return
  const hostile = Object.values(bot.entities)
    .filter((e) => (e.type === 'hostile' || HOSTILE_MOB_NAMES.has(e.name)) && e.position.distanceTo(bot.entity.position) < DEFEND_RADIUS)
    .sort((a, b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position))[0]
  if (!hostile) return

  autoDefending = true
  try {
    const weapon = await findItemByRealName(bot, (name) => WEAPON_NAMES.has(name))
    if (weapon) await bot.equip(weapon.item, 'hand')
    await bot.pathfinder.goto(new goals.GoalFollow(hostile, 2))
    bot.pathfinder.setGoal(null)
    if (hostile.position.distanceTo(bot.entity.position) < 3.5) await bot.attack(hostile)
  } catch (err) {
    console.log(`[ai] autoDefend error: ${err.stack || err}`)
  } finally {
    autoDefending = false
  }
}

/** Call once after the bot spawns. Runs on every health/food update (mineflayer's 'health' event covers both) and periodically checks for nearby threats. */
function attachAutoSurvival (bot, notifyOwner) {
  bot.on('health', () => {
    maybeEatOrAlert(bot, notifyOwner).catch((err) => console.log(`[ai] maybeEatOrAlert error: ${err.stack || err}`))
  })
  let tick = 0
  bot.on('physicsTick', () => {
    tick++
    if (tick % DEFEND_CHECK_EVERY_TICKS !== 0) return
    autoDefend(bot).catch((err) => console.log(`[ai] autoDefend error: ${err.stack || err}`))
  })
}

module.exports = { handleAiMessage, attachAutoSurvival }
