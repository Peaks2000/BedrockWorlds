// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.imageio.ImageIO;

final class VanillaTextures {
    private static final Map<String, List<String>> MAPPINGS = mappings();
    private static final java.util.Set<String> MAPPED_SOURCES = MAPPINGS.values().stream()
        .flatMap(List::stream).collect(java.util.stream.Collectors.toUnmodifiableSet());

    record Source(List<String> files, Function<String, byte[]> read) {
    }

    static Map<String, byte[]> convert(List<Source> bottomToTop) {
        Map<String, byte[]> output = new LinkedHashMap<>();
        for (Source pack : bottomToTop) {
            Map<String, byte[]> layer = new LinkedHashMap<>();
            Map<String, Image> decoded = new LinkedHashMap<>();
            Map<String, JsonObject> animations = animations(pack);
            for (var mapping : MAPPINGS.entrySet()) {
                for (String source : mapping.getValue()) {
                    if (copy(pack, decoded, animations, source, mapping.getKey(), layer)) {
                        break;
                    }
                }
            }
            for (String file : pack.files()) {
                if (!file.matches("textures/[a-z0-9_./-]+\\.(png|tga|jpg)")) {
                    continue;
                }
                String base = file.substring(0, file.lastIndexOf('.'));
                String target = base.substring("textures/".length());
                if (target.startsWith("blocks/")) {
                    target = "block/" + target.substring(7);
                } else if (target.startsWith("items/")) {
                    target = "item/" + target.substring(6);
                } else if (target.startsWith("ui/")) {
                    continue;
                }
                // Explicit mappings also describe paths whose Java and Bedrock names differ.
                if (!MAPPINGS.containsKey(target) && !MAPPED_SOURCES.contains(base)) {
                    copy(pack, decoded, animations, base, target, layer);
                }
            }
            EntityTextures.adapt(pack, layer);
            for (String path : layer.keySet()) {
                if (path.endsWith(".png")) {
                    output.remove(path + ".mcmeta");
                }
            }
            output.putAll(layer);
        }
        return output;
    }

    private static boolean copy(Source pack, Map<String, Image> decoded, Map<String, JsonObject> animations,
        String source, String target, Map<String, byte[]> output) {
        if (!source.startsWith("textures/") || source.contains("..") || target.contains("..")
            || target.startsWith("trims/") || target.startsWith("palettes/")) {
            return false;
        }
        Image image;
        if (decoded.containsKey(source)) {
            image = decoded.get(source);
        } else {
            image = image(pack, source);
            decoded.put(source, image);
        }
        if (image == null) {
            return false;
        }
        String path = "assets/minecraft/textures/" + target + ".png";
        output.put(path, image.png());
        output.remove(path + ".mcmeta");
        JsonObject bedrock = animations.get(source);
        if (bedrock == null && source.endsWith("_grey")) {
            bedrock = animations.get(source.substring(0, source.length() - 5));
        }
        if (bedrock != null && image.height() > image.width() && image.height() % image.width() == 0) {
            int frames = image.height() / image.width();
            JsonObject animation = new JsonObject();
            int time = bedrock == null ? 1 : positiveInt(bedrock, "ticks_per_frame",
                positiveInt(bedrock, "ticks_per_frames", 1));
            animation.addProperty("frametime", time);
            if (bedrock.has("blend_frames") && bedrock.get("blend_frames").isJsonPrimitive()
                && bedrock.getAsJsonPrimitive("blend_frames").isBoolean()) {
                animation.addProperty("interpolate", bedrock.get("blend_frames").getAsBoolean());
            }
            if (bedrock != null && bedrock.get("frames") instanceof com.google.gson.JsonArray indices) {
                var valid = new com.google.gson.JsonArray();
                for (var index : indices) {
                    if (index.isJsonPrimitive() && index.getAsJsonPrimitive().isNumber()) {
                        int value = index.getAsInt();
                        if (value >= 0 && value < frames) {
                            valid.add(value);
                        }
                    }
                }
                if (!valid.isEmpty()) {
                    animation.add("frames", valid);
                }
            }
            JsonObject metadata = new JsonObject();
            metadata.add("animation", animation);
            output.put(path + ".mcmeta", metadata.toString().getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }

    private static Image image(Source source, String base) {
        for (String suffix : List.of(".png", ".tga", ".jpg")) {
            byte[] bytes = source.read().apply(base + suffix);
            if (bytes == null) {
                continue;
            }
            try {
                BufferedImage image = suffix.equals(".tga") ? TgaImage.read(bytes) : readImage(bytes);
                if (image == null || (long) image.getWidth() * image.getHeight() > 16_777_216) {
                    continue;
                }
                if (!suffix.equals(".png")) {
                    var encoded = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", encoded);
                    bytes = encoded.toByteArray();
                }
                return new Image(bytes, image.getWidth(), image.getHeight());
            } catch (IOException | IllegalArgumentException ignored) {
                // An unsupported optional texture must not discard the rest of the pack.
            }
        }
        return null;
    }

    private static BufferedImage readImage(byte[] bytes) throws IOException {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return null;
            }
            var reader = readers.next();
            try {
                reader.setInput(stream);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 16_777_216) {
                    return null;
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static Map<String, JsonObject> animations(Source pack) {
        byte[] data = pack.read().apply("textures/flipbook_textures.json");
        Map<String, JsonObject> result = new LinkedHashMap<>();
        if (data != null && data.length <= 4 * 1024 * 1024) {
            try {
                for (var entry : JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonArray()) {
                    var value = entry.getAsJsonObject();
                    result.put(value.get("flipbook_texture").getAsString(), value);
                }
            } catch (RuntimeException ignored) {
                result.clear();
            }
        }
        return result;
    }

    private static int positiveInt(JsonObject object, String key, int fallback) {
        var value = object.get(key);
        try {
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && value.getAsInt() > 0 ? value.getAsInt() : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static Map<String, List<String>> mappings() {
        try (var input = VanillaTextures.class.getResourceAsStream(
            "/assets/peaks_bedrock_worlds/vanilla-textures.json")) {
            if (input == null) {
                throw new IllegalStateException("Missing vanilla texture mappings.");
            }
            var data = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (var entry : data.entrySet()) {
                List<String> sources = new ArrayList<>();
                for (JsonElement path : entry.getValue().getAsJsonArray()) {
                    sources.add(path.getAsString());
                }
                result.put(entry.getKey(), List.copyOf(sources));
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read vanilla texture mappings.", exception);
        }
    }

    private record Image(byte[] png, int width, int height) {
    }
}
