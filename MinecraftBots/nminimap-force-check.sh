#!/bin/bash
sleep 40
echo DONE_LINES
grep 'Done (' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -1
grep 'Done (' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -1
echo PROMPT
grep 'resource pack prompt' /home/minecraft/multicraft/servers/survival/logs/latest.log || echo 'no prompt errors'
echo RP
grep -E '^resource-pack|^require-resource' /home/minecraft/multicraft/servers/survival/server.properties
echo ZIP_CORE
unzip -l /home/minecraft/multicraft/servers/survival/bluemap/web/nminimap-hud.zip | grep -E 'shaders/core|pack.mcmeta'
echo ENABLE
grep 'Enabling NMinimap' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -1
echo LISTEN
ss -lptn | grep -E '8101|25566' || true
