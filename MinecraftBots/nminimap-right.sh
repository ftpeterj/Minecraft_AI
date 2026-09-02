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
import sqlite3

def patch_cfg(path: Path) -> None:
    text = path.read_text(encoding='utf-8')
    text = text.replace('  side: left', '  side: right', 1)
    if '  side: right' not in text:
        raise SystemExit(f'side not patched in {path}')
    path.write_text(text, encoding='utf-8')
    print('config', path, 'side=right')

def patch_db(path: Path) -> None:
    con = sqlite3.connect(path)
    cur = con.cursor()
    cur.execute('UPDATE nminimap_players SET isRight=1, isRound=1, enabled=1')
    print(path, 'updated', cur.rowcount, 'players')
    for row in cur.execute('SELECT name, isRight, isRound, enabled, scale FROM nminimap_players'):
        print(' ', row)
    con.commit()
    con.close()

for root, owner in [
    ('/home/minecraft/multicraft/servers/Creative', 'mc4'),
    ('/home/minecraft/multicraft/servers/survival', 'mc5'),
]:
    patch_cfg(Path(root) / 'plugins/NMinimap/config.yml')
    patch_db(Path(root) / 'plugins/NMinimap/database.db')
PY

chown mc4:mc4 /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/config.yml \
  /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/database.db
chown mc5:mc5 /home/minecraft/multicraft/servers/survival/plugins/NMinimap/config.yml \
  /home/minecraft/multicraft/servers/survival/plugins/NMinimap/database.db

echo '--- reload ---'
rcon /home/minecraft/multicraft/servers/Creative/server.properties 'mm admin reload' || true
rcon /home/minecraft/multicraft/servers/survival/server.properties 'mm admin reload' || true

# Apply to the online player immediately, then kick so the HUD shader pack is re-offered
echo '--- player command + kick ---'
rcon /home/minecraft/multicraft/servers/survival/server.properties \
  'tellraw KingOfThisHouse {"text":"NMinimap: moving HUD to top-right. Relog and ACCEPT the resource pack. Kappa/Iris shaders must be OFF or the maps stay in the world.","color":"yellow"}' || true
# Paper cannot run plugin cmds via /execute; kick forces a clean resend of pack + DB settings
rcon /home/minecraft/multicraft/servers/survival/server.properties \
  'kick KingOfThisHouse NMinimap HUD fix: relog, accept the resource pack, and turn OFF Kappa/Iris shaders. Then /minimap side right' || true

echo '--- config check ---'
grep -n 'side:' /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/config.yml /home/minecraft/multicraft/servers/survival/plugins/NMinimap/config.yml
echo DONE
