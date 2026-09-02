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

const HOST = process.env.MC_HOST || 'minecraft.local'
const PORT = Number(process.env.MC_PORT || 25566)
const VERSION = process.env.MC_VERSION || '26.2'
const AUTH = process.env.MC_AUTH || 'microsoft'
const USERNAME = process.env.MC_USER || 'bloodypuddlekos'

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

  bot.on('login', () => {
    log(`login username=${bot.username}`)
  })

  bot.on('spawn', () => {
    reconnectDelay = MIN_RECONNECT_MS
    const p = bot.entity?.position
    log(`SPAWN at ${p?.x?.toFixed?.(1)},${p?.y?.toFixed?.(1)},${p?.z?.toFixed?.(1)} gameMode=${bot.game?.gameMode} dim=${bot.game?.dimension}`)
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
