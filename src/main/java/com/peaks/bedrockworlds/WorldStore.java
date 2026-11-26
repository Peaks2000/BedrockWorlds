// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

final class WorldStore {
    static final Gson JSON = new Gson();
    private static final Pattern WORLD_ID = Pattern.compile("[0-9a-f-]{36}");

    private final Path root;

    WorldStore(Path instance) {
        root = instance.resolve("Bedrock worlds");
    }

    Path root() {
        return root;
    }

    List<World> list() throws IOException {
        if (!Files.isDirectory(root.resolve("worlds"))) {
            return List.of();
        }

        try (var directories = Files.list(root.resolve("worlds"))) {
            return directories.filter(path -> WORLD_ID.matcher(path.getFileName().toString()).matches())
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .map(this::read)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(world -> world.options().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        }
    }

    private World read(Path path) {
        try (Reader reader = Files.newBufferedReader(path.resolve("world.json"))) {
            return new World(path, validate(JSON.fromJson(reader, Options.class)));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    World create(Options options, List<Path> archives) throws IOException {
        return create(options, archives, List.of(), List.of());
    }

    World create(Options options, List<Path> archives, List<MarketplaceContent.Prepared> content,
                 List<MarketplaceLibrary.Item> marketplace) throws IOException {
        options = validate(options);
        var templates = content.stream().map(MarketplaceContent.Prepared::template)
            .filter(java.util.Objects::nonNull).toList();
        if (templates.size() > 1) {
            throw new IOException("Choose one world template per world.");
        }
        Path worlds = Files.createDirectories(root.resolve("worlds"));
        Path staging = Files.createTempDirectory(worlds, ".creating-");
        Path destination = worlds.resolve(UUID.randomUUID().toString());
        try {
            if (!templates.isEmpty()) {
                copyTree(templates.getFirst(), staging);
            }
            PackInstaller.install(staging, archives, content.stream()
                .flatMap(item -> item.packs().stream()).toList());
            if (!marketplace.isEmpty()) {
                Files.writeString(staging.resolve("marketplace.json"), JSON.toJson(
                    marketplace.stream().map(item -> item.id().toString()).toList()));
            }
            Files.writeString(staging.resolve("world.json"), JSON.toJson(options));
            Files.move(staging, destination);
            return new World(destination, options);
        } finally {
            deleteTree(staging);
        }
    }

    static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Bedrock content cannot contain symbolic links.");
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    static List<String> marketplace(World world) throws IOException {
        Path file = world.directory().resolve("marketplace.json");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            return com.google.gson.JsonParser.parseReader(reader).getAsJsonArray().asList().stream()
                .map(value -> UUID.fromString(value.getAsString()).toString()).toList();
        } catch (RuntimeException exception) {
            throw new IOException("Could not read the world's Marketplace pack list.");
        }
    }

    static void deleteTree(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var tree = Files.walk(path)) {
                for (Path item : tree.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(item);
                }
            }
        }
    }

    private static Options validate(Options options) throws IOException {
        if (options == null || options.name() == null || options.name().isBlank() || options.name().length() > 64
            || options.name().codePoints().anyMatch(Character::isISOControl) || options.seed() == null
            || options.seed().length() > 64 || options.seed().codePoints().anyMatch(Character::isISOControl)
            || !List.of("survival", "creative", "adventure").contains(options.gameMode())
            || !List.of("peaceful", "easy", "normal", "hard").contains(options.difficulty())) {
            throw new IOException("Invalid Bedrock world settings.");
        }
        return options;
    }

    record World(Path directory, Options options) {
    }

    record Options(String name, String seed, String gameMode, String difficulty, boolean cheats) {
    }
}
