'use strict'
/**
 * Persistent mineflayer presence for survival (Paper 26.2, unofficial patch — see README).
 * Connects, stays logged in, auto-reconnects on kick/disconnect/error. No behavior beyond that.
 *
 * Usage:
 *   node persistent-bot.js
 *
 * Env overrides: MC_HOST, MC_PORT, MC_VERSION, MC_AUTH, MC_USER
 * Ctrl+C to quit cleanly.
 */
const mineflayer = require('mineflayer')
const { pathfinder, Movements } = require('mineflayer-pathfinder')
const { handleAiMessage } = require('./ai')

const HOST = process.env.MC_HOST || 'minecraft.local'
const PORT = Number(process.env.MC_PORT || 25566)
const VERSION = process.env.MC_VERSION || '26.2'
const AUTH = process.env.MC_AUTH || 'microsoft'
const USERNAME = process.env.MC_USER || 'bloodypuddlekos'
const OWNER_DISPLAY = process.env.BOT_OWNER || 'KingOfThisHouse'
const NEARBY_CHAT_RADIUS = Number(process.env.NEARBY_CHAT_RADIUS || 3)

const MIN_RECONNECT_MS = 5000
const MAX_RECONNECT_MS = 5 * 60 * 1000

let reconnectDelay = MIN_RECONNECT_MS
let shuttingDown = false
let bot = null

function stamp () {
  return new Date().toISOString()
}

function log (msg) {
  console.log(`[${stamp()}] ${msg}`)
}

function destinationFor (itemName, forceOffhand) {
  if (forceOffhand) return 'off-hand'
  if (itemName.endsWith('_helmet') || itemName === 'turtle_helmet') return 'head'
  if (itemName.endsWith('_chestplate') || itemName === 'elytra') return 'torso'
  if (itemName.endsWith('_leggings')) return 'legs'
  if (itemName.endsWith('_boots')) return 'feet'
  return 'hand'
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

async function handleEquipCommand (args, reply) {
  let words = args.slice()
  let forceOffhand = false
  const last = words[words.length - 1]?.toLowerCase()
  if (last === 'offhand' || last === 'off-hand') {
    forceOffhand = true
    words = words.slice(0, -1)
  }
  const query = words.join(' ')
  if (!query) {
    reply('usage: equip <item name> [offhand]')
    return
  }

  const item = findItem(bot.inventory, query)
  if (!item) {
    const have = bot.inventory.items().map((i) => i.name).join(', ') || '(empty)'
    reply(`no "${query}" in inventory. Have: ${have}`)
    return
  }

  const destination = destinationFor(item.name, forceOffhand)
  try {
    await bot.equip(item.type, destination)
    reply(`equipped ${item.displayName || item.name} (${destination})`)
  } catch (err) {
    reply(`couldn't equip ${item.displayName || item.name}: ${err.message}`)
  }
}

function durabilityLine (label, item) {
  if (!item || !item.maxDurability) return null
  const remaining = item.maxDurability - (item.durabilityUsed || 0)
  const pct = Math.round((remaining / item.maxDurability) * 100)
  return `${label} ${pct}%`
}

function handleDurabilityCommand (reply) {
  const slots = bot.inventory.slots
  const lines = [
    durabilityLine('helmet', slots[5]),
    durabilityLine('chest', slots[6]),
    durabilityLine('legs', slots[7]),
    durabilityLine('boots', slots[8]),
    durabilityLine('hand', bot.heldItem),
    durabilityLine('offhand', slots[45])
  ].filter(Boolean)
  reply(lines.length ? lines.join(', ') : 'nothing worn/held with durability')
}

function handleChatLine (username, message, reply) {
  if (username === bot.username) return
  const parts = message.trim().split(/\s+/)
  const cmd = parts[0]?.toLowerCase()
  if (cmd === 'equip') {
    handleEquipCommand(parts.slice(1), reply).catch((err) => log(`equip handler error: ${err.stack || err}`))
  } else if (cmd === 'durability') {
    handleDurabilityCommand(reply)
  } else {
    const ownerReply = (msg) => { try { bot.whisper(OWNER_DISPLAY, msg) } catch {} }
    handleAiMessage(bot, handleEquipCommand, username, message, reply, ownerReply)
      .catch((err) => log(`ai handler error: ${err.stack || err}`))
  }
}

function scheduleReconnect (reason) {
  if (shuttingDown) return
  log(`reconnecting in ${Math.round(reconnectDelay / 1000)}s (${reason})`)
  setTimeout(connect, reconnectDelay)
  reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_MS)
}

function connect () {
  log(`connecting ${HOST}:${PORT} version=${VERSION} auth=${AUTH} user=${USERNAME}`)

  bot = mineflayer.createBot({
    host: HOST,
    port: PORT,
    version: VERSION,
    username: USERNAME,
    auth: AUTH,
    hideErrors: false
  })
  bot.loadPlugin(pathfinder)

  bot.on('login', () => {
    log(`login username=${bot.username}`)
  })

  bot.on('spawn', () => {
    reconnectDelay = MIN_RECONNECT_MS
    const p = bot.entity?.position
    log(`SPAWN at ${p?.x?.toFixed?.(1)},${p?.y?.toFixed?.(1)},${p?.z?.toFixed?.(1)} gameMode=${bot.game?.gameMode} dim=${bot.game?.dimension}`)
    try {
      bot.pathfinder.setMovements(new Movements(bot))
    } catch (err) {
      log(`pathfinder movements setup failed: ${err.stack || err}`)
    }
  })

  bot.on('whisper', (username, message) => {
    handleChatLine(username, message, (msg) => bot.whisper(username, msg))
  })

  bot.on('chat', (username, message) => {
    const prefix = bot.username.toLowerCase() + ' '
    if (message.toLowerCase().startsWith(prefix)) {
      handleChatLine(username, message.slice(prefix.length), (msg) => bot.chat(msg))
      return
    }
    // No name prefix, but if they're standing close by, assume they're talking to us.
    const entity = bot.players[username]?.entity
    if (entity && bot.entity && entity.position.distanceTo(bot.entity.position) <= NEARBY_CHAT_RADIUS) {
      handleChatLine(username, message, (msg) => bot.chat(msg))
    }
  })

  bot.on('kicked', (reason) => {
    log(`KICKED: ${typeof reason === 'string' ? reason : JSON.stringify(reason)}`)
  })

  bot.on('error', (err) => {
    log(`ERROR: ${err.stack || err}`)
  })

  bot.on('end', (reason) => {
    log(`END: ${reason}`)
    scheduleReconnect(`end: ${reason}`)
  })
}

process.on('SIGINT', () => {
  shuttingDown = true
  log('shutting down (SIGINT)')
  try { bot?.quit('shutting down') } catch {}
  setTimeout(() => process.exit(0), 500)
})

connect()
