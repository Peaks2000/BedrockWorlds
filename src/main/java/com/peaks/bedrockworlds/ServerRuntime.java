// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;

enum ServerRuntime {
    WINDOWS("win", "0c04d5b34ef72a30a18847a9fc9324b0be24b6ff80c3531af841eb81848295c5"),
    LINUX("linux", "b40b04c8f5a7c42a19ee685c22fc593e66116b9c22741b8c75489306b252ff62"),
    MAC(LINUX.platform, LINUX.checksum);

    static final String VERSION = "1.26.63.1";
    static final URI DOWNLOAD_PAGE = URI.create("https://www.minecraft.net/en-us/download/server/bedrock");
    private static final Set<String> SERVER_SETTINGS = Set.of("allowlist.json", "server.properties", "permissions.json");
    private final String platform;
    private final String checksum;

    ServerRuntime(String platform, String checksum) {
        this.platform = platform;
        this.checksum = checksum;
    }

    static ServerRuntime current() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch");
        if (os.startsWith("mac")) {
            if (arch.equals("aarch64") || arch.equals("arm64")) {
                return MAC;
            }
            throw new IOException("This ViaFabricPlusPeaks build requires Apple Silicon for macOS NetherNet.");
        }
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            if (os.startsWith("win")) {
                return WINDOWS;
            }
            if (os.startsWith("linux")) {
                return LINUX;
            }
        }
        throw new IOException("Bedrock Worlds requires x64 Windows, x64 Linux, or macOS with Docker Desktop.");
    }

    boolean installed(Path root) {
        return Files.isRegularFile(root.resolve(".server-" + platform + "-" + VERSION))
            && Files.isRegularFile(root.resolve(executable()));
    }

    void install(Path root) throws IOException, InterruptedException {
        if (installed(root)) {
            return;
        }
        Files.deleteIfExists(root.resolve(".server-" + platform + "-" + VERSION));
        Path staging = Files.createTempDirectory(root, ".server-install-");
        try {
            Path zip = staging.resolve("server.zip");
            URI url = URI.create("https://www.minecraft.net/bedrockdedicatedserver/bin-" + platform
                + "/bedrock-server-" + VERSION + ".zip");
            HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(3))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", DOWNLOAD_PAGE.toString()).build();
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
                var response = client.send(request, HttpResponse.BodyHandlers.ofFile(zip));
                if (response.statusCode() != 200) {
                    throw new IOException("Mojang server download returned HTTP " + response.statusCode() + ".");
                }
            }
            if (!checksum.equals(sha256(zip))) {
                throw new IOException("The Mojang server download failed its SHA-256 check.");
            }

            Path extracted = Files.createDirectory(staging.resolve("extracted"));
            try (var archive = new ZipInputStream(Files.newInputStream(zip))) {
                for (var entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                    Path path = extracted.resolve(entry.getName()).normalize();
                    if (!path.startsWith(extracted) || path.equals(extracted)) {
                        throw new IOException("Invalid path in Bedrock server archive.");
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(path);
                    } else {
                        Files.createDirectories(path.getParent());
                        Files.copy(archive, path);
                    }
                }
            }

            // Keep mutable world data outside the set of paths owned by the server archive.
            try (var paths = Files.list(extracted)) {
                for (Path path : paths.toList()) {
                    String name = path.getFileName().toString();
                    if (SERVER_SETTINGS.contains(name) || name.equals("worlds")) {
                        continue;
                    }
                    Path target = root.resolve(name);
                    WorldStore.deleteTree(target);
                    Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
                }
            }
            if (this != WINDOWS && !root.resolve(executable()).toFile().setExecutable(true, true)) {
                throw new IOException("Could not make the Bedrock server executable.");
            }
            Files.createFile(root.resolve(".server-" + platform + "-" + VERSION));
        } finally {
            WorldStore.deleteTree(staging);
        }
    }

    ProcessBuilder launch(Path root, int port, int udpPort, String container) {
        ProcessBuilder builder;
        if (this == MAC) {
            String source = root.toAbsolutePath().toString().replace("\"", "\"\"");
            builder = new ProcessBuilder(docker(), "run", "--rm", "--interactive", "--platform", "linux/amd64",
                "--name", container, "--mount", "type=bind,\"source=" + source + "\",target=/server",
                "-p", "127.0.0.1:" + port + ":" + port + "/tcp",
                "-p", "127.0.0.1:" + udpPort + ":" + udpPort + "/udp",
                "-w", "/server", "-e", "LD_LIBRARY_PATH=/server", "ubuntu:24.04", "./bedrock_server");
        } else {
            builder = new ProcessBuilder(root.resolve(executable()).toAbsolutePath().toString());
            if (this == LINUX) {
                builder.environment().put("LD_LIBRARY_PATH", root.toAbsolutePath().toString());
            }
        }
        return builder.directory(root.toFile()).redirectErrorStream(true);
    }

    static String docker() {
        for (String path : List.of("/usr/local/bin/docker", "/opt/homebrew/bin/docker",
            System.getProperty("user.home") + "/.docker/bin/docker",
            "/Applications/Docker.app/Contents/Resources/bin/docker")) {
            if (Files.isExecutable(Path.of(path))) {
                return path;
            }
        }
        return "docker";
    }

    private String executable() {
        return this == WINDOWS ? "bedrock_server.exe" : "bedrock_server";
    }

    private static String sha256(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = in.read(buffer)) != -1;) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
