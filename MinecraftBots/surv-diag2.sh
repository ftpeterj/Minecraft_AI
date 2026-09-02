#!/bin/bash
S=/home/minecraft/multicraft/servers/survival
C=/home/minecraft/multicraft/servers/Creative
echo '=== WORLD DIRS ==='
ls -la "$S" | sed -n '1,50p'
echo '--- world internals ---'
ls -la "$S/world" 2>/dev/null | head -n 40
echo '--- nether/end? ---'
ls -ld "$S"/world_nether "$S"/world_the_end "$S"/world/DIM-1 "$S"/world/DIM1 "$S"/world/dimensions 2>/dev/null || true
find "$S/world" -maxdepth 3 -type d \( -iname '*nether*' -o -iname '*end*' -o -iname 'DIM*' -o -iname 'dimensions' \) 2>/dev/null
echo '=== paper-world ==='
ls "$S"/config 2>/dev/null
grep -n 'nether\|the_end\|world' "$S"/bukkit.yml "$S"/spigot.yml "$S"/config/paper-world-defaults.yml "$S"/config/paper-global.yml 2>/dev/null | head -n 40
echo '=== server.properties full relevant ==='
grep -E 'resource-pack|query|enable-query|white-list|enforce|online-mode|server-port|level-name|allow-nether|motd|prevent-proxy' "$S/server.properties"
echo '=== CREATIVE same ==='
grep -E 'resource-pack|query|enable-query|white-list|server-port' "$C/server.properties"
echo '=== BLUEMAP world.conf world= ==='
grep -n '^world:\|^dimension:\|^name:' "$S"/plugins/BlueMap/maps/*.conf
echo '=== JOIN attempts last 2 logs ==='
grep -E 'logged in|Disconnect|Kicked|lost connection|UUID of player|com/mojang|Failed to verify|not whitelisted|JoinGate|Timed out|Disconnected|Protocol|incompatible' "$S/logs/latest.log" | tail -n 50
echo '--- older logs ---'
ls -lt "$S/logs" | head
echo '=== CREATIVE joins ==='
grep -E 'logged in|Disconnect|Kicked|lost connection|UUID of player|not whitelisted|JoinGate|resource' "$C/logs/latest.log" | tail -n 30
echo '=== pack prompt errors ==='
grep -A20 'resource pack prompt' "$S/logs/latest.log" | head -n 30
grep -A20 'resource pack prompt' "$C/logs/latest.log" | head -n 30
echo '=== bluemap world.conf ==='
grep -n '^world:\|^dimension:\|^name:' "$S"/plugins/BlueMap/maps/world.conf
echo '=== nminimap disallowed ==='
grep -A20 'disallowed-worlds' "$S/plugins/NMinimap/config.yml" | head -n 25
