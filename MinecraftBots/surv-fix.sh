#!/bin/bash
set -euo pipefail
S=/home/minecraft/multicraft/servers/survival
C=/home/minecraft/multicraft/servers/Creative

echo '=== older join crumbs ==='
zgrep -h -E 'logged in|UUID of player|lost connection|Kicked|not whitelisted|Failed to verify|Disconnected' "$S"/logs/*.log.gz 2>/dev/null | tail -n 30 || true

python3 - <<'PY'
from pathlib import Path

def set_props(path: Path, updates: dict) -> None:
    lines = path.read_text(encoding='utf-8').splitlines()
    seen = set()
    out = []
    for line in lines:
        key = line.split('=', 1)[0] if '=' in line else ''
        if key in updates:
            out.append(f'{key}={updates[key]}')
            seen.add(key)
        else:
            out.append(line)
    for key, val in updates.items():
        if key not in seen:
            out.append(f'{key}={val}')
    path.write_text('\n'.join(out) + '\n', encoding='utf-8')
    print('updated', path)

# Resource-pack-prompt MUST be a JSON text component. Plain text breaks 26.2.
# Keep the pack URL so NMinimap can still prompt, but with valid JSON and no sha1.
ip = '192.168.0.200'
prompt = '{"text":"NMinimap HUD"}'
set_props(Path('/home/minecraft/multicraft/servers/survival/server.properties'), {
    'resource-pack-prompt': prompt,
    'resource-pack-sha1': '',
    'require-resource-pack': 'false',
    'resource-pack': f'http://{ip}:8101/nminimap.zip',
    'enable-query': 'false',
    'query.port': '25566',
})
set_props(Path('/home/minecraft/multicraft/servers/Creative/server.properties'), {
    'resource-pack-prompt': prompt,
    'resource-pack-sha1': '',
    'require-resource-pack': 'false',
    'resource-pack': f'http://{ip}:8100/nminimap.zip',
})

def retarget(conf: Path, world: str, dimension: str, name: str) -> None:
    text = conf.read_text(encoding='utf-8')
    import re
    text = re.sub(r'^world:.*$', f'world: "{world}"', text, count=1, flags=re.M)
    text = re.sub(r'^dimension:.*$', f'dimension: "{dimension}"', text, count=1, flags=re.M)
    text = re.sub(r'^name:.*$', f'name: "{name}"', text, count=1, flags=re.M)
    conf.write_text(text, encoding='utf-8')
    print('retargeted', conf)

maps = Path('/home/minecraft/multicraft/servers/survival/plugins/BlueMap/maps')
retarget(maps / 'world_nether.conf', 'world', 'minecraft:the_nether', 'Nether')
retarget(maps / 'world_the_end.conf', 'world', 'minecraft:the_end', 'The End')
print('nether/end world lines:')
for p in sorted(maps.glob('*.conf')):
    for line in p.read_text(encoding='utf-8').splitlines():
        if line.startswith('world:') or line.startswith('dimension:') or line.startswith('name:'):
            print(p.name, line)
PY

chown mc5:mc5 "$S/server.properties" "$S/plugins/BlueMap/maps/world_nether.conf" "$S/plugins/BlueMap/maps/world_the_end.conf"
chown mc4:mc4 "$C/server.properties"

stop_one() {
  local props="$1"
  local port pw
  port=$(grep '^rcon.port=' "$props" | cut -d= -f2)
  pw=$(grep '^rcon.password=' "$props" | cut -d= -f2-)
  mcrcon -H 127.0.0.1 -P "$port" -p "$pw" stop || true
}
echo 'Stopping both for properties + BlueMap map configs'
stop_one "$S/server.properties"
stop_one "$C/server.properties"

for i in $(seq 1 30); do
  c=$(pgrep -u mc4 -f paper-26.2-121.jar || true)
  s=$(pgrep -u mc5 -f paper-26.2-121.jar || true)
  echo "dying t=$i mc4=${c:-none} mc5=${s:-none}"
  if [ -z "$c" ] && [ -z "$s" ]; then
    break
  fi
  sleep 1
done

# Re-stamp after stop in case Bukkit rewrote properties from memory
python3 - <<'PY'
from pathlib import Path
ip = '192.168.0.200'
prompt = '{"text":"NMinimap HUD"}'

def set_props(path: Path, updates: dict) -> None:
    lines = path.read_text(encoding='utf-8').splitlines()
    seen = set()
    out = []
    for line in lines:
        key = line.split('=', 1)[0] if '=' in line else ''
        if key in updates:
            out.append(f'{key}={updates[key]}')
            seen.add(key)
        else:
            out.append(line)
    for key, val in updates.items():
        if key not in seen:
            out.append(f'{key}={val}')
    path.write_text('\n'.join(out) + '\n', encoding='utf-8')
    print('restamp', path)

set_props(Path('/home/minecraft/multicraft/servers/survival/server.properties'), {
    'resource-pack-prompt': prompt,
    'resource-pack-sha1': '',
    'require-resource-pack': 'false',
    'resource-pack': f'http://{ip}:8101/nminimap.zip',
    'enable-query': 'false',
    'query.port': '25566',
})
set_props(Path('/home/minecraft/multicraft/servers/Creative/server.properties'), {
    'resource-pack-prompt': prompt,
    'resource-pack-sha1': '',
    'require-resource-pack': 'false',
    'resource-pack': f'http://{ip}:8100/nminimap.zip',
})
PY
chown mc5:mc5 "$S/server.properties"
chown mc4:mc4 "$C/server.properties"

for i in $(seq 1 50); do
  c=$(grep -c 'Enabling NMinimap' "$C/logs/latest.log" 2>/dev/null || true)
  s=$(grep -c 'Enabling NMinimap' "$S/logs/latest.log" 2>/dev/null || true)
  echo "boot t=$i c=$c s=$s"
  if [ "${c:-0}" -ge 1 ] && [ "${s:-0}" -ge 1 ]; then
    # wait until this is a NEW boot: Done line after Enabling
    if grep -q 'Done (' "$C/logs/latest.log" && grep -q 'Done (' "$S/logs/latest.log"; then
      break
    fi
  fi
  sleep 3
done
sleep 8
echo '=== BlueMap after ==='
grep -E 'BlueMap|problem with your BlueMap|query system|resource pack prompt|Done \(' "$S/logs/latest.log" | tail -n 40
echo '=== props ==='
grep -E '^resource-pack|^enable-query|^query.port|^server-port' "$S/server.properties"
grep -E '^resource-pack|^server-port' "$C/server.properties"
echo FIX_DONE
