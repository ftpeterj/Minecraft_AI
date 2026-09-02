#!/bin/bash
set -euo pipefail

rcon() {
  local props="$1"
  shift
  local port pw
  port=$(grep '^rcon.port=' "$props" | cut -d= -f2)
  pw=$(grep '^rcon.password=' "$props" | cut -d= -f2-)
  mcrcon -H 127.0.0.1 -P "$port" -p "$pw" "$@"
}

python3 - <<'PY'
from pathlib import Path

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
    print('cleared pack on', path)

clear = {
    'resource-pack': '',
    'resource-pack-sha1': '',
    'resource-pack-id': '',
    'resource-pack-prompt': '',
    'require-resource-pack': 'false',
}
set_props(Path('/home/minecraft/multicraft/servers/Creative/server.properties'), clear)
set_props(Path('/home/minecraft/multicraft/servers/survival/server.properties'), clear)
PY

disable_jar() {
  local dir="$1" owner="$2"
  mkdir -p "$dir/disabled"
  if [ -f "$dir/NMinimap.jar" ]; then
    mv -f "$dir/NMinimap.jar" "$dir/disabled/NMinimap.jar"
    chown -R "$owner:$owner" "$dir/disabled"
    echo "moved $dir/NMinimap.jar -> disabled/"
  else
    echo "already disabled: $dir"
  fi
}
disable_jar /home/minecraft/multicraft/servers/Creative/plugins mc4
disable_jar /home/minecraft/multicraft/servers/survival/plugins mc5

echo 'Stopping both so NMinimap unloads and pack is no longer required'
rcon /home/minecraft/multicraft/servers/Creative/server.properties stop || true
rcon /home/minecraft/multicraft/servers/survival/server.properties stop || true

for i in $(seq 1 30); do
  c=$(pgrep -u mc4 -f paper-26.2-121.jar || true)
  s=$(pgrep -u mc5 -f paper-26.2-121.jar || true)
  echo "dying t=$i mc4=${c:-none} mc5=${s:-none}"
  if [ -z "$c" ] && [ -z "$s" ]; then break; fi
  sleep 1
done

python3 - <<'PY'
from pathlib import Path
clear = {
    'resource-pack': '',
    'resource-pack-sha1': '',
    'resource-pack-id': '',
    'resource-pack-prompt': '',
    'require-resource-pack': 'false',
}
for p in [
    Path('/home/minecraft/multicraft/servers/Creative/server.properties'),
    Path('/home/minecraft/multicraft/servers/survival/server.properties'),
]:
    lines = p.read_text(encoding='utf-8').splitlines()
    seen=set(); out=[]
    for line in lines:
        key = line.split('=',1)[0] if '=' in line else ''
        if key in clear:
            out.append(f'{key}={clear[key]}'); seen.add(key)
        else:
            out.append(line)
    for k,v in clear.items():
        if k not in seen:
            out.append(f'{k}={v}')
    p.write_text('\n'.join(out)+'\n', encoding='utf-8')
print('restamped empty pack')
PY
chown mc4:mc4 /home/minecraft/multicraft/servers/Creative/server.properties
chown mc5:mc5 /home/minecraft/multicraft/servers/survival/server.properties
echo WAIT_FOR_BOOT
