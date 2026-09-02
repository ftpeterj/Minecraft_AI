#!/bin/bash
echo RP_CREATIVE
grep -E '^resource-pack|^require-resource' /home/minecraft/multicraft/servers/Creative/server.properties
echo RP_SURVIVAL
grep -E '^resource-pack|^require-resource' /home/minecraft/multicraft/servers/survival/server.properties
echo ENABLE
grep 'Enabling NMinimap' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 2
grep 'Enabling NMinimap' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 2
echo DONE_LINE
grep 'Done (' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 1
grep 'Done (' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 1
echo PIDS
pgrep -a -u mc4 -f paper-26.2-121.jar || true
pgrep -a -u mc5 -f paper-26.2-121.jar || true
echo DEFAULTS
grep -E 'enable-anyway:|style:' /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/config.yml
grep -E 'enable-anyway:|style:' /home/minecraft/multicraft/servers/survival/plugins/NMinimap/config.yml
echo NOSUCH
grep NoSuchFileException /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 2 || true
grep NoSuchFileException /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 2 || true
