#!/bin/bash
set -euo pipefail
SHA=$(sha1sum /home/minecraft/multicraft/servers/Creative/bluemap/web/nminimap.zip | awk '{print $1}')
LAN_IP=$(hostname -I | awk '{print $1}')
echo "SHA=$SHA IP=$LAN_IP"

python3 - "$SHA" "$LAN_IP" <<'PY'
from pathlib import Path
import sys
sha, ip = sys.argv[1], sys.argv[2]

def set_rp(props: Path, url: str) -> None:
    lines = props.read_text(encoding='utf-8').splitlines()
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
    props.write_text('\n'.join(out) + '\n', encoding='utf-8')
    print('wrote', props)

set_rp(Path('/home/minecraft/multicraft/servers/Creative/server.properties'), f'http://{ip}:8100/nminimap.zip')
set_rp(Path('/home/minecraft/multicraft/servers/survival/server.properties'), f'http://{ip}:8101/nminimap.zip')
PY

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

python3 - "$SHA" "$LAN_IP" <<'PY'
from pathlib import Path
import sys
sha, ip = sys.argv[1], sys.argv[2]

def set_rp(props: Path, url: str) -> None:
    lines = props.read_text(encoding='utf-8').splitlines()
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
    props.write_text('\n'.join(out) + '\n', encoding='utf-8')
    print('reapplied', props)

set_rp(Path('/home/minecraft/multicraft/servers/Creative/server.properties'), f'http://{ip}:8100/nminimap.zip')
set_rp(Path('/home/minecraft/multicraft/servers/survival/server.properties'), f'http://{ip}:8101/nminimap.zip')
PY
chown mc4:mc4 /home/minecraft/multicraft/servers/Creative/server.properties || true
chown mc5:mc5 /home/minecraft/multicraft/servers/survival/server.properties || true

for i in $(seq 1 40); do
  c=$(pgrep -u mc4 -f paper-26.2-121.jar || true)
  s=$(pgrep -u mc5 -f paper-26.2-121.jar || true)
  echo "up t=$i mc4=${c:-none} mc5=${s:-none}"
  if [ -n "$c" ] && [ -n "$s" ]; then
    break
  fi
  sleep 3
done
sleep 25
echo '--- enable ---'
grep 'Enabling NMinimap' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 1
grep 'Enabling NMinimap' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 1
echo '--- ziputil after boot ---'
awk '/Enabling NMinimap/{t=1} t&&/NoSuchFileException/{print; t=0}' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 3 || true
awk '/Enabling NMinimap/{t=1} t&&/NoSuchFileException/{print; t=0}' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 3 || true
echo '--- rp ---'
grep '^resource-pack' /home/minecraft/multicraft/servers/Creative/server.properties
grep '^resource-pack' /home/minecraft/multicraft/servers/survival/server.properties
echo '--- defaults ---'
grep -E 'enable-anyway|style:' /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/config.yml | head -n 4
echo DONE
