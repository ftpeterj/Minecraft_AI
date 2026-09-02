#!/bin/bash
for i in $(seq 1 40); do
  if grep -q 'Done (' /home/minecraft/multicraft/servers/Creative/logs/latest.log \
     && grep -q 'Done (' /home/minecraft/multicraft/servers/survival/logs/latest.log; then
    # new boot: NMinimap should NOT be in Plugins line
    if grep -q 'Plugins (' /home/minecraft/multicraft/servers/survival/logs/latest.log; then
      break
    fi
  fi
  sleep 3
done
sleep 5
echo CREATIVE_PLUGINS
grep 'Plugins (' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -1
echo SURVIVAL_PLUGINS
grep 'Plugins (' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -1
echo DONE
grep 'Done (' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -1
grep 'Done (' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -1
echo RP
grep -E '^resource-pack|^require-resource' /home/minecraft/multicraft/servers/survival/server.properties
echo JARS
ls /home/minecraft/multicraft/servers/Creative/plugins/*.jar
ls /home/minecraft/multicraft/servers/survival/plugins/*.jar
echo DISABLED
ls /home/minecraft/multicraft/servers/Creative/plugins/disabled/ /home/minecraft/multicraft/servers/survival/plugins/disabled/
