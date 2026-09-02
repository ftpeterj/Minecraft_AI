#!/bin/bash
set -euo pipefail

echo '--- zip warn context ---'
grep -n -B15 'ZipUtil.pack' /home/minecraft/multicraft/servers/Creative/logs/latest.log | head -n 40

python3 - <<'PY'
from pathlib import Path

def patch(cfg: Path, abs_paths: list[str]) -> None:
    text = cfg.read_text(encoding='utf-8')
    start = text.find('  zip-destinations:')
    if start < 0:
        raise SystemExit(f'no zip-destinations in {cfg}')
    rest = text[start:]
    # cut until next top-level key under resourcepack (pack-mcmeta)
    end_rel = rest.find('\n  pack-mcmeta:')
    if end_rel < 0:
        raise SystemExit(f'no pack-mcmeta after zip-destinations in {cfg}')
    block = '  zip-destinations:\n' + ''.join(f"    - '{p}'\n" for p in abs_paths)
    new = text[:start] + block + rest[end_rel+1:]
    cfg.write_text(new, encoding='utf-8')
    print(f'fixed zip-destinations in {cfg}')

c = Path('/home/minecraft/multicraft/servers/Creative')
s = Path('/home/minecraft/multicraft/servers/survival')
patch(c/'plugins/NMinimap/config.yml', [
    str(c/'plugins/NMinimap/built-pack.zip'),
    str(c/'bluemap/web/nminimap.zip'),
])
patch(s/'plugins/NMinimap/config.yml', [
    str(s/'plugins/NMinimap/built-pack.zip'),
    str(s/'bluemap/web/nminimap.zip'),
])
PY

install -o mc4 -g mc4 -m 644 \
  /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/built-pack.zip \
  /home/minecraft/multicraft/servers/Creative/bluemap/web/nminimap.zip
install -o mc5 -g mc5 -m 644 \
  /home/minecraft/multicraft/servers/survival/plugins/NMinimap/built-pack.zip \
  /home/minecraft/multicraft/servers/survival/bluemap/web/nminimap.zip

CSHA=$(sha1sum /home/minecraft/multicraft/servers/Creative/bluemap/web/nminimap.zip | awk '{print $1}')
SSHA=$(sha1sum /home/minecraft/multicraft/servers/survival/bluemap/web/nminimap.zip | awk '{print $1}')
LAN_IP=$(hostname -I | awk '{print $1}')
echo "CSHA=$CSHA"
echo "SSHA=$SSHA"

python3 - "$CSHA" "$SSHA" "$LAN_IP" <<'PY'
from pathlib import Path
import sys
csha, ssha, ip = sys.argv[1], sys.argv[2], sys.argv[3]

def set_rp(props: Path, url: str, sha: str) -> None:
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
    print(f'set {props}')

set_rp(Path('/home/minecraft/multicraft/servers/Creative/server.properties'), f'http://{ip}:8100/nminimap.zip', csha)
set_rp(Path('/home/minecraft/multicraft/servers/survival/server.properties'), f'http://{ip}:8101/nminimap.zip', ssha)
PY

reload() {
  local props="$1"
  local port pw
  port=$(grep '^rcon.port=' "$props" | cut -d= -f2)
  pw=$(grep '^rcon.password=' "$props" | cut -d= -f2-)
  mcrcon -H 127.0.0.1 -P "$port" -p "$pw" "mm admin reload" || true
}

echo 'reloading nminimap'
reload /home/minecraft/multicraft/servers/Creative/server.properties
reload /home/minecraft/multicraft/servers/survival/server.properties
sleep 2
echo '--- zip dest ---'
grep -A4 'zip-destinations:' /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/config.yml
grep -A4 'zip-destinations:' /home/minecraft/multicraft/servers/survival/plugins/NMinimap/config.yml
echo '--- sha after reload ---'
sha1sum \
  /home/minecraft/multicraft/servers/Creative/bluemap/web/nminimap.zip \
  /home/minecraft/multicraft/servers/survival/bluemap/web/nminimap.zip
echo '--- latest zip warn ---'
grep ZipUtil /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 5 || true
grep ZipUtil /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 5 || true
