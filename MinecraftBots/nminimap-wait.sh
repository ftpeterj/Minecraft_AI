#!/bin/bash
set -euo pipefail
echo waiting
for i in $(seq 1 30); do
  c=$(pgrep -u mc4 -f paper-26.2-121.jar || true)
  s=$(pgrep -u mc5 -f paper-26.2-121.jar || true)
  echo "t=$i mc4=${c:-none} mc5=${s:-none}"
  if [ -n "$c" ] && [ -n "$s" ]; then
    echo both-java-up
    break
  fi
  sleep 3
done

wait_done() {
  local log="$1" name="$2"
  for i in $(seq 1 40); do
    if grep -q 'Done (' "$log" 2>/dev/null; then
      echo "$name done-line found"
      return 0
    fi
    sleep 3
  done
  echo "$name still starting"
}

wait_done /home/minecraft/multicraft/servers/Creative/logs/latest.log Creative
wait_done /home/minecraft/multicraft/servers/survival/logs/latest.log Survival

echo '---CREATIVE---'
grep -E 'NMinimap|PacketEvents|AnvilORM|Done \(|ERROR|Exception' /home/minecraft/multicraft/servers/Creative/logs/latest.log | tail -n 50 || true
echo '---SURVIVAL---'
grep -E 'NMinimap|PacketEvents|AnvilORM|Done \(|ERROR|Exception' /home/minecraft/multicraft/servers/survival/logs/latest.log | tail -n 50 || true
echo '---PACKS---'
ls -la /home/minecraft/multicraft/servers/Creative/plugins/NMinimap/built-pack.zip 2>/dev/null || echo 'creative pack missing'
ls -la /home/minecraft/multicraft/servers/survival/plugins/NMinimap/built-pack.zip 2>/dev/null || echo 'survival pack missing'
