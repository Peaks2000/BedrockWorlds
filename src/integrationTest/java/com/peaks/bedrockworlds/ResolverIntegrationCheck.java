// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.viaversion.viafabricplus.injection.access.core.IServerData;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl;
import com.viaversion.viafabricplus.util.bedrock.CompatibilityViaBedrockRuntime;
import com.viaversion.viafabricplus.util.bedrock.NetherNetInetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;

public final class ResolverIntegrationCheck implements ClientModInitializer {
    private boolean ran;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ran || client.gui.overlay() != null || client.gui.screen() == null) {
                return;
            }
            ran = true;
            try {
                check(client);
                System.out.println("RESOLVER RESULT PASS");
            } catch (Exception exception) {
                throw new IllegalStateException("Resolver integration check failed", exception);
            } finally {
                client.stop();
            }
        });
    }

    private static void check(Minecraft client) throws Exception {
        var active = BedrockWorlds.class.getDeclaredField("active");
        active.setAccessible(true);
        if (active.get(null) != null) {
            throw new IllegalStateException("Run the resolver check in an isolated development client.");
        }
        var previous = ProtocolTranslator.getTargetVersion();
        Path root = Files.createTempDirectory("bedrock-resolver-check-");
        try {
            var world = new WorldStore.World(root,
                new WorldStore.Options("Resolver fixture", "", "survival", "normal", false));
            WorldServer server = new WorldServer(root, world);
            URI endpoint = URI.create("http://127.0.0.1:29998/v1/join");
            var serverEndpoint = WorldServer.class.getDeclaredField("endpoint");
            serverEndpoint.setAccessible(true);
            serverEndpoint.set(server, endpoint);
            Class<?> sessionClass = Class.forName("com.peaks.bedrockworlds.BedrockWorlds$Session");
            var constructor = sessionClass.getDeclaredConstructor(WorldListScreen.class, WorldServer.class);
            constructor.setAccessible(true);
            Object session = constructor.newInstance(new WorldListScreen(client.gui.screen()), server);
            var dataField = sessionClass.getDeclaredField("data");
            dataField.setAccessible(true);
            var data = new ServerData("Resolver fixture", "127.0.0.1:29998", ServerData.Type.OTHER);
            ((IServerData) data).viaFabricPlus$forceVersion(BedrockProtocolVersion.BEDROCK_LATEST);
            dataField.set(session, data);
            active.set(null, session);
            var versions = new LinkedHashSet<>(List.of(ProtocolTranslationImpl.NATIVE_VERSION,
                BedrockProtocolVersion.BEDROCK_LATEST, CompatibilityViaBedrockRuntime.compatibilityVersion()));
            int failures = 0;
            for (var version : versions) {
                ProtocolTranslator.setTargetVersion(version);
                var resolved = resolve("127.0.0.1", 29998);
                boolean correct = resolved instanceof NetherNetInetSocketAddress local
                    && endpoint.equals(local.httpEndpoint());
                failures += result(correct, "managed endpoint with " + version.getName());
                failures += result(!(resolve("127.0.0.1", 29999) instanceof NetherNetInetSocketAddress),
                    "unmanaged port with " + version.getName());
                failures += result(!(resolve("localhost", 29998) instanceof NetherNetInetSocketAddress),
                    "unmanaged hostname with " + version.getName());
            }
            var stopping = sessionClass.getDeclaredField("stopping");
            stopping.setAccessible(true);
            stopping.set(session, true);
            failures += result(!(resolve("127.0.0.1", 29998) instanceof NetherNetInetSocketAddress), "stopping server");
            stopping.set(session, false);
            serverEndpoint.set(server, null);
            failures += result(!(resolve("127.0.0.1", 29998) instanceof NetherNetInetSocketAddress), "missing endpoint");
            active.set(null, null);
            failures += result(!(resolve("127.0.0.1", 29998) instanceof NetherNetInetSocketAddress), "no managed session");
            if (failures != 0) {
                throw new AssertionError(failures + " resolver integration checks failed");
            }
        } finally {
            active.set(null, null);
            ProtocolTranslator.setTargetVersion(previous);
            WorldStore.deleteTree(root);
        }
    }

    private static java.net.InetSocketAddress resolve(String host, int port) {
        return ServerNameResolver.DEFAULT.resolveAddress(new ServerAddress(host, port)).orElseThrow()
            .asInetSocketAddress();
    }

    private static int result(boolean success, String name) {
        System.out.println("RESOLVER " + (success ? "PASS " : "FAIL ") + name);
        return success ? 0 : 1;
    }
}
