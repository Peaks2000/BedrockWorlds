// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;

final class EntityTextures {
    private static final String BEDROCK = "textures/entity/";
    private static final String JAVA = "assets/minecraft/" + BEDROCK;

    static void adapt(VanillaTextures.Source source, Map<String, byte[]> layer) {
        adaptSheep(layer, false);
        if (isTgaSource(source, "sheep/sheep_baby")) {
            adaptSheep(layer, true);
        }
        padLegacyHumanoid(layer, "zombie/zombie");
        padLegacyHumanoid(layer, "zombie/husk");
        if (isTgaSource(source, "zombie/drowned")) {
            adaptDrowned(layer);
        }
    }

    private static boolean isTgaSource(VanillaTextures.Source source, String name) {
        return source.files().contains(BEDROCK + name + ".tga") && !source.files().contains(BEDROCK + name + ".png");
    }

    private static void adaptSheep(Map<String, byte[]> layer, boolean baby) {
        String body = path(baby ? "sheep/sheep_baby" : "sheep/sheep");
        String wool = path(baby ? "sheep/sheep_wool_baby" : "sheep/sheep_wool");
        BufferedImage atlas = read(layer.get(body));
        int scale = squareScale(atlas, baby ? 32 : 64);
        if (scale == 0) {
            return;
        }

        BufferedImage base = new BufferedImage(64 * scale, 32 * scale, BufferedImage.TYPE_INT_ARGB);
        BufferedImage fur = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int width = atlas.getWidth();
        int height = base.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = atlas.getRGB(x, y);
                // Bedrock's sheep material reads the base alpha as a tint mask.
                base.setRGB(x, y, opaqueIfPresent(pixel));
                if (baby) {
                    if ((pixel >>> 24) == 255) {
                        fur.setRGB(x, y, pixel);
                    }
                } else {
                    fur.setRGB(x, y, atlas.getRGB(x, y + height));
                }
            }
        }
        layer.put(body, png(base));
        layer.putIfAbsent(wool, png(fur));
        layer.remove(body + ".mcmeta");
    }

    private static void padLegacyHumanoid(Map<String, byte[]> layer, String name) {
        String texture = path(name);
        BufferedImage original = read(layer.get(texture));
        if (original == null || original.getWidth() < 64 || original.getWidth() % 64 != 0
            || original.getWidth() != original.getHeight() * 2) {
            return;
        }
        // Java 26.3 expects the original mirrored-limb UVs in the top half of a square atlas.
        BufferedImage padded = square(original.getWidth());
        int width = original.getWidth();
        int height = original.getHeight();
        padded.setRGB(0, 0, width, height, original.getRGB(0, 0, width, height, null, 0, width), 0, width);
        layer.put(texture, png(padded));
        layer.remove(texture + ".mcmeta");
    }

    private static void adaptDrowned(Map<String, byte[]> layer) {
        String body = path("zombie/drowned");
        String outer = path("zombie/drowned_outer_layer");
        BufferedImage atlas = read(layer.get(body));
        int scale = squareScale(atlas, 64);
        if (scale == 0 || layer.containsKey(outer)) {
            return;
        }
        BufferedImage base = square(atlas.getWidth());
        BufferedImage clothing = square(atlas.getWidth());
        // Bedrock uses one atlas for both models and mirrors its left arm and leg.
        copyBox(atlas, base, scale, 0, 0, 0, 0, 8, 8, 8, false, true); // head
        copyBox(atlas, base, scale, 16, 16, 16, 16, 8, 12, 4, false, true); // torso
        copyLimb(atlas, base, scale, 0, 16, 40, 16, false, true); // right arm
        copyLimb(atlas, base, scale, 40, 16, 32, 48, true, true); // left arm
        copyLimb(atlas, base, scale, 16, 48, 0, 16, false, true); // right leg
        copyLimb(atlas, base, scale, 32, 48, 16, 48, true, true); // left leg
        copyBox(atlas, clothing, scale, 32, 0, 0, 0, 8, 8, 8, false, false); // hat and algae
        copyBox(atlas, clothing, scale, 16, 32, 16, 16, 8, 12, 4, false, false); // jacket
        copyLimb(atlas, clothing, scale, 48, 48, 40, 16, false, false); // right sleeve
        copyLimb(atlas, clothing, scale, 40, 32, 32, 48, true, false); // left sleeve
        copyLimb(atlas, clothing, scale, 0, 48, 0, 16, false, false); // right pants
        copyLimb(atlas, clothing, scale, 0, 32, 16, 48, true, false); // left pants
        layer.put(body, png(base));
        layer.put(outer, png(clothing));
        layer.remove(body + ".mcmeta");
    }

    private static void copyLimb(BufferedImage atlas, BufferedImage output, int scale, int sx, int sy,
        int dx, int dy, boolean mirror, boolean opaque) {
        copyBox(atlas, output, scale, sx, sy, dx, dy, 4, 12, 4, mirror, opaque);
    }

    private static void copyBox(BufferedImage atlas, BufferedImage output, int scale, int sx, int sy,
        int dx, int dy, int width, int height, int depth, boolean mirror, boolean opaque) {
        copyFace(atlas, output, scale, sx + depth, sy, dx + depth, dy, width, depth, mirror, opaque);
        copyFace(atlas, output, scale, sx + depth + width, sy, dx + depth + width, dy, width, depth, mirror, opaque);
        copyFace(atlas, output, scale, sx, sy + depth, dx + (mirror ? depth + width : 0), dy + depth, depth, height, mirror, opaque);
        copyFace(atlas, output, scale, sx + depth, sy + depth, dx + depth, dy + depth, width, height, mirror, opaque);
        copyFace(atlas, output, scale, sx + depth + width, sy + depth, dx + (mirror ? 0 : depth + width), dy + depth, depth, height, mirror, opaque);
        copyFace(atlas, output, scale, sx + 2 * depth + width, sy + depth, dx + 2 * depth + width, dy + depth, width, height, mirror, opaque);
    }

    private static void copyFace(BufferedImage atlas, BufferedImage output, int scale, int sx, int sy,
        int dx, int dy, int width, int height, boolean mirror, boolean opaque) {
        for (int y = 0; y < height * scale; y++) {
            for (int x = 0; x < width * scale; x++) {
                int fromX = mirror ? (sx + width) * scale - 1 - x : sx * scale + x;
                int color = atlas.getRGB(fromX, sy * scale + y);
                output.setRGB(dx * scale + x, dy * scale + y, opaque ? opaqueIfPresent(color) : color);
            }
        }
    }

    private static int opaqueIfPresent(int pixel) {
        return (pixel >>> 24) == 0 ? pixel : pixel | 0xff000000;
    }

    private static int squareScale(BufferedImage image, int unit) {
        return image != null && image.getWidth() == image.getHeight() && image.getWidth() % unit == 0
            ? image.getWidth() / unit : 0;
    }

    private static BufferedImage square(int size) {
        return new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    }

    private static BufferedImage read(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException ignored) {
            return null;
        }
    }

    private static byte[] png(BufferedImage image) {
        var bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode mob texture.", exception);
        }
        return bytes.toByteArray();
    }

    private static String path(String name) {
        return JAVA + name + ".png";
    }
}
