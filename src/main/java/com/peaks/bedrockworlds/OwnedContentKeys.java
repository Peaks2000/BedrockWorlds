// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class OwnedContentKeys {
    private final Map<String, byte[]> keys = new HashMap<>();

    // The receipt comes directly from the authenticated service over HTTPS, never from an imported file.
    OwnedContentKeys(String receipt, UUID deviceId) throws IOException {
        try {
            var body = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(receipt.split("\\.")[1]),
                StandardCharsets.UTF_8)).getAsJsonObject();
            if (body.get("exp").getAsLong() <= Instant.now().getEpochSecond()) {
                throw new IOException("The Marketplace content receipt has expired.");
            }
            var data = body.getAsJsonObject("Receipt");
            if (!deviceId.toString().equals(data.getAsJsonObject("ReceiptData").get("DeviceId").getAsString())) {
                throw new IOException("The Marketplace content receipt is for a different device.");
            }
            byte[] entity = data.get("EntityId").getAsString().getBytes(StandardCharsets.UTF_8);
            byte[] device = deviceId.toString().getBytes(StandardCharsets.UTF_8);
            for (var entry : data.getAsJsonArray("Entitlements")) {
                var entitlement = entry.getAsJsonObject();
                if (!entitlement.has("ContentKey") || entitlement.get("ContentKey").isJsonNull()) {
                    continue;
                }
                var expiry = entitlement.get("ExpirationDate");
                if (expiry != null && !expiry.isJsonNull() && !expiry.getAsString().isBlank()
                    && Instant.parse(expiry.getAsString()).isBefore(Instant.now())) {
                    continue;
                }
                byte[] encoded = Base64.getDecoder().decode(entitlement.get("ContentKey").getAsString());
                if (encoded.length != 64 || entity.length < 32 || device.length < 32) {
                    throw new IOException("The Marketplace returned an unsupported content key format.");
                }
                byte[] key = new byte[32];
                for (int index = 0; index < key.length; index++) {
                    key[index] = (byte) (encoded[index * 2] ^ entity[index] ^ device[index]);
                }
                keys.put(entitlement.get("FriendlyId").getAsString(), key);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Could not read the owned Marketplace content receipt.");
        }
    }

    void decode(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path contents : paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals("contents.json")).toList()) {
                if (Files.size(contents) > 16 * 1024 * 1024) {
                    throw new IOException("A Marketplace pack index is too large.");
                }
                byte[] bytes = Files.readAllBytes(contents);
                if (encrypted(bytes)) {
                    bytes = decodeHeader(bytes);
                }
                try {
                    JsonObject index = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8).strip())
                        .getAsJsonObject();
                    for (var entry : index.getAsJsonArray("content")) {
                        var item = entry.getAsJsonObject();
                        if (item.has("key") && !item.get("key").isJsonNull()) {
                            Path target = ContentArchive.child(contents.getParent(), item.get("path").getAsString());
                            if (!Files.isRegularFile(target)) {
                                throw new IOException("A Marketplace pack is missing a listed file.");
                            }
                            byte[] key = item.get("key").getAsString().getBytes(StandardCharsets.ISO_8859_1);
                            decodeFile(target, key, 0);
                        }
                    }
                    // BDS reads the plain files; retaining the encrypted index would make it decode them twice.
                    Files.delete(contents);
                } catch (RuntimeException exception) {
                    throw new IOException("Could not read a Marketplace pack index.");
                }
            }
        }
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                byte[] header;
                try (var in = Files.newInputStream(file)) {
                    header = in.readNBytes(256);
                }
                if (encrypted(header)) {
                    decodeFile(file, keyFor(header), 256);
                }
            }
        }
    }

    private byte[] decodeHeader(byte[] bytes) throws IOException {
        try {
            return cipher(keyFor(bytes)).doFinal(Arrays.copyOfRange(bytes, 256, bytes.length));
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not decode owned Marketplace content.");
        }
    }

    private byte[] keyFor(byte[] header) throws IOException {
        if (header.length < 256 || header[16] != 36) {
            throw new IOException("A Marketplace file has an unsupported encryption header.");
        }
        String id = new String(header, 17, 36, StandardCharsets.UTF_8);
        byte[] key = keys.get(id);
        if (key == null) {
            throw new IOException("The linked account has no active content key for this pack.");
        }
        return key;
    }

    private static void decodeFile(Path file, byte[] key, int offset) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), ".decoding-", ".tmp");
        try {
            Cipher cipher = cipher(key);
            try (var in = Files.newInputStream(file); var out = Files.newOutputStream(temporary)) {
                in.skipNBytes(offset);
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = in.read(buffer)) != -1;) {
                    out.write(cipher.update(buffer, 0, count));
                }
                try {
                    out.write(cipher.doFinal());
                } catch (GeneralSecurityException exception) {
                    throw new IOException("Could not decode owned Marketplace content.");
                }
            }
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Cipher cipher(byte[] key) throws IOException {
        if (key.length != 32) {
            throw new IOException("A Marketplace file has an unsupported content key.");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(Arrays.copyOf(key, 16)));
            return cipher;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not decode owned Marketplace content.");
        }
    }

    private static boolean encrypted(byte[] bytes) {
        return bytes.length >= 8 && (bytes[4] & 0xff) == 0xfc && (bytes[5] & 0xff) == 0xb9
            && (bytes[6] & 0xff) == 0xcf && (bytes[7] & 0xff) == 0x9b;
    }
}
