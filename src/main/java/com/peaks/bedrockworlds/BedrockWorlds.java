// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.viaversion.viafabricplus.injection.access.core.IServerData;
import com.viaversion.viafabricplus.save.SaveManager;
import com.viaversion.viafabricplus.util.bedrock.BedrockProtocolCompatibility;
import com.viaversion.viafabricplus.util.bedrock.WebRtcNativeLibrary;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BedrockWorlds implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("Bedrock Worlds");
    static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Bedrock Worlds server worker");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Session active;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(BedrockWorlds::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            Session session = active;
            if (session != null && listener.getServerData() == session.data) {
                stop(session);
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
        Runtime.getRuntime().addShutdownHook(new Thread(BedrockWorlds::shutdown, "Bedrock Worlds shutdown"));
    }

    static WorldStore store(Minecraft client) {
        return new WorldStore(client.gameDirectory.toPath());
    }

    static void start(Minecraft client, WorldListScreen parent, WorldStore.World world, boolean downloadApproved) {
        if (active != null) {
            parent.setStatus("Wait for the current local server to stop.");
            return;
        }

        BedrockAuthManager account = SaveManager.INSTANCE.getAccountsSave().getBedrockAccount();
        if (account == null) {
            parent.setStatus("Link a Bedrock account in ViaFabricPlusPeaks first.");
            return;
        }

        try {
            WebRtcNativeLibrary.ensureAvailable();
        } catch (IllegalStateException exception) {
            parent.setStatus("This ViaFabricPlusPeaks build is missing the WebRTC native for this platform.");
            return;
        }
        WorldServer server = new WorldServer(store(client).root(), world);
        Session session = new Session(parent, server);
        active = session;
        client.gui.setScreen(new WorldStatusScreen("Starting Bedrock server…", () -> {
            stop(session);
            client.gui.setScreen(parent);
        }));
        Screen progress = client.gui.screen();
        WORKER.execute(() -> {
            try {
                var required = WorldStore.marketplace(world);
                if (!required.isEmpty()) {
                    var owned = MarketplaceLibrary.read(account, client.getLanguageManager().getSelected()).stream()
                        .filter(item -> item.id() != null).map(item -> item.id().toString()).toList();
                    if (!owned.containsAll(required)) {
                        throw new IOException("The linked account no longer owns all Marketplace packs used by this world.");
                    }
                }
                var identity = account.getMinecraftMultiplayerToken().getUpToDate();
                int port = server.start(identity.getDisplayName(), identity.getXuid(), downloadApproved);
                client.execute(() -> {
                    if (active != session || session.stopping || client.gui.screen() != progress) {
                        stop(session);
                        return;
                    }
                    if (account != SaveManager.INSTANCE.getAccountsSave().getBedrockAccount()) {
                        stop(session);
                        parent.setStatus("The linked Bedrock account changed. Open the world again.");
                        client.gui.setScreen(parent);
                        return;
                    }
                    try {
                        connect(client, session, world.options().name(), port);
                    } catch (RuntimeException failure) {
                        stop(session);
                        parent.setStatus("Could not open the local Bedrock connection.");
                        client.gui.setScreen(parent);
                    }
                });
            } catch (Exception exception) {
                if (!(exception instanceof InterruptedException)) {
                    // Authentication exceptions may carry service response bodies.
                    String message = exception instanceof IOException io && io.getClass() == IOException.class
                        ? io.getMessage() : "Could not start the local server or sign in to Bedrock.";
                    LOG.warn("Bedrock Worlds startup failed ({})", exception.getClass().getSimpleName());
                    client.execute(() -> {
                        if (active == session && !session.stopping && client.gui.screen() == progress) {
                            parent.setStatus(message);
                            client.gui.setScreen(parent);
                        }
                    });
                }
                client.execute(() -> stop(session));
            }
        });
    }

    private static void connect(Minecraft client, Session session, String name, int port) {
        String route = "127.0.0.1:" + port;
        ServerData data = new ServerData(name, route, ServerData.Type.OTHER);
        IServerData bedrock = (IServerData) data;
        bedrock.viaFabricPlus$forceVersion(BedrockProtocolVersion.BEDROCK_LATEST);
        int protocol = BedrockProtocolCompatibility.protocolForGameVersion(ServerRuntime.VERSION);
        if (protocol == BedrockProtocolCompatibility.UNKNOWN_PROTOCOL) {
            throw new IllegalStateException("This ViaFabricPlusPeaks build does not support the installed server version.");
        }
        bedrock.viaFabricPlus$setBedrockWireProtocol(protocol);
        data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
        bedrock.viaFabricPlus$setUseBedrockAccount(true);
        session.data = data;
        session.loginDeadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        ConnectScreen.startConnecting(session.parent, client, ServerAddress.parseString(route), data, false, null);
    }

    public static java.net.InetSocketAddress localServerAddress(String host, int port) {
        Session session = active;
        if (session == null || session.stopping || session.data == null
            || !"127.0.0.1".equals(host) || !session.data.ip.equals(host + ":" + port)
            || session.server.endpoint() == null) {
            return null;
        }
        return new com.viaversion.viafabricplus.util.bedrock.NetherNetInetSocketAddress(
            new java.net.InetSocketAddress(host, port), session.server.endpoint());
    }

    public static void bindResourceConnection(ServerData data, net.minecraft.network.Connection connection) {
        Session session = active;
        if (session != null && !session.stopping && data != null && session.data == data) {
            session.resourceUser = ((com.viaversion.viafabricplus.injection.access.core.IConnection) connection)
                .viaFabricPlus$getUserConnection();
        }
    }

    public static boolean ownsResourcePackStorage(Object storage) {
        Session session = active;
        var user = session == null ? null : session.resourceUser;
        return session != null && !session.stopping && user != null && storage != null
            && user.get(net.raphimc.viabedrock.protocol.storage.ResourcePackStorage.class) == storage;
    }

    public static void convertVanillaTextures(net.raphimc.viabedrock.protocol.storage.ResourcePackStorage storage,
        net.raphimc.viabedrock.api.resourcepack.content.Content target) {
        if (!ownsResourcePackStorage(storage)) {
            return;
        }
        var sources = storage.getPackStackBottomToTop().stream().map(pack -> new VanillaTextures.Source(
            pack.content().getFilesDeep("textures/", ""), pack.content()::get)).toList();
        int copied = 0;
        var protectedTextures = java.util.Set.copyOf(target.getFilesDeep("assets/minecraft/textures/", ".png"));
        for (var asset : VanillaTextures.convert(sources).entrySet()) {
            String texture = asset.getKey().endsWith(".mcmeta")
                ? asset.getKey().substring(0, asset.getKey().length() - ".mcmeta".length()) : asset.getKey();
            if (!protectedTextures.contains(texture) && !target.contains(asset.getKey())) {
                target.put(asset.getKey(), asset.getValue());
                copied++;
            }
        }
        LOG.info("Added {} vanilla texture assets to the managed Bedrock world resource pack", copied);
    }

    private static void tick(Minecraft client) {
        Session session = active;
        if (session == null || session.stopping || session.data == null) {
            return;
        }
        Screen screen = client.gui.screen();
        var listener = client.getConnection();
        boolean localConnection = listener != null && listener.getServerData() == session.data;
        if (localConnection) {
            session.joined = true;
        }
        if (session.server.exited() || screen == session.parent || screen instanceof DisconnectedScreen
            || (session.joined && !localConnection)) {
            stop(session);
        } else if (!session.joined && System.nanoTime() >= session.loginDeadline) {
            stop(session);
            session.parent.setStatus("Timed out connecting to the local Bedrock server.");
            client.disconnect(session.parent, false);
        }
    }

    private static void stop(Session session) {
        if (active != session || session.stopping) {
            return;
        }
        session.stopping = true;
        session.server.cancel();
        WORKER.execute(() -> {
            try {
                session.server.stop();
            } finally {
                session.server.release();
                if (active == session) {
                    active = null;
                }
            }
        });
    }

    private static void shutdown() {
        Session session = active;
        if (session != null) {
            session.server.cancel();
            session.server.stop();
            session.server.release();
        }
    }

    private static final class Session {
        final WorldListScreen parent;
        final WorldServer server;
        volatile boolean stopping;
        ServerData data;
        volatile com.viaversion.viaversion.api.connection.UserConnection resourceUser;
        long loginDeadline;
        boolean joined;

        Session(WorldListScreen parent, WorldServer server) {
            this.parent = parent;
            this.server = server;
        }
    }
}
