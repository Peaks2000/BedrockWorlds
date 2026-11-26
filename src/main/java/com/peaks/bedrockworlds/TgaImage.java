// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import java.awt.image.BufferedImage;
import java.io.IOException;

final class TgaImage {
    static BufferedImage read(byte[] data) throws IOException {
        if (data.length < 18 || data[1] != 0) {
            throw new IOException("Unsupported TGA header.");
        }
        int type = data[2] & 255;
        int width = (data[12] & 255) | (data[13] & 255) << 8;
        int height = (data[14] & 255) | (data[15] & 255) << 8;
        int depth = data[16] & 255;
        if ((type != 2 && type != 10) || (depth != 24 && depth != 32)
            || width == 0 || height == 0 || (long) width * height > 16_777_216) {
            throw new IOException("Unsupported TGA image format.");
        }
        int stride = depth / 8;
        int cursor = 18 + (data[0] & 255);
        int pixel = 0;
        int count = width * height;
        int descriptor = data[17] & 255;
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        while (pixel < count) {
            int packet = 0;
            if (type == 10) {
                if (cursor >= data.length) {
                    throw new IOException("Truncated TGA image.");
                }
                packet = data[cursor++] & 255;
            }
            int length = type == 10 ? (packet & 127) + 1 : 1;
            boolean repeat = type == 10 && (packet & 128) != 0;
            if (length > count - pixel) {
                throw new IOException("Invalid TGA run length.");
            }
            int color = 0;
            for (int i = 0; i < length; i++, pixel++) {
                if (!repeat || i == 0) {
                    if (cursor + stride > data.length) {
                        throw new IOException("Truncated TGA pixels.");
                    }
                    int blue = data[cursor++] & 255;
                    int green = data[cursor++] & 255;
                    int red = data[cursor++] & 255;
                    int alpha = stride == 4 ? data[cursor++] & 255 : 255;
                    if ((descriptor & 15) == 0) {
                        alpha = 255;
                    }
                    color = alpha << 24 | red << 16 | green << 8 | blue;
                }
                int x = pixel % width;
                int y = pixel / width;
                if ((descriptor & 16) != 0) {
                    x = width - x - 1;
                }
                if ((descriptor & 32) == 0) {
                    y = height - y - 1;
                }
                result.setRGB(x, y, color);
            }
        }
        return result;
    }
}
