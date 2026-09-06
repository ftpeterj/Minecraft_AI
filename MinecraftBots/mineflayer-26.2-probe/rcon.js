'use strict'
/**
 * Minimal Source/Minecraft RCON client (just enough to run one command and
 * read the response) — used so the bot can privately notify its owner via
 * `tellraw` from the server console, bypassing signed/secure chat entirely.
 * Kept dependency-free rather than pulling in an rcon package for one call site.
 */
const net = require('net')

const TYPE_AUTH = 3
const TYPE_AUTH_RESPONSE = 2
const TYPE_EXEC_COMMAND = 2
const TYPE_RESPONSE_VALUE = 0

function encodePacket (id, type, body) {
  const bodyBuf = Buffer.from(body + '\0', 'utf8')
  const size = 4 + 4 + bodyBuf.length + 1
  const buf = Buffer.alloc(4 + size)
  buf.writeInt32LE(size, 0)
  buf.writeInt32LE(id, 4)
  buf.writeInt32LE(type, 8)
  bodyBuf.copy(buf, 12)
  buf.writeUInt8(0, buf.length - 1)
  return buf
}

function rconCommand (host, port, password, command, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port })
    let buffer = Buffer.alloc(0)
    let authed = false
    let settled = false

    const finish = (fn, arg) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      socket.destroy()
      fn(arg)
    }

    const timer = setTimeout(() => finish(reject, new Error('rcon timeout')), timeoutMs)

    socket.on('connect', () => {
      socket.write(encodePacket(1, TYPE_AUTH, password))
    })

    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk])
      while (buffer.length >= 4) {
        const size = buffer.readInt32LE(0)
        if (buffer.length < 4 + size) break
        const packet = buffer.subarray(0, 4 + size)
        buffer = buffer.subarray(4 + size)
        const id = packet.readInt32LE(4)
        const type = packet.readInt32LE(8)
        const body = packet.subarray(12, packet.length - 2).toString('utf8')

        if (!authed) {
          if (type === TYPE_AUTH_RESPONSE) {
            if (id === -1) { finish(reject, new Error('rcon auth failed')); return }
            authed = true
            socket.write(encodePacket(2, TYPE_EXEC_COMMAND, command))
          }
          continue
        }
        if (type === TYPE_RESPONSE_VALUE && id === 2) {
          finish(resolve, body)
        }
      }
    })

    socket.on('error', (err) => finish(reject, err))
  })
}

module.exports = { rconCommand }
