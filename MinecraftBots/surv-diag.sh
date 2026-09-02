#!/bin/bash
set -uo pipefail
S=/home/minecraft/multicraft/servers/survival
C=/home/minecraft/multicraft/servers/Creative

echo '=== PIDS ==='
pgrep -a -u mc5 -f paper || true
pgrep -a -u mc4 -f paper || true

echo '=== LISTEN ==='
ss -lptun | grep -E '25565|25566|25575|25577|8100|8101' || true

echo '=== SURVIVAL TAIL ==='
tail -n 80 "$S/logs/latest.log"

echo '=== SURVIVAL ERRORS ==='
grep -E 'ERROR|WARN|Exception|kick|Disconnect|Whitelist|JoinGate|resource|BlueMap|Failed|denied|login' "$S/logs/latest.log" | tail -n 80

echo '=== CREATIVE RECENT JOINS ==='
grep -E 'logged in|Disconnect|Kicked|Whitelist|JoinGate|lost connection' "$C/logs/latest.log" | tail -n 20

echo '=== SURVIVAL JOINS ==='
grep -E 'logged in|Disconnect|Kicked|Whitelist|JoinGate|lost connection|UUID' "$S/logs/latest.log" | tail -n 40

echo '=== WORLDS ==='
ls -la "$S" | head -n 40
echo '--- level-name / nether / end ---'
grep -E 'level-name|allow-nether|white-list|enforce-whitelist|online-mode|server-port|resource-pack|spawn-protection|max-players|query|motd' "$S/server.properties"

echo '=== BLUEMAP MAPS ==='
ls -la "$S/plugins/BlueMap/maps" 2>/dev/null || true
echo '--- plugin.conf ---'
cat "$S/plugins/BlueMap/plugin.conf" 2>/dev/null || true
echo '--- core.conf ---'
cat "$S/plugins/BlueMap/core.conf" 2>/dev/null || true
echo '--- map confs ---'
for f in "$S/plugins/BlueMap/maps"/*.conf; do
  echo "FILE $f"
  cat "$f"
  echo
done

echo '=== WHITELIST ==='
cat "$S/whitelist.json" 2>/dev/null || true
echo '=== OPS ==='
cat "$S/ops.json" 2>/dev/null || true

echo '=== JOINGATE ==='
ls -la "$S/plugins/JoinGate" 2>/dev/null || true
cat "$S/plugins/JoinGate/config.yml" 2>/dev/null || true
