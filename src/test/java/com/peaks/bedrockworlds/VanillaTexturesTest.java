// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.*;

public class VanillaTexturesTest {
    @Test
    public void mapsVanillaBlocksItemsAndRenamedPaths() throws Exception {
        byte[] png = png(16, 16, 0xffaabbcc);
        var result = VanillaTextures.convert(List.of(source(Map.of(
            "textures/blocks/grass_top.png", png,
            "textures/blocks/planks_oak.png", png,
            "textures/items/diamond_sword.png", png))));
        assertArrayEquals(png, result.get("assets/minecraft/textures/block/grass_block_top.png"));
        assertArrayEquals(png, result.get("assets/minecraft/textures/block/oak_planks.png"));
        assertArrayEquals(png, result.get("assets/minecraft/textures/item/diamond_sword.png"));
    }

    @Test
    public void higherPackWinsEvenWhenItUsesAnotherMappedAlias() throws Exception {
        byte[] bottom = png(16, 16, 0xff112233);
        byte[] top = png(16, 16, 0xff445566);
        var result = VanillaTextures.convert(List.of(
            source(Map.of("textures/blocks/carrots_stage3.png", bottom)),
            source(Map.of("textures/blocks/carrots_stage_3.png", top))));
        assertArrayEquals(top, result.get("assets/minecraft/textures/block/carrots_stage3.png"));
    }

    @Test
    public void translatesFlipbookOrderAndTimingWithoutAnimatingStaticTallImages() throws Exception {
        var result = VanillaTextures.convert(List.of(source(Map.of(
            "textures/blocks/fire_0.png", png(16, 32, 0xff112233),
            "textures/entity/custom_tall.png", png(16, 32, 0xff112233),
            "textures/flipbook_textures.json", ("[{\"flipbook_texture\":\"textures/blocks/fire_0\","
                + "\"ticks_per_frame\":3,\"frames\":[1,0],\"blend_frames\":true}]").getBytes(StandardCharsets.UTF_8)))));
        var metadata = JsonParser.parseString(new String(result.get("assets/minecraft/textures/block/fire_0.png.mcmeta"),
            StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animation");
        assertEquals(3, metadata.get("frametime").getAsInt());
        assertEquals(1, metadata.getAsJsonArray("frames").get(0).getAsInt());
        assertTrue(metadata.get("interpolate").getAsBoolean());
        assertFalse(result.containsKey("assets/minecraft/textures/entity/custom_tall.png.mcmeta"));
    }

    @Test
    public void decodesTgaAlphaAndOriginAndRle() throws Exception {
        byte[] tga = new byte[26];
        tga[2] = 2; tga[12] = 1; tga[14] = 2; tga[16] = 32; tga[17] = 8;
        tga[18] = 0x33; tga[19] = 0x22; tga[20] = 0x11; tga[21] = (byte) 0x80;
        tga[22] = 0x66; tga[23] = 0x55; tga[24] = 0x44; tga[25] = (byte) 0xff;
        var image = TgaImage.read(tga);
        assertEquals(0xff445566, image.getRGB(0, 0));
        assertEquals(0x80112233, image.getRGB(0, 1));
        byte[] rle = new byte[23];
        System.arraycopy(tga, 0, rle, 0, 18);
        rle[12] = 2; rle[14] = 1; rle[17] = 40; rle[2] = 10;
        rle[18] = (byte) 0x81;
        System.arraycopy(tga, 18, rle, 19, 4);
        var repeated = TgaImage.read(rle);
        assertEquals(0x80112233, repeated.getRGB(0, 0));
        assertEquals(repeated.getRGB(0, 0), repeated.getRGB(1, 0));
        var output = VanillaTextures.convert(List.of(source(Map.of("textures/blocks/leaves_oak.tga", tga))));
        assertEquals(0xff445566, ImageIO.read(new ByteArrayInputStream(
            output.get("assets/minecraft/textures/block/oak_leaves.png"))).getRGB(0, 0));
    }

    @Test
    public void rejectsTruncatedTgaAndKeepsOtherValidTextures() throws Exception {
        assertThrows(IOException.class, () -> TgaImage.read(new byte[4]));
        byte[] valid = png(16, 16, 0xff112233);
        var output = VanillaTextures.convert(List.of(source(Map.of(
            "textures/blocks/grass_side.tga", new byte[4], "textures/blocks/stone.png", valid))));
        assertArrayEquals(valid, output.get("assets/minecraft/textures/block/stone.png"));
        assertFalse(output.containsKey("assets/minecraft/textures/block/grass_block_side_overlay.png"));
    }

    @Test
    public void keepsJavaArmorTrimPalettesCoherent() throws Exception {
        byte[] png = png(23, 1, 0xff112233);
        var output = VanillaTextures.convert(List.of(source(Map.of(
            "textures/trims/color_palettes/trim_palette.png", png,
            "textures/trims/items/boots_trim.png", png))));
        assertTrue(output.keySet().stream().noneMatch(path -> path.contains("/trims/")));
    }

    private static VanillaTextures.Source source(Map<String, byte[]> files) {
        return new VanillaTextures.Source(List.copyOf(files.keySet()), files::get);
    }

    private static byte[] png(int width, int height, int color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color);
            }
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
