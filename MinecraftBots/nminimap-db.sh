#!/bin/bash
python3 - <<'PY'
import sqlite3, os
for db in [
    '/home/minecraft/multicraft/servers/Creative/plugins/NMinimap/database.db',
    '/home/minecraft/multicraft/servers/survival/plugins/NMinimap/database.db',
]:
    print('====', db)
    if not os.path.exists(db):
        print('missing')
        continue
    con = sqlite3.connect(db)
    cur = con.cursor()
    for row in cur.execute("SELECT name, sql FROM sqlite_master WHERE type='table'"):
        print('TABLE', row[0])
        print(row[1])
    for name, in cur.execute("SELECT name FROM sqlite_master WHERE type='table'"):
        print('--- rows', name)
        cols = [d[0] for d in cur.execute(f'PRAGMA table_info({name})')]
        print('cols', cols)
        for r in cur.execute(f'SELECT * FROM {name} LIMIT 20'):
            print(r)
    con.close()
PY
echo '=== pack.mcmeta / shaders ==='
for root in /home/minecraft/multicraft/servers/Creative /home/minecraft/multicraft/servers/survival; do
  echo "-- $root pack --"
  unzip -l "$root/plugins/NMinimap/built-pack.zip" | head -n 40
  echo '--- mcmeta ---'
  unzip -p "$root/plugins/NMinimap/built-pack.zip" pack.mcmeta 2>/dev/null || unzip -p "$root/plugins/NMinimap/built-pack.zip" '*/pack.mcmeta' 2>/dev/null || true
done
ls -la /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/resourcepack
find /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/resourcepack -type f | head -n 40
