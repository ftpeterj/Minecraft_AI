'use strict'
const fs = require('fs')
const path = require('path')

function findMcDataRoot () {
  const candidates = [
    path.join(__dirname, 'node_modules', 'minecraft-data', 'minecraft-data'),
    path.join(__dirname, 'node_modules', 'mineflayer', 'node_modules', 'minecraft-data', 'minecraft-data')
  ]
  for (const c of candidates) {
    if (fs.existsSync(path.join(c, 'data', 'pc', 'common', 'versions.json'))) return c
  }
  throw new Error('minecraft-data not found; run npm install first')
}

const root = findMcDataRoot()
const pc = path.join(root, 'data', 'pc')
const srcDir = path.join(pc, '26.1')
const dstDir = path.join(pc, '26.2')
if (!fs.existsSync(srcDir)) throw new Error('pc/26.1 missing in ' + root)

fs.cpSync(srcDir, dstDir, { recursive: true })

const versionPath = path.join(dstDir, 'version.json')
const version = JSON.parse(fs.readFileSync(versionPath, 'utf8'))
version.version = 776
version.minecraftVersion = '26.2'
version.majorVersion = '26.2'
fs.writeFileSync(versionPath, JSON.stringify(version, null, 2) + '\n')

const versionsPath = path.join(pc, 'common', 'versions.json')
const versions = JSON.parse(fs.readFileSync(versionsPath, 'utf8'))
if (!versions.includes('26.2')) {
  versions.push('26.2')
  fs.writeFileSync(versionsPath, JSON.stringify(versions, null, 2) + '\n')
}

const protoVersPath = path.join(pc, 'common', 'protocolVersions.json')
const protoVers = JSON.parse(fs.readFileSync(protoVersPath, 'utf8'))
if (!protoVers.some(v => v.minecraftVersion === '26.2')) {
  protoVers.unshift({
    minecraftVersion: '26.2',
    version: 776,
    dataVersion: 4903,
    usesNetty: true,
    majorVersion: '26.2',
    releaseType: 'release'
  })
  fs.writeFileSync(protoVersPath, JSON.stringify(protoVers, null, 2) + '\n')
}

const dataPathsPath = path.join(root, 'data', 'dataPaths.json')
const dataPaths = JSON.parse(fs.readFileSync(dataPathsPath, 'utf8'))
if (!dataPaths.pc['26.2']) {
  const src = JSON.parse(JSON.stringify(dataPaths.pc['26.1']))
  for (const [k, v] of Object.entries(src)) {
    if (typeof v === 'string' && v === 'pc/26.1') src[k] = 'pc/26.2'
  }
  src.version = 'pc/26.2'
  src.protocol = 'pc/26.2'
  dataPaths.pc['26.2'] = src
  fs.writeFileSync(dataPathsPath, JSON.stringify(dataPaths, null, 2) + '\n')
}

const gen = path.join(root, '..', 'bin', 'generate_data.js')
if (fs.existsSync(gen)) {
  require('child_process').execFileSync(process.execPath, [gen], {
    cwd: path.join(root, '..'),
    stdio: 'inherit'
  })
} else {
  console.warn('generate_data.js not found; data.js may still lack 26.2')
}

function patchFile (filePath, find, replace, label) {
  if (!fs.existsSync(filePath)) return
  const src = fs.readFileSync(filePath, 'utf8')
  if (src.includes(replace)) return
  if (!src.includes(find)) {
    console.warn('Skip (pattern missing):', label, filePath)
    return
  }
  fs.writeFileSync(filePath, src.replace(find, replace))
  console.log('Patched', label)
}

const mfVersion = path.join(__dirname, 'node_modules', 'mineflayer', 'lib', 'version.js')
patchFile(mfVersion, "'26.1']", "'26.1', '26.2']", 'mineflayer/lib/version.js testedVersions += 26.2')

const mcProtoVersion = path.join(__dirname, 'node_modules', 'minecraft-protocol', 'src', 'version.js')
patchFile(mcProtoVersion, "'26.1']", "'26.1', '26.2']", 'minecraft-protocol supportedVersions += 26.2')

const chunkIndex = path.join(__dirname, 'node_modules', 'prismarine-chunk', 'src', 'index.js')
patchFile(
  chunkIndex,
  "    26.1: require('./pc/1.18/chunk')",
  "    26.1: require('./pc/1.18/chunk'),\n    26.2: require('./pc/1.18/chunk')",
  'prismarine-chunk 26.2 -> 1.18 chunk'
)

const physicsFeatures = path.join(__dirname, 'node_modules', 'prismarine-physics', 'lib', 'features.json')
if (fs.existsSync(physicsFeatures)) {
  let src = fs.readFileSync(physicsFeatures, 'utf8')
  if (!src.includes('"26.2"')) {
    src = src.replaceAll('"26.1"]', '"26.1", "26.2"]')
    fs.writeFileSync(physicsFeatures, src)
    console.log('Patched prismarine-physics features.json += 26.2')
  }
}

console.log('Patched minecraft-data: 26.2 -> protocol 776 (cloned from 26.1) at', root)
