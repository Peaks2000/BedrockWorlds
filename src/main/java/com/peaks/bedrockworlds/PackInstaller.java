// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;

final class PackInstaller {
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 50_000;

    private long unpacked;
    private int entries;
    private final Set<UUID> ids = new HashSet<>();
    private final JsonArray behaviors = new JsonArray();
    private final JsonArray resources = new JsonArray();

    static void install(Path world, List<Path> archives) throws IOException {
        install(world, archives, List.of());
    }

    static void install(Path world, List<Path> archives, List<Path> directories) throws IOException {
        if (archives.isEmpty() && directories.isEmpty()) {
            return;
        }

        PackInstaller installer = new PackInstaller();
        installer.loadReferences(world.resolve("world_behavior_packs.json"), installer.behaviors);
        installer.loadReferences(world.resolve("world_resource_packs.json"), installer.resources);
        installer.loadInstalledPacks(world);
        Path temporary = Files.createTempDirectory(world, ".packs-");
        try {
            for (Path archive : archives) {
                installer.installArchive(world, archive, temporary);
            }
            for (Path directory : directories) {
                Path copy = Files.createTempDirectory(temporary, "marketplace-pack-");
                WorldStore.copyTree(directory, copy);
                installer.installManifest(world, copy.resolve("manifest.json"));
            }
            Files.writeString(world.resolve("world_behavior_packs.json"), WorldStore.JSON.toJson(installer.behaviors));
            Files.writeString(world.resolve("world_resource_packs.json"), WorldStore.JSON.toJson(installer.resources));
        } finally {
            WorldStore.deleteTree(temporary);
        }
    }

    private void loadReferences(Path file, JsonArray target) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            for (var entry : JsonParser.parseReader(reader).getAsJsonArray()) {
                UUID.fromString(entry.getAsJsonObject().get("pack_id").getAsString());
                target.add(entry.deepCopy());
            }
        } catch (RuntimeException exception) {
            throw new IOException("The world template has an unreadable pack list.");
        }
    }

    private void loadInstalledPacks(Path world) throws IOException {
        for (String folder : List.of("behavior_packs", "resource_packs")) {
            Path root = world.resolve(folder);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (Path manifest : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("manifest.json")).toList()) {
                    if (Files.size(manifest) > 1024 * 1024) {
                        throw new IOException("The pack manifest is too large.");
                    }
                    try (Reader reader = Files.newBufferedReader(manifest)) {
                        UUID id = UUID.fromString(JsonParser.parseReader(reader).getAsJsonObject()
                            .getAsJsonObject("header").get("uuid").getAsString());
                        if (!ids.add(id)) {
                            throw new IOException("The world template contains duplicate Bedrock pack IDs.");
                        }
                    }
                }
            } catch (RuntimeException exception) {
                throw new IOException("The world template contains an unreadable pack manifest.", exception);
            }
        }
    }

    private static JsonObject reference(JsonArray references, UUID id) {
        for (var entry : references) {
            var value = entry.getAsJsonObject();
            if (UUID.fromString(value.get("pack_id").getAsString()).equals(id)) {
                return value;
            }
        }
        return null;
    }

    private static String versionKey(com.google.gson.JsonElement version) {
        if (version.isJsonArray()) {
            return String.join(".", version.getAsJsonArray().asList().stream()
                .map(com.google.gson.JsonElement::getAsString).toList());
        }
        return version.getAsString();
    }

    private void installArchive(Path world, Path archive, Path temporary) throws IOException {
        String filename = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".mcaddon") && !filename.endsWith(".mcpack")) {
            throw new IOException("Select a .mcaddon or .mcpack file.");
        }

        Path unpackedArchive = Files.createTempDirectory(temporary, "pack-");
        List<Path> manifests = extract(archive, unpackedArchive);
        if (filename.endsWith(".mcaddon")) {
            List<Path> nested;
            try (var paths = Files.walk(unpackedArchive)) {
                nested = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mcpack"))
                    .filter(path -> manifests.stream().noneMatch(manifest -> path.startsWith(manifest.getParent())))
                    .toList();
            }
            for (Path pack : nested) {
                installArchive(world, pack, temporary);
            }
            if (!nested.isEmpty() && manifests.isEmpty()) {
                return;
            }
        }
        if (manifests.isEmpty()) {
            throw new IOException("The add-on contains no Bedrock pack manifest.");
        }
        for (Path manifest : manifests) {
            if (manifests.stream().anyMatch(other -> !other.equals(manifest)
                && manifest.startsWith(other.getParent()))) {
                throw new IOException("The add-on contains overlapping pack folders.");
            }
        }
        for (Path manifest : manifests) {
            installManifest(world, manifest);
        }
    }

    private List<Path> extract(Path archive, Path destination) throws IOException {
        List<Path> manifests = new ArrayList<>();
        try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
            byte[] buffer = new byte[32 * 1024];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("The selected add-ons contain too many files.");
                }
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination) || target.equals(destination)
                    || entry.getName().contains("\\") || entry.getName().contains(":")) {
                    throw new IOException("The add-on contains an invalid file path.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                Files.createDirectories(target.getParent());
                try (var output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE)) {
                    for (int count; (count = zip.read(buffer)) != -1;) {
                        unpacked += count;
                        if (unpacked > MAX_BYTES) {
                            throw new IOException("The selected add-ons exceed 512 MiB when unpacked.");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                if (target.getFileName().toString().equals("manifest.json")) {
                    manifests.add(target);
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("The add-on contains an invalid ZIP entry.", exception);
        }
        return manifests;
    }

    private void installManifest(Path world, Path manifest) throws IOException {
        Path directory = manifest.getParent();
        Path contents = directory.resolve("contents.json");
        if (Files.isRegularFile(contents)) {
            try (var input = Files.newInputStream(contents)) {
                byte[] header = input.readNBytes(8);
                if (header.length == 8 && (header[4] & 0xff) == 0xfc && (header[5] & 0xff) == 0xb9
                    && (header[6] & 0xff) == 0xcf && (header[7] & 0xff) == 0x9b) {
                    throw new IOException("This add-on requires Marketplace activation in Minecraft Bedrock.");
                }
            }
        }

        try (Reader reader = Files.newBufferedReader(manifest)) {
            if (Files.size(manifest) > 1024 * 1024) {
                throw new IOException("The pack manifest is too large.");
            }
            JsonObject pack = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject header = pack.getAsJsonObject("header");
            UUID id = UUID.fromString(header.get("uuid").getAsString());
            var version = header.get("version");
            if (version == null || !(version.isJsonArray() && version.getAsJsonArray().size() == 3
                || version.isJsonPrimitive() && version.getAsJsonPrimitive().isString())) {
                throw new IOException("The pack manifest has an invalid version.");
            }
            boolean behavior = false;
            boolean resource = false;
            for (var module : pack.getAsJsonArray("modules")) {
                String type = module.getAsJsonObject().get("type").getAsString();
                behavior |= type.equals("data") || type.equals("script");
                resource |= type.equals("resources");
            }
            if (behavior == resource) {
                throw new IOException("Each add-on pack must be a behavior pack or a resource pack.");
            }
            if (!ids.add(id)) {
                throw new IOException("Two selected packs have the same Bedrock pack ID.");
            }
            JsonArray target = behavior ? behaviors : resources;
            if (reference(behavior ? resources : behaviors, id) != null) {
                throw new IOException("The selected pack's type conflicts with the world template.");
            }
            JsonObject existing = reference(target, id);
            if (existing != null && !versionKey(version).equals(versionKey(existing.get("version")))) {
                throw new IOException("The selected pack's version conflicts with the world template.");
            }
            if (existing == null) {
                JsonObject reference = new JsonObject();
                reference.addProperty("pack_id", id.toString());
                reference.add("version", version.deepCopy());
                target.add(reference);
            }
            Path packs = Files.createDirectories(world.resolve(behavior ? "behavior_packs" : "resource_packs"));
            Files.move(directory, packs.resolve(id.toString()));
        } catch (RuntimeException exception) {
            throw new IOException("The add-on contains an unreadable pack manifest.", exception);
        }
    }
}
