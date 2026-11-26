# Bedrock Worlds for ViaFabricPlusPeaks

Bedrock Worlds is a separate Fabric client mod for Minecraft Java 26.3 and Java 25. Install the add-on JAR beside ViaFabricPlusPeaks 6, including the 6.0.0 beta builds. ViaFabricPlusPeaks 5.2 remains supported.

1. Link your Microsoft Bedrock account in ViaFabricPlusPeaks.
2. Open **Singleplayer → Bedrock Worlds → Create New World**. Choose the name, seed, game mode and difficulty.
3. Open **Add-ons** to select server-compatible `.mcaddon` and `.mcpack` files. Drop packs on the screen or copy them into the folder opened by **Open Folder** and click **Refresh**.
4. Choose **Create World**, then select the world from **Bedrock Worlds**. On first play, review Mojang's terms and approve the dedicated-server download.

Worlds live under `Bedrock worlds/worlds` in Minecraft's game directory. Each world has its own UUID folder, Bedrock database, enabled pack lists and copies of the selected packs. The world generates on first play. The companion starts a private Mojang Bedrock Dedicated Server 1.26.63.1 and uses the linked account for its allowlist and the fork's NetherNet login. When you disconnect or close Minecraft normally, the companion sends the server's `stop` command so the world can save.

## Platform setup

- **Windows x64** runs Mojang's Windows server and requires the matching Windows WebRTC native in ViaFabricPlusPeaks.
- **Linux x64** runs Mojang's Linux server. Mojang supports Ubuntu 22.04 or newer.
- **Apple Silicon macOS** uses Docker Desktop to run Mojang's Linux amd64 server. Start Docker Desktop and make the instance directory available for file sharing. The companion publishes the signaling and gameplay ports to loopback. Docker may pull the Ubuntu 24.04 image on the first run.
- Intel macOS and Linux ARM require additional WebRTC or dedicated-server binaries.

Open **Add-ons → Marketplace** to browse the owned world content for your linked Bedrock account. Choose an entry to open its Minecraft Marketplace page. The library uses Minecraft's Marketplace entitlement endpoint and the PlayFab catalog with your existing Bedrock session. Mojang documents activating purchased add-ons in Minecraft Bedrock and Realms. Local Dedicated Server worlds use the separately imported server-compatible pack archives. To play an owned pack over LAN, activate it in Minecraft Bedrock, enable LAN multiplayer on the world, then use **Join Bedrock LAN / Friends** in the companion. The Bedrock client hosts the world for that session. [Marketplace Add-On activation](https://help.minecraft.net/hc/en-us/articles/24120525083533-How-to-activate-Minecraft-Add-On) · [Dedicated Server download and requirements](https://www.minecraft.net/en-us/download/server/bedrock)

To diagnose a server startup error, open `Bedrock worlds/bedrock-server.log` in the instance game directory. The Mojang server binary is downloaded on demand and verified by SHA-256.

## Build

Use JDK 25. Put the ViaFabricPlusPeaks 6.0.0-beta.2 JAR in `libs`, or pass its path to Gradle.

```sh
./gradlew -PviaFabricPlusJar=/path/to/ViaFabricPlusPeaks-6.0.0-beta.2.jar build
```

On Windows use `gradlew.bat`. The result is `build/libs/bedrock-worlds-0.3.1.jar`.

Marketplace protocol reference: [read-only entitlement request](https://github.com/Daniel-Ric/PlayFab-Purchase-Service/blob/8ff0ecf82fbaeea756c6ccf35b13f930982c1e79/src/services/minecraft.service.js) and [PlayFab Get Items](https://learn.microsoft.com/en-us/rest/api/playfab/economy/catalog/get-items?view=playfab-rest).
