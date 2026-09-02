#!/bin/bash
set -euo pipefail
LAN_IP=$(hostname -I | awk '{print $1}')

set_rp() {
  python3 - "$1" "$2" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
url = sys.argv[2]
repl = {
    'resource-pack': url,
    'resource-pack-sha1': '',
    'resource-pack-prompt': 'NMinimap HUD',
    'require-resource-pack': 'false',
}
lines = p.read_text(encoding='utf-8').splitlines()
out, seen = [], set()
for line in lines:
    key = line.split('=', 1)[0] if '=' in line else ''
    if key in repl:
        out.append(f'{key}={repl[key]}')
        seen.add(key)
    else:
        out.append(line)
for key, val in repl.items():
    if key not in seen:
        out.append(f'{key}={val}')
p.write_text('\n'.join(out) + '\n', encoding='utf-8')
print('set', p)
PY
}

set_rp /home/minecraft/multicraft/servers/Creative/server.properties "http://${LAN_IP}:8100/nminimap.zip"
set_rp /home/minecraft/multicraft/servers/survival/server.properties "http://${LAN_IP}:8101/nminimap.zip"

stop_one() {
  local props="$1"
  local port pw
  port=$(grep '^rcon.port=' "$props" | cut -d= -f2)
  pw=$(grep '^rcon.password=' "$props" | cut -d= -f2-)
  mcrcon -H 127.0.0.1 -P "$port" -p "$pw" stop || true
}
stop_one /home/minecraft/multicraft/servers/Creative/server.properties
stop_one /home/minecraft/multicraft/servers/survival/server.properties

for i in $(seq 1 30); do
  c=$(pgrep -u mc4 -f paper-26.2-121.jar || true)
  s=$(pgrep -u mc5 -f paper-26.2-121.jar || true)
  echo "dying t=$i mc4=${c:-none} mc5=${s:-none}"
  if [ -z "$c" ] && [ -z "$s" ]; then
    break
  fi
  sleep 1
done

set_rp /home/minecraft/multicraft/servers/Creative/server.properties "http://${LAN_IP}:8100/nminimap.zip"
set_rp /home/minecraft/multicraft/servers/survival/server.properties "http://${LAN_IP}:8101/nminimap.zip"
chown mc4:mc4 /home/minecraft/multicraft/servers/Creative/server.properties || true
chown mc5:mc5 /home/minecraft/multicraft/servers/survival/server.properties || true

for i in $(seq 1 50); do
  cdone=$(grep -c 'Enabling NMinimap' /home/minecraft/multicraft/servers/Creative/logs/latest.log 2>/dev/null || true)
  sdone=$(grep -c 'Enabling NMinimap' /home/minecraft/multicraft/servers/survival/logs/latest.log 2>/dev/null || true)
  echo "boot t=$i c=$cdone s=$sdone"
  if [ "${cdone:-0}" -ge 1 ] && [ "${sdone:-0}" -ge 1 ]; then
    break
  fi
  sleep 3
done
sleep 5
echo '--- rp ---'
grep -E '^resource-pack|^require-resource' /home/minecraft/multicraft/servers/Creative/server.properties
grep -E '^resource-pack|^require-resource' /home/minecraft/multicraft/servers/survival/server.properties
echo '--- enable ---'
grep 'Enabling NMinimap' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 1
grep 'Enabling NMinimap' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 1
echo '--- nosuch ---'
grep NoSuchFileException /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 2 || true
grep NoSuchFileException /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 2 || true
echo DONE
