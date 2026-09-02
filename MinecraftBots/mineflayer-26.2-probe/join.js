'use strict'
/**
 * Unofficial 26.2 join probe.
 * Official Mineflayer only lists through 26.1. This clones 26.1 protocol
 * data as 26.2 (protocol 776) and relies on prismarine-chunk 1.41+ fluidCount.
 *
 * Usage:
 *   npm install
 *   node join.js
 *
 * Microsoft device-code login will print a URL + code. Use an account that
 * is already on the Creative whitelist (KingOfThisHouse or BigMuddyPuddle),
 * or whitelist the new name after the first kick.
 */
const mineflayer = require('mineflayer')

const HOST = process.env.MC_HOST || 'minecraft.local'
const PORT = Number(process.env.MC_PORT || 25565)
const VERSION = process.env.MC_VERSION || '26.2'
const AUTH = process.env.MC_AUTH || 'microsoft'
const USERNAME = process.env.MC_USER || 'MineflayerProbe'

console.log(`[probe] connecting ${HOST}:${PORT} version=${VERSION} auth=${AUTH}`)

const bot = mineflayer.createBot({
  host: HOST,
  port: PORT,
  version: VERSION,
  username: USERNAME,
  auth: AUTH,
  hideErrors: false
})

function stamp () {
  return new Date().toISOString()
}

bot.on('login', () => {
  console.log(`[${stamp()}] login username=${bot.username} uuid=${bot.player?.uuid || '?'}`)
})

bot.on('spawn', () => {
  const p = bot.entity?.position
  console.log(`[${stamp()}] SPAWN at ${p?.x?.toFixed?.(1)},${p?.y?.toFixed?.(1)},${p?.z?.toFixed?.(1)} gameMode=${bot.game?.gameMode} dim=${bot.game?.dimension}`)
  try {
    const block = bot.blockAt(bot.entity.position.offset(0, -1, 0))
    console.log(`[${stamp()}] block under feet: ${block?.name || 'unknown'}`)
  } catch (e) {
    console.log(`[${stamp()}] blockAt failed: ${e.message}`)
  }
  setTimeout(() => {
    try { bot.chat('mineflayer 26.2 probe: spawned') } catch {}
  }, 1500)
  setTimeout(() => {
    console.log(`[${stamp()}] probe ok — disconnecting`)
    bot.quit('probe done')
    setTimeout(() => process.exit(0), 1000)
  }, 8000)
})

bot.on('kicked', (reason) => {
  console.log(`[${stamp()}] KICKED: ${typeof reason === 'string' ? reason : JSON.stringify(reason)}`)
})

bot.on('error', (err) => {
  console.log(`[${stamp()}] ERROR: ${err.stack || err}`)
})

bot.on('end', (reason) => {
  console.log(`[${stamp()}] END: ${reason}`)
})

bot._client?.on('error', (err) => {
  console.log(`[${stamp()}] client error: ${err.message}`)
})

process.on('uncaughtException', (err) => {
  console.log(`[${stamp()}] uncaught: ${err.stack || err}`)
  setTimeout(() => process.exit(1), 500)
})
