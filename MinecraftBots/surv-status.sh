#!/bin/bash
S=/home/minecraft/multicraft/servers/survival
C=/home/minecraft/multicraft/servers/Creative
echo PIDS
pgrep -a -u mc4 -f paper-26.2-121.jar || true
pgrep -a -u mc5 -f paper-26.2-121.jar || true
echo SURV_DONE
grep 'Done (' "$S/logs/latest.log" | tail -n 1
echo CREATIVE_DONE
grep 'Done (' "$C/logs/latest.log" | tail -n 1
echo BLUEMAP
grep -E 'problem with your BlueMap|Loading map|WebServer|Loaded!' "$S/logs/latest.log" | tail -n 20
echo PROMPT
grep 'resource pack prompt' "$S/logs/latest.log" "$C/logs/latest.log" || echo 'no prompt parse errors'
echo QUERY
grep 'query system' "$S/logs/latest.log" || echo 'no query bind error'
echo PROPS
grep -E '^resource-pack|^enable-query|^query.port|^server-port' "$S/server.properties"
echo JOINS
grep -E 'logged in|UUID of player|lost connection|Kicked' "$S/logs/latest.log" | tail -n 20
grep -E 'logged in|UUID of player|lost connection|Kicked' "$C/logs/latest.log" | tail -n 20
echo MAPS
grep -E '^world:|^dimension:|^name:' "$S"/plugins/BlueMap/maps/*.conf
