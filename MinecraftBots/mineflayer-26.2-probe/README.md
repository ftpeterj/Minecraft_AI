# Mineflayer 26.2 probe

Unofficial join test against the LAN Paper **26.2** servers.

Official Mineflayer 4.38.0 only documents support through **26.1**. This folder:

1. Installs `mineflayer@^4.38.0` (pulls `prismarine-chunk@1.41+`, which already reads the 26.x `fluidCount` field).
2. Clones `minecraft-data` **26.1** to **26.2** and sets protocol **776**.
3. Aliases prismarine-chunk **26.2** to the 1.18/26.1 chunk implementation.
4. Tries a Microsoft-authenticated join to Creative (`minecraft.local:25565`).

```
cd C:\Projects\MinecraftBots\mineflayer-26.2-probe
npm install
node join.js
```

Complete the Microsoft device-code login. Use `KingOfThisHouse` or `BigMuddyPuddle` (already whitelisted), or whitelist the new account after the first kick.

Env overrides: `MC_HOST`, `MC_PORT`, `MC_VERSION`, `MC_AUTH`, `MC_USER`.
