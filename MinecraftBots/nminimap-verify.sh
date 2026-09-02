#!/bin/bash
set -euo pipefail
echo FILES
find /home/minecraft/multicraft/servers/Creative /home/minecraft/multicraft/servers/survival \
  \( -name nminimap.zip -o -name built-pack.zip \) -ls
echo SHA
sha1sum \
  /home/minecraft/multicraft/servers/Creative/bluemap/web/nminimap.zip \
  /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/built-pack.zip \
  /home/minecraft/multicraft/servers/survival/bluemap/web/nminimap.zip \
  /home/minecraft/multicraft/servers/survival/plugins/NMinimap/built-pack.zip
echo CREATIVE_LOG
grep NMinimap /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 15
echo SURVIVAL_LOG
grep NMinimap /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 15
echo PLUGINS
grep 'Plugins (' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 1
grep 'Plugins (' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 1
