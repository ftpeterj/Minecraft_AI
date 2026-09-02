#!/bin/bash
S=/home/minecraft/multicraft/servers/survival
C=/home/minecraft/multicraft/servers/Creative
echo '=== VERSION ==='
grep -E 'Loading Paper|This server is running' "$C/logs/latest.log" | head -n 5
grep -E 'Loading Paper|This server is running' "$S/logs/latest.log" | head -n 5
echo '=== CREATIVE JOINS/KICKS ==='
grep -E 'logged in|UUID of player|lost connection|Kicked|outdated|Outdated|Disconnect|Failed|incompatible|viaversion|protocol' "$C/logs/latest.log" | tail -n 40
echo '=== SURVIVAL JOINS/KICKS ==='
grep -E 'logged in|UUID of player|lost connection|Kicked|outdated|Outdated|Disconnect|Failed|incompatible|viaversion|protocol' "$S/logs/latest.log" | tail -n 40
echo '=== SERVER.PROPERTIES VERSION HINTS ==='
grep -E 'server-port|online-mode|white-list|resource-pack|motd' "$C/server.properties"
grep -E 'server-port|online-mode|white-list|resource-pack|motd' "$S/server.properties"
echo '=== PLUGINS ==='
ls "$C/plugins"/*.jar
ls "$S/plugins"/*.jar
