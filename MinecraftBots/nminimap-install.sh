#!/bin/bash
# Install NMinimap 1.0.8 + deps on Creative (mc4) and Survival (mc5).
set -euo pipefail

CREATIVE=/home/minecraft/multicraft/servers/Creative
SURVIVAL=/home/minecraft/multicraft/servers/survival
STAGING=/tmp/nminimap-jars
mkdir -p "$STAGING"
cd "$STAGING"

echo "Downloading jars..."
curl -fsSL -o NMinimap.jar "https://cdn.modrinth.com/data/lBBekvEQ/versions/woCeIe36/NMinimap.jar"
curl -fsSL -o packetevents-spigot-2.13.0.jar "https://cdn.modrinth.com/data/HYKaKraK/versions/h0ncTpUP/packetevents-spigot-2.13.0.jar"
curl -fsSL -o AnvilORM.jar "https://github.com/NezuShin/AnvilORM/releases/download/V1.0.2/AnvilORM.jar"

echo "Verifying checksums..."
echo "ca5835c41a19e04f1c335306e537fadf41fd21d5  NMinimap.jar" | sha1sum -c -
echo "d4f64e33fa6f35dd96724ffa36e3502550dc6ac3  packetevents-spigot-2.13.0.jar" | sha1sum -c -
echo "d2a510484c0e9be87b2afde808f28f4e3e67462ce1885aa5e94d8d35264f27af  AnvilORM.jar" | sha256sum -c -

install_jars () {
  local dest="$1" owner="$2"
  install -o "$owner" -g "$owner" -m 664 NMinimap.jar "$dest/NMinimap.jar"
  install -o "$owner" -g "$owner" -m 664 packetevents-spigot-2.13.0.jar "$dest/packetevents-spigot-2.13.0.jar"
  install -o "$owner" -g "$owner" -m 664 AnvilORM.jar "$dest/AnvilORM.jar"
  ls -la "$dest"/NMinimap.jar "$dest"/packetevents-spigot-2.13.0.jar "$dest"/AnvilORM.jar
}

echo "Installing into plugin folders..."
install_jars "$CREATIVE/plugins" mc4
install_jars "$SURVIVAL/plugins" mc5

echo "Jars installed."
