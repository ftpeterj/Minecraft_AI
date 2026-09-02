#!/bin/bash
for root in /home/minecraft/multicraft/servers/Creative /home/minecraft/multicraft/servers/survival; do
  echo "======== $root ========"
  cfg="$root/plugins/NMinimap/config.yml"
  echo '--- default-settings ---'
  grep -n -A20 'default-settings:' "$cfg" | head -n 25
  echo '--- resourcepack ---'
  grep -n -A25 'resourcepack:' "$cfg" | head -n 30
  echo '--- map-id / map-pixel ---'
  grep -n -E 'map-id:|map-pixel-size:|style:|side:' "$cfg" | head -n 20
  echo '--- data files ---'
  ls -la "$root/plugins/NMinimap" | head -n 40
  echo '--- sqlite ---'
  find "$root/plugins/NMinimap" -name '*.db' -o -name '*.sqlite*' -o -name '*.yml' | head
  echo
done
echo '=== CREATIVE latest nminimap ==='
grep -i nminimap /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 20
echo '=== SURVIVAL latest nminimap ==='
grep -i nminimap /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 20
echo '=== recent players ==='
grep -E 'logged in|lost connection' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 15
grep -E 'logged in|lost connection' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 15
