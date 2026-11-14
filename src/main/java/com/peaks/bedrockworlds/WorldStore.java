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
        options = validate(options);
        Path worlds = Files.createDirectories(root.resolve("worlds"));
        Path staging = Files.createTempDirectory(worlds, ".creating-");
        Path destination = worlds.resolve(UUID.randomUUID().toString());
        try {
            PackInstaller.install(staging, archives);
            Files.writeString(staging.resolve("world.json"), JSON.toJson(options));
            Files.move(staging, destination);
            return new World(destination, options);
        } finally {
            deleteTree(staging);
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
