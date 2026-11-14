// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.viaversion.viafabricplus.util.bedrock.BedrockHttpSignaling;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class WorldServer {
    private final Path root;
    private final WorldStore.World world;
    private final String container = "peaks-bedrock-" + java.util.UUID.randomUUID();
    private volatile boolean cancelled;
    private volatile Process process;
    private ServerRuntime runtime;
    private FileChannel lockFile;

    WorldServer(Path root, WorldStore.World world) {
        this.root = root;
        this.world = world;
    }

    int start(String gamertag, String xuid, boolean downloadApproved)
        throws IOException, InterruptedException {
        if (cancelled) {
            throw new InterruptedException();
        }
        runtime = ServerRuntime.current();
        Files.createDirectories(root);
        lockFile = FileChannel.open(root.resolve(".server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        if (lockFile.tryLock() == null) {
            throw new IOException("Another Minecraft instance is running a server from this folder.");
        }
        if (!runtime.installed(root) && !downloadApproved) {
            throw new IOException("The server download needs your approval. Open the world again to review the terms.");
        }
        runtime.install(root);

        int port;
        int udpPort;
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (var tcp = new ServerSocket(0, 1, loopback); var udp = new DatagramSocket(0, loopback)) {
            port = tcp.getLocalPort();
            udpPort = udp.getLocalPort();
        }
        configure(gamertag, xuid, port, udpPort);
        synchronized (this) {
            if (cancelled) {
                throw new InterruptedException();
            }
            ProcessBuilder launch = runtime.launch(root, port, udpPort, container)
                .redirectOutput(root.resolve("bedrock-server.log").toFile());
            try {
                process = launch.start();
            } catch (IOException exception) {
                String message = runtime == ServerRuntime.MAC
                    ? "Install and start Docker Desktop to run the Bedrock server."
                    : "Could not start Bedrock Dedicated Server. Check the OS requirements and server files.";
                throw new IOException(message, exception);
            }
        }
        awaitReady(port);
        return port;
    }

    void cancel() {
        cancelled = true;
    }

    boolean exited() {
        Process server = process;
        return server != null && !server.isAlive();
    }

    synchronized void stop() {
        cancel();
        Process server = process;
        if (server == null) {
            return;
        }
        try {
            if (server.isAlive()) {
                try (var input = server.getOutputStream()) {
                    input.write("stop\n".getBytes(StandardCharsets.US_ASCII));
                    input.flush();
                    server.waitFor(12, TimeUnit.SECONDS);
                } catch (IOException ignored) {
                }
            }
            if (runtime == ServerRuntime.MAC && (server.isAlive() || server.exitValue() != 0)) {
                Process remove = new ProcessBuilder(ServerRuntime.docker(), "rm", "-f", container)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!remove.waitFor(5, TimeUnit.SECONDS)) {
                    remove.destroyForcibly();
                }
            }
            if (server.isAlive()) {
                server.destroy();
                if (!server.waitFor(2, TimeUnit.SECONDS)) {
                    server.destroyForcibly();
                    server.waitFor(3, TimeUnit.SECONDS);
                }
            }
        } catch (IOException | InterruptedException exception) {
            server.destroyForcibly();
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void release() {
        try {
            if (lockFile != null) {
                lockFile.close();
            }
        } catch (IOException exception) {
            BedrockWorlds.LOG.warn("Could not close the Bedrock server lock.", exception);
        }
    }

    private void configure(String gamertag, String xuid, int port, int udpPort) throws IOException {
        var options = world.options();
        boolean docker = runtime == ServerRuntime.MAC;
        String udpMapping = docker ? "127.0.0.1:" + udpPort + ":" + udpPort : Integer.toString(udpPort);
        String properties = """
            server-name=Bedrock Worlds
            gamemode=%s
            difficulty=%s
            allow-cheats=%s
            max-players=1
            online-mode=true
            allow-list=true
            server-port=%d
            server-ip=%s
            server-udp-ports=%s
            enable-lan-visibility=false
            transport=nethernet
            level-name=%s
            level-seed=%s
            content-log-file-enabled=true
            emit-server-telemetry=false
            """.formatted(options.gameMode(), options.difficulty(), options.cheats(), port, docker ? "0.0.0.0" : "127.0.0.1", udpMapping,
            world.directory().getFileName(), options.seed());
        Files.writeString(root.resolve("server.properties"), properties);

        JsonObject entry = new JsonObject();
        entry.addProperty("name", gamertag);
        entry.addProperty("xuid", xuid);
        entry.addProperty("ignoresPlayerLimit", false);
        JsonArray allowlist = new JsonArray();
        allowlist.add(entry);
        Files.writeString(root.resolve("allowlist.json"), WorldStore.JSON.toJson(allowlist));

        JsonArray permissions = new JsonArray();
        if (options.cheats()) {
            JsonObject permission = new JsonObject();
            permission.addProperty("permission", "operator");
            permission.addProperty("xuid", xuid);
            permissions.add(permission);
        }
        Files.writeString(root.resolve("permissions.json"), WorldStore.JSON.toJson(permissions));
    }

    private void awaitReady(int port) throws IOException, InterruptedException {
        long timeout = System.nanoTime() + Duration.ofMinutes(runtime == ServerRuntime.MAC ? 5 : 2).toNanos();
        var address = new InetSocketAddress("127.0.0.1", port);
        while (System.nanoTime() < timeout && !cancelled) {
            if (exited()) {
                throw new IOException("Bedrock server exited during startup. See bedrock-server.log.");
            }
            if (BedrockHttpSignaling.probe(address) != null) {
                return;
            }
            Thread.sleep(250);
        }
        if (cancelled) {
            throw new InterruptedException();
        }
        throw new IOException("Bedrock server startup timed out. See bedrock-server.log.");
    }
}
