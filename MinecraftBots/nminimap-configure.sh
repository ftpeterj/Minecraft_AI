#!/bin/bash
set -euo pipefail

patch_config() {
  local cfg="$1"
  python3 - "$cfg" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text(encoding='utf-8')
text = text.replace('enable-anyway: false', 'enable-anyway: true', 1)
text = text.replace('style: square', 'style: round', 1)
old = """  zip-destinations:
    - 'NMinimap/built-pack.zip'"""
new = """  zip-destinations:
    - 'NMinimap/built-pack.zip'
    - 'bluemap/web/nminimap.zip'"""
if "bluemap/web/nminimap.zip" not in text:
    if old not in text:
        raise SystemExit(f'zip-destinations block missing in {p}')
    text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')
print(f'patched {p}')
PY
}

patch_config /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/config.yml
patch_config /home/minecraft/multicraft/servers/survival/plugins/NMinimap/config.yml

# Default static-markers point at world 0,0 named "world". Creative world name may differ.
# Leave as-is; a missing world just skips that marker.

copy_pack() {
  local src="$1" dest="$2" owner="$3"
  install -o "$owner" -g "$owner" -m 644 "$src" "$dest"
  sha1sum "$dest"
}

echo '--- copy packs to BlueMap web ---'
copy_pack /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/built-pack.zip \
  /home/minecraft/multicraft/servers/Creative/bluemap/web/nminimap.zip mc4
copy_pack /home/minecraft/multicraft/servers/survival/plugins/NMinimap/built-pack.zip \
  /home/minecraft/multicraft/servers/survival/bluemap/web/nminimap.zip mc5

LAN_IP=$(hostname -I | awk '{print $1}')
echo "LAN_IP=$LAN_IP"

set_resource_pack() {
  local props="$1" url="$2" sha="$3"
  python3 - "$props" "$url" "$sha" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
url, sha = sys.argv[2], sys.argv[3]
lines = p.read_text(encoding='utf-8').splitlines()
out = []
seen = set()
repl = {
    'resource-pack': url,
    'resource-pack-sha1': sha,
    'resource-pack-prompt': 'NMinimap HUD',
    'require-resource-pack': 'false',
}
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
print(f'updated {p}')
PY
}

CSHA=$(sha1sum /home/minecraft/multicraft/servers/Creative/bluemap/web/nminimap.zip | awk '{print $1}')
SSHA=$(sha1sum /home/minecraft/multicraft/servers/survival/bluemap/web/nminimap.zip | awk '{print $1}')
set_resource_pack /home/minecraft/multicraft/servers/Creative/server.properties "http://${LAN_IP}:8100/nminimap.zip" "$CSHA"
set_resource_pack /home/minecraft/multicraft/servers/survival/server.properties "http://${LAN_IP}:8101/nminimap.zip" "$SSHA"

echo '--- resource-pack lines ---'
grep -E '^resource-pack|^require-resource' /home/minecraft/multicraft/servers/Creative/server.properties
grep -E '^resource-pack|^require-resource' /home/minecraft/multicraft/servers/survival/server.properties

reload_mm() {
  local props="$1" name="$2"
  local port pw
  port=$(grep '^rcon.port=' "$props" | cut -d= -f2)
  pw=$(grep '^rcon.password=' "$props" | cut -d= -f2-)
  echo "Reloading NMinimap on $name"
  mcrcon -H 127.0.0.1 -P "$port" -p "$pw" "mm admin reload" || true
}

reload_mm /home/minecraft/multicraft/servers/Creative/server.properties Creative
reload_mm /home/minecraft/multicraft/servers/survival/server.properties Survival

echo '--- survival bind context ---'
grep -n -B5 -A8 'BindException\|Address already in use\|BlueMap' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 40 || true
echo '--- listening ---'
ss -lptn | grep -E '8100|8101|25565|25566' || true
