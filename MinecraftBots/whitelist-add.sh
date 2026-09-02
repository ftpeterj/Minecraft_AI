#!/bin/bash
set -euo pipefail
NAME="${1:?name required}"

rcon() {
  local props="$1"
  shift
  local port pw
  port=$(grep '^rcon.port=' "$props" | cut -d= -f2)
  pw=$(grep '^rcon.password=' "$props" | cut -d= -f2-)
  mcrcon -H 127.0.0.1 -P "$port" -p "$pw" "$@"
}

echo "=== Creative whitelist add $NAME ==="
rcon /home/minecraft/multicraft/servers/Creative/server.properties "whitelist add $NAME"
rcon /home/minecraft/multicraft/servers/Creative/server.properties "whitelist reload"
rcon /home/minecraft/multicraft/servers/Creative/server.properties "whitelist list"

echo "=== Survival whitelist add $NAME ==="
rcon /home/minecraft/multicraft/servers/survival/server.properties "whitelist add $NAME"
rcon /home/minecraft/multicraft/servers/survival/server.properties "whitelist reload"
rcon /home/minecraft/multicraft/servers/survival/server.properties "whitelist list"

echo "=== json ==="
echo Creative:
cat /home/minecraft/multicraft/servers/Creative/whitelist.json
echo
echo Survival:
cat /home/minecraft/multicraft/servers/survival/whitelist.json
echo
