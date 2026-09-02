#!/bin/bash
S=/home/minecraft/multicraft/servers/survival
C=/home/minecraft/multicraft/servers/Creative
echo '=== ONLINE ==='
grep -E 'logged in|lost connection|Kicked|resource|Resource|KingOfThisHouse|ShadowKing|BigMuddy' "$S/logs/latest.log" | tail -n 40
echo '--- creative ---'
grep -E 'logged in|lost connection|Kicked|resource|KingOfThisHouse' "$C/logs/latest.log" | tail -n 20
echo '=== DB ==='
python3 - <<'PY'
import sqlite3
for db in [
    '/home/minecraft/multicraft/servers/survival/plugins/NMinimap/database.db',
    '/home/minecraft/multicraft/servers/Creative/plugins/NMinimap/database.db',
]:
    print(db)
    con=sqlite3.connect(db)
    print(list(con.execute('SELECT * FROM nminimap_players')))
    con.close()
PY
echo '=== CONFIG side/style/anyway ==='
grep -n -E 'side:|style:|enable-anyway|map-pixel' "$S/plugins/NMinimap/config.yml" "$C/plugins/NMinimap/config.yml"
echo '=== PACK URL ==='
grep -E '^resource-pack' "$S/server.properties" "$C/server.properties"
echo '=== 26.2 shader files ==='
ls -la "$S/plugins/NMinimap/resourcepack/nminimap_26_2/assets/minecraft/shaders/core/"
echo '=== pack.mcmeta ==='
cat "$S/plugins/NMinimap/resourcepack/pack.mcmeta"
echo '=== list zip 26.2 ==='
unzip -l "$S/plugins/NMinimap/built-pack.zip" | grep -E '26_2|pack.mcmeta|text'
echo '=== sha of served zip vs built ==='
sha1sum "$S/plugins/NMinimap/built-pack.zip" "$S/bluemap/web/nminimap.zip" "$C/plugins/NMinimap/built-pack.zip" "$C/bluemap/web/nminimap.zip"
