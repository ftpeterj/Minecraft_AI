#!/bin/bash
set -euo pipefail
LAN_IP=$(hostname -I | awk '{print $1}')
PROMPT='{"text":"NMinimap HUD shader pack (required). Turn OFF Kappa/Iris."}'

rcon() {
  local props="$1"
  shift
  local port pw
  port=$(grep '^rcon.port=' "$props" | cut -d= -f2)
  pw=$(grep '^rcon.password=' "$props" | cut -d= -f2-)
  mcrcon -H 127.0.0.1 -P "$port" -p "$pw" "$@"
}

pin_26_2_shaders() {
  local rp="$1"
  mkdir -p "$rp/assets/minecraft/shaders/core"
  cp -f "$rp/nminimap_26_2/assets/minecraft/shaders/core/text.vsh" \
        "$rp/assets/minecraft/shaders/core/text.vsh"
  cp -f "$rp/nminimap_26_2/assets/minecraft/shaders/core/text.fsh" \
        "$rp/assets/minecraft/shaders/core/text.fsh"
  echo "pinned 26.2 shaders in $rp"
}

pin_26_2_shaders /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/resourcepack
pin_26_2_shaders /home/minecraft/multicraft/servers/survival/plugins/NMinimap/resourcepack
chown -R mc4:mc4 /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/resourcepack/assets/minecraft
chown -R mc5:mc5 /home/minecraft/multicraft/servers/survival/plugins/NMinimap/resourcepack/assets/minecraft

echo 'rebuild packs via reload'
rcon /home/minecraft/multicraft/servers/Creative/server.properties 'mm admin reload' || true
rcon /home/minecraft/multicraft/servers/survival/server.properties 'mm admin reload' || true
sleep 3

# Unique filename so Minecraft clients that cached the old URL re-download
for root_owner in "Creative mc4 8100" "survival mc5 8101"; do
  set -- $root_owner
  root=/home/minecraft/multicraft/servers/$1
  owner=$2
  port=$3
  src="$root/plugins/NMinimap/built-pack.zip"
  dest="$root/bluemap/web/nminimap-hud.zip"
  install -o "$owner" -g "$owner" -m 644 "$src" "$dest"
  # keep old name too
  install -o "$owner" -g "$owner" -m 644 "$src" "$root/bluemap/web/nminimap.zip"
  echo "$1 sha $(sha1sum "$dest")"
done

CSHA=$(sha1sum /home/minecraft/multicraft/servers/Creative/bluemap/web/nminimap-hud.zip | awk '{print $1}')
SSHA=$(sha1sum /home/minecraft/multicraft/servers/survival/bluemap/web/nminimap-hud.zip | awk '{print $1}')
CID=$(cat /proc/sys/kernel/random/uuid)
SID=$(cat /proc/sys/kernel/random/uuid)

python3 - "$CSHA" "$SSHA" "$CID" "$SID" "$LAN_IP" "$PROMPT" <<'PY'
from pathlib import Path
import sys
csha, ssha, cid, sid, ip, prompt = sys.argv[1:7]

def set_props(path: Path, updates: dict) -> None:
    lines = path.read_text(encoding='utf-8').splitlines()
    seen=set(); out=[]
    for line in lines:
        key = line.split('=',1)[0] if '=' in line else ''
        if key in updates:
            out.append(f'{key}={updates[key]}'); seen.add(key)
        else:
            out.append(line)
    for k,v in updates.items():
        if k not in seen:
            out.append(f'{k}={v}')
    path.write_text('\n'.join(out)+'\n', encoding='utf-8')
    print('wrote', path)

set_props(Path('/home/minecraft/multicraft/servers/Creative/server.properties'), {
    'resource-pack': f'http://{ip}:8100/nminimap-hud.zip',
    'resource-pack-sha1': csha,
    'resource-pack-id': cid,
    'resource-pack-prompt': prompt,
    'require-resource-pack': 'true',
})
set_props(Path('/home/minecraft/multicraft/servers/survival/server.properties'), {
    'resource-pack': f'http://{ip}:8101/nminimap-hud.zip',
    'resource-pack-sha1': ssha,
    'resource-pack-id': sid,
    'resource-pack-prompt': prompt,
    'require-resource-pack': 'true',
})
PY

echo 'Stopping servers so the required pack is live on next join'
rcon /home/minecraft/multicraft/servers/survival/server.properties 'kick KingOfThisHouse Relog and ACCEPT the NMinimap pack. Kappa/Iris must be OFF.' || true
rcon /home/minecraft/multicraft/servers/Creative/server.properties stop || true
rcon /home/minecraft/multicraft/servers/survival/server.properties stop || true

for i in $(seq 1 30); do
  c=$(pgrep -u mc4 -f paper-26.2-121.jar || true)
  s=$(pgrep -u mc5 -f paper-26.2-121.jar || true)
  echo "dying t=$i mc4=${c:-none} mc5=${s:-none}"
  if [ -z "$c" ] && [ -z "$s" ]; then break; fi
  sleep 1
done

# restamp after stop (bukkit may rewrite props)
python3 - "$CSHA" "$SSHA" "$CID" "$SID" "$LAN_IP" "$PROMPT" <<'PY'
from pathlib import Path
import sys
csha, ssha, cid, sid, ip, prompt = sys.argv[1:7]

def set_props(path: Path, updates: dict) -> None:
    lines = path.read_text(encoding='utf-8').splitlines()
    seen=set(); out=[]
    for line in lines:
        key = line.split('=',1)[0] if '=' in line else ''
        if key in updates:
            out.append(f'{key}={updates[key]}'); seen.add(key)
        else:
            out.append(line)
    for k,v in updates.items():
        if k not in seen:
            out.append(f'{k}={v}')
    path.write_text('\n'.join(out)+'\n', encoding='utf-8')

set_props(Path('/home/minecraft/multicraft/servers/Creative/server.properties'), {
    'resource-pack': f'http://{ip}:8100/nminimap-hud.zip',
    'resource-pack-sha1': csha,
    'resource-pack-id': cid,
    'resource-pack-prompt': prompt,
    'require-resource-pack': 'true',
})
set_props(Path('/home/minecraft/multicraft/servers/survival/server.properties'), {
    'resource-pack': f'http://{ip}:8101/nminimap-hud.zip',
    'resource-pack-sha1': ssha,
    'resource-pack-id': sid,
    'resource-pack-prompt': prompt,
    'require-resource-pack': 'true',
})
print('restamped')
PY
chown mc4:mc4 /home/minecraft/multicraft/servers/Creative/server.properties
chown mc5:mc5 /home/minecraft/multicraft/servers/survival/server.properties
echo WAIT_FOR_BOOT
