// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.zip.ZipInputStream;

final class ContentArchive {
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private long bytes;
    private int entries;

    void unpack(Path archive, Path destination, int depth) throws IOException {
        if (depth > 3) {
            throw new IOException("Marketplace content contains too many nested archives.");
        }
        Files.createDirectories(destination);
        try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
            byte[] buffer = new byte[64 * 1024];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 100_000) {
                    throw new IOException("Marketplace content contains too many files.");
                }
                Path target = child(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        for (int count; (count = zip.read(buffer)) != -1;) {
                            bytes += count;
                            if (bytes > MAX_BYTES) {
                                throw new IOException("Marketplace content exceeds 2 GiB when unpacked.");
                            }
                            out.write(buffer, 0, count);
                        }
                    }
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Marketplace content contains an invalid ZIP entry.", exception);
        }
        try (var paths = Files.walk(destination)) {
            for (Path nested : paths.filter(Files::isRegularFile).filter(ContentArchive::isArchive).toList()) {
                // Archives inside a real pack are pack assets, not delivery wrappers.
                boolean inPack = false;
                for (Path p = nested.getParent(); p != null && p.startsWith(destination); p = p.getParent()) {
                    if (Files.isRegularFile(p.resolve("manifest.json"))) {
                        inPack = true;
                        break;
                    }
                }
                if (!inPack) {
                    unpack(nested, Files.createTempDirectory(nested.getParent(), ".content-"), depth + 1);
                    Files.delete(nested);
                }
            }
        }
    }

    static Path child(Path root, String name) throws IOException {
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root) || path.equals(root) || name.contains("\\") || name.contains(":")) {
            throw new IOException("Marketplace content contains an invalid file path.");
        }
        return path;
    }

    private static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".zip") || name.endsWith(".mcpack") || name.endsWith(".mcaddon")
            || name.endsWith(".mcworld") || name.endsWith(".mctemplate");
    }
}
