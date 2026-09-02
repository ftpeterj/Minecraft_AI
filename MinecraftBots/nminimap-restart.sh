#!/bin/bash
set -euo pipefail

echo '--- config defaults ---'
grep -n -E 'enable-anyway|style:|zip-destinations|nminimap.zip' \
  /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/config.yml \
  /home/minecraft/multicraft/servers/survival/plugins/NMinimap/config.yml

echo '--- packs ---'
ls -la /home/minecraft/multicraft/servers/Creative/bluemap/web/nminimap.zip \
       /home/minecraft/multicraft/servers/survival/bluemap/web/nminimap.zip

stop_one() {
  local props="$1" name="$2"
  local port pw
  port=$(grep '^rcon.port=' "$props" | cut -d= -f2)
  pw=$(grep '^rcon.password=' "$props" | cut -d= -f2-)
  echo "Stopping $name"
  mcrcon -H 127.0.0.1 -P "$port" -p "$pw" stop || true
}

CSHA=$(sha1sum /home/minecraft/multicraft/servers/Creative/bluemap/web/nminimap.zip | awk '{print $1}')
SSHA=$(sha1sum /home/minecraft/multicraft/servers/survival/bluemap/web/nminimap.zip | awk '{print $1}')
LAN_IP=$(hostname -I | awk '{print $1}')

ensure_rp() {
  local props="$1" url="$2" sha="$3"
  python3 - "$props" "$url" "$sha" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
url, sha = sys.argv[2], sys.argv[3]
lines = p.read_text(encoding='utf-8').splitlines()
repl = {
    'resource-pack': url,
    'resource-pack-sha1': sha,
    'resource-pack-prompt': 'NMinimap HUD',
    'require-resource-pack': 'false',
}
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
print(f'reapplied {p}')
PY
}

stop_one /home/minecraft/multicraft/servers/Creative/server.properties Creative
stop_one /home/minecraft/multicraft/servers/survival/server.properties Survival

# wait until old java dies, then stamp resource-pack before they boot
for i in $(seq 1 30); do
  c=$(pgrep -u mc4 -f paper-26.2-121.jar || true)
  s=$(pgrep -u mc5 -f paper-26.2-121.jar || true)
  echo "dying t=$i mc4=${c:-none} mc5=${s:-none}"
  if [ -z "$c" ] && [ -z "$s" ]; then
    break
  fi
  sleep 1
done
ensure_rp /home/minecraft/multicraft/servers/Creative/server.properties "http://${LAN_IP}:8100/nminimap.zip" "$CSHA"
ensure_rp /home/minecraft/multicraft/servers/survival/server.properties "http://${LAN_IP}:8101/nminimap.zip" "$SSHA"
chown mc4:mc4 /home/minecraft/multicraft/servers/Creative/server.properties || true
chown mc5:mc5 /home/minecraft/multicraft/servers/survival/server.properties || true

sleep 2
for i in $(seq 1 40); do
  c=$(pgrep -u mc4 -f paper-26.2-121.jar || true)
  s=$(pgrep -u mc5 -f paper-26.2-121.jar || true)
  echo "t=$i mc4=${c:-none} mc5=${s:-none}"
  if [ -n "$c" ] && [ -n "$s" ]; then
    break
  fi
  sleep 3
done

for i in $(seq 1 40); do
  if grep -q 'Done (' /home/minecraft/multicraft/servers/Creative/logs/latest.log \
     && grep -q 'Done (' /home/minecraft/multicraft/servers/survival/logs/latest.log; then
    echo both-done
    break
  fi
  sleep 3
done

echo '--- plugins after restart ---'
grep -E 'Plugins \(.*NMinimap|Enabling NMinimap|Done \(' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 8
grep -E 'Plugins \(.*NMinimap|Enabling NMinimap|Done \(' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 8

echo '--- resource-pack still set ---'
grep -E '^resource-pack|^require-resource' /home/minecraft/multicraft/servers/Creative/server.properties
grep -E '^resource-pack|^require-resource' /home/minecraft/multicraft/servers/survival/server.properties
