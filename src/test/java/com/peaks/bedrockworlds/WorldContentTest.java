// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class WorldContentTest {
    private static final UUID DEVICE = UUID.fromString("12345678-1234-1234-1234-123456789012");
    private static final String ENTITY = "11223344-1122-1122-1122-112233445566";
    private static final String PACK = "12345678-9876-1234-1234-123456789012";
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FILE_KEY = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
    private static int passed;

    @org.junit.Test
    public void ownedContentAndWorldCreation() throws Exception {
        main(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bedrock-worlds-regression-");
        try {
            Path pack = Files.createDirectory(root.resolve("owned-pack"));
            Files.writeString(pack.resolve("manifest.json"), manifest(PACK, "data"));
            Files.createDirectories(pack.resolve("functions"));
            byte[] function = "say owned content works\n".getBytes(StandardCharsets.UTF_8);
            Files.write(pack.resolve("functions/test.mcfunction"), encrypt(FILE_KEY, function));
            JsonObject file = new JsonObject();file.addProperty("path", "functions/test.mcfunction");
            file.addProperty("key", new String(FILE_KEY, StandardCharsets.ISO_8859_1));
            JsonArray entries = new JsonArray();entries.add(file);
            JsonObject index = new JsonObject();index.add("content", entries);
            Files.write(pack.resolve("contents.json"), header(encrypt(KEY, index.toString().getBytes(StandardCharsets.UTF_8))));
            Files.createDirectories(pack.resolve("db"));
            byte[] database = "original template database bytes".getBytes(StandardCharsets.UTF_8);
            Files.write(pack.resolve("db/000001.ldb"), header(encrypt(KEY, database)));
            var keys = new OwnedContentKeys(receipt(true, false, DEVICE), DEVICE);
            keys.decode(pack);
            check(Arrays.equals(function, Files.readAllBytes(pack.resolve("functions/test.mcfunction"))), "owned behaviour file decoded");
            check(Arrays.equals(database, Files.readAllBytes(pack.resolve("db/000001.ldb"))), "template database decoded");
            check(!Files.exists(pack.resolve("contents.json")), "key-bearing index removed");
            rejects(() -> new OwnedContentKeys(receipt(true, true, DEVICE), DEVICE), "expired receipt rejected");
            rejects(() -> new OwnedContentKeys(receipt(true, false, UUID.randomUUID()), DEVICE), "other device rejected");
            Path locked = Files.createDirectory(root.resolve("unowned"));
            Files.write(locked.resolve("data.ldb"), header(encrypt(KEY, database)));
            rejects(() -> new OwnedContentKeys(receipt(false, false, DEVICE), DEVICE).decode(locked), "missing entitlement rejected");

            Path zip = root.resolve("traversal.zip");
            zip(zip, "../outside", new byte[]{1});
            rejects(() -> new ContentArchive().unpack(zip, root.resolve("extract"), 0), "ZIP traversal rejected");
            check(!Files.exists(root.resolve("outside")), "ZIP did not escape staging");
            Path nestedPack = root.resolve("pack.mcpack");zip(nestedPack, "manifest.json", manifest(PACK, "data").getBytes(StandardCharsets.UTF_8));
            Path wrapper = root.resolve("wrapper.zip");zip(wrapper, "ppack0.zip", Files.readAllBytes(nestedPack));
            Path extracted = root.resolve("wrapper");new ContentArchive().unpack(wrapper, extracted, 0);
            try (var paths = Files.walk(extracted)) {
                check(paths.anyMatch(path -> path.getFileName().toString().equals("manifest.json")), "delivery wrapper unpacked");
            }

            var contents = new JsonArray();
            for (String version : List.of("1.0.0", "1.26.0", "1.27.0")) {
                var content = new JsonObject();content.addProperty("Type", "resourcebinary");
                content.addProperty("MinClientVersion", version);content.addProperty("MaxClientVersion", "65535.65535.65535");
                contents.add(content);
            }
            check(MarketplaceContent.downloads(contents, "1.26.63").getFirst().get("MinClientVersion").getAsString().equals("1.26.0"), "compatible download selected");

            Path template = Files.createDirectory(root.resolve("template"));
            Files.createDirectories(template.resolve("db"));Files.write(template.resolve("db/sentinel.ldb"), database);
            Files.writeString(template.resolve("level.dat"), "template level settings");
            Files.writeString(template.resolve("manifest.json"), manifest(UUID.randomUUID().toString(), "world_template"));
            String existingId = UUID.randomUUID().toString();
            Files.writeString(template.resolve("world_behavior_packs.json"), "[{\"pack_id\":\""+existingId+"\",\"version\":[1,0,0]}]");
            var store = new WorldStore(root.resolve("instance"));
            var prepared = new MarketplaceContent.Prepared(template, template, List.of(pack));
            var item = new MarketplaceLibrary.Item(UUID.randomUUID(), "Owned template", "World template", "Creator");
            var options = new WorldStore.Options("Chosen name", "", "survival", "normal", false);
            var world = store.create(options, List.of(), List.of(prepared), List.of(item));
            check(Arrays.equals(database, Files.readAllBytes(world.directory().resolve("db/sentinel.ldb"))), "world uses template database");
            check(Files.readString(world.directory().resolve("world_behavior_packs.json")).contains(existingId), "template packs retained");
            check(Files.readString(world.directory().resolve("world_behavior_packs.json")).contains(PACK), "selected add-on enabled");
            check(WorldStore.marketplace(world).equals(List.of(item.id().toString())), "ownership reference saved");
            var plain = store.create(options, List.of());
            check(plain.directory().getParent().equals(world.directory().getParent()), "plain and add-on worlds share server root");
            check(!Files.exists(plain.directory().resolve("bedrock_server")), "no per-world server copy");
            Path ambiguous = Files.createDirectory(root.resolve("ambiguous"));
            WorldStore.copyTree(template, ambiguous.resolve("first"));WorldStore.copyTree(template, ambiguous.resolve("second"));
            rejects(() -> MarketplaceContent.inspect(ambiguous), "ambiguous templates rejected");
            System.out.println("PASS " + passed + " Bedrock Worlds content regressions");
        } finally {
            WorldStore.deleteTree(root);
        }
    }

    private static String manifest(String id, String type) {
        return "{\"format_version\":2,\"header\":{\"name\":\"Fixture\",\"description\":\"Fixture\",\"uuid\":\""+id+"\",\"version\":[1,0,0]},\"modules\":[{\"type\":\""+type+"\",\"uuid\":\""+UUID.randomUUID()+"\",\"version\":[1,0,0]}]}";
    }

    private static String receipt(boolean owned, boolean expired, UUID device) {
        var body = new JsonObject();body.addProperty("exp", Instant.now().getEpochSecond() + (expired ? -60 : 3600));
        var receipt = new JsonObject();receipt.addProperty("EntityId", ENTITY);
        var rd = new JsonObject();rd.addProperty("DeviceId", device.toString());receipt.add("ReceiptData", rd);
        var entries = new JsonArray();
        if (owned) {
            var entry = new JsonObject();entry.addProperty("FriendlyId", PACK);byte[] encoded = new byte[64];
            byte[] entity = ENTITY.getBytes(StandardCharsets.UTF_8);byte[] dev = device.toString().getBytes(StandardCharsets.UTF_8);
            for (int i=0;i<32;i++) encoded[i*2]=(byte)(KEY[i]^entity[i]^dev[i]);
            entry.addProperty("ContentKey", Base64.getEncoder().encodeToString(encoded));entries.add(entry);
        }
        receipt.add("Entitlements", entries);body.add("Receipt", receipt);
        return "fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().getBytes(StandardCharsets.UTF_8))+".fixture";
    }

    private static byte[] encrypt(byte[] key, byte[] data) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(Arrays.copyOf(key,16)));
        return cipher.doFinal(data);
    }

    private static byte[] header(byte[] data) {
        byte[] result=new byte[256+data.length];result[4]=(byte)0xfc;result[5]=(byte)0xb9;result[6]=(byte)0xcf;result[7]=(byte)0x9b;
        result[16]=36;System.arraycopy(PACK.getBytes(StandardCharsets.UTF_8),0,result,17,36);
        System.arraycopy(data,0,result,256,data.length);return result;
    }

    private static void zip(Path path, String name, byte[] data) throws IOException {
        try(var out=new ZipOutputStream(Files.newOutputStream(path))) {out.putNextEntry(new ZipEntry(name));out.write(data);out.closeEntry();}
    }

    private static void check(boolean result, String name) {
        if(!result) throw new AssertionError(name);System.out.println("PASS " + name);passed++;
    }

    private static void rejects(Checked action, String name) throws Exception {
        try {action.run();}catch(IOException expected){check(true,name);return;}throw new AssertionError(name);
    }

    private interface Checked {void run() throws Exception;}
}
