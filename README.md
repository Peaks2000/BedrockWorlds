# Bedrock Worlds for ViaFabricPlusPeaks

Bedrock Worlds is a separate Fabric client mod for Minecraft Java 26.3 and Java 25. Install the add-on JAR beside ViaFabricPlusPeaks 6, including the 6.0.0 beta builds. ViaFabricPlusPeaks 5.2 remains supported.

1. Link your Microsoft Bedrock account in ViaFabricPlusPeaks.
2. Open **Singleplayer → Bedrock Worlds → Create New World**. Choose the name, seed, game mode and difficulty.
3. Open **Add-ons → Marketplace** to select owned content from the linked account. World templates supply the map; add-ons and resource packs are enabled on the new world. You can also import server-compatible `.mcaddon` and `.mcpack` files by dropping them on the screen or using **Open Folder**. **Marketplace** is also available directly on the Bedrock Worlds list.
4. Choose **Create World**, then select the world from **Bedrock Worlds**. On first play, review Mojang's terms and approve the dedicated-server download.

Worlds live under `Bedrock worlds/worlds` in Minecraft's game directory. Each world has its own UUID folder, Bedrock database, enabled pack lists and copies of the selected packs. The world generates on first play. The companion starts a private Mojang Bedrock Dedicated Server 1.26.63.1 and uses the linked account for its allowlist and the fork's NetherNet login. When you disconnect or close Minecraft normally, the companion sends the server's `stop` command so the world can save.

## Platform setup

- **Windows x64** runs Mojang's Windows server and requires the matching Windows WebRTC native in ViaFabricPlusPeaks.
- **Linux x64** runs Mojang's Linux server. Mojang supports Ubuntu 22.04 or newer.
- **Apple Silicon macOS** uses Docker Desktop to run Mojang's Linux amd64 server. Start Docker Desktop and make the instance directory available for file sharing. The companion publishes the signaling and gameplay ports to loopback. Docker may pull the Ubuntu 24.04 image on the first run.
- Intel macOS and Linux ARM require additional WebRTC or dedicated-server binaries.

Marketplace selection returns to the world creation screen with the content selected. Choose **Create World** to download and prepare it. The library reads the linked account's Minecraft Marketplace entitlements and published PlayFab catalog. Content receipts are requested with the account's master entity token and device ID. Only content owned by that account is selected, and encrypted files require its active entitlement keys. The companion prepares those files locally for BDS without changing ownership markers. Authentication tokens, receipts and keys are never saved in world metadata or uploaded to a third-party service. The linked account's ownership is checked again when opening the world.

Plain and add-on Bedrock worlds use the same local Dedicated Server installation in `Bedrock worlds`; server binaries and vanilla assets are downloaded once per instance, not copied into each world. Existing plain Bedrock worlds keep their directories and saves. Each world has its own mutable database and enabled packs. Delivery archives and temporary decoded template copies are removed after creation. Java singleplayer saves are separate and are not converted.

The server stops on disconnect, cancelled startup or normal VFPP/Minecraft shutdown. Shutdown sends `stop` first and waits for saving before terminating a stuck process. Marketplace content is prepared only in the instance's local storage; it is not bundled in the add-on JAR. Scripts and custom content still depend on Dedicated Server and ViaFabricPlusPeaks compatibility.

To diagnose a server startup error, open `Bedrock worlds/bedrock-server.log` in the instance game directory. The Mojang server binary is downloaded on demand and verified by SHA-256.

## Build

Use JDK 25. Put the ViaFabricPlusPeaks 6.0.0-beta.2 JAR in `libs`, or pass its path to Gradle.

```sh
./gradlew -PviaFabricPlusJar=/path/to/ViaFabricPlusPeaks-6.0.0-beta.2.jar build
```

On Windows use `gradlew.bat`. The result is `build/libs/bedrock-worlds-0.4.1.jar`.

Marketplace protocol reference: [read-only entitlement request](https://github.com/Daniel-Ric/PlayFab-Purchase-Service/blob/8ff0ecf82fbaeea756c6ccf35b13f930982c1e79/src/services/minecraft.service.js) and [PlayFab Get Items](https://learn.microsoft.com/en-us/rest/api/playfab/economy/catalog/get-items?view=playfab-rest).
