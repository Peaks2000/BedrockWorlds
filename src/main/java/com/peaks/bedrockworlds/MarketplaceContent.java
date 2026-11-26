// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;

final class MarketplaceContent {
    private static final URI PUBLISHED = URI.create("https://20ca2.playfabapi.com/Catalog/GetPublishedItem");
    private static final URI INVENTORY = URI.create("https://20ca2.playfabapi.com/Inventory/GetInventoryItems");

    static Prepared prepare(BedrockAuthManager account, MarketplaceLibrary.Item selection, Path root) throws Exception {
        var owned = MarketplaceLibrary.read(account, "en-us");
        if (selection.id() == null || owned.stream().noneMatch(item -> selection.id().equals(item.id()))) {
            throw new IOException("This pack is no longer owned by the linked Bedrock account.");
        }
        var payload = new JsonObject();
        payload.addProperty("ItemId", selection.id().toString());
        payload.addProperty("ETag", "");
        String token = account.getPlayFabMasterToken().getUpToDate().getToken();
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".marketplace-");
        boolean success = false;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var item = post(client, PUBLISHED, token, payload).getAsJsonObject("Item");
            List<JsonObject> downloads = downloads(item.getAsJsonArray("Contents"), account.getGameVersion());
            if (downloads.isEmpty()) {
                throw new IOException("This Marketplace pack has no compatible world or add-on download.");
            }
            var receiptData = new JsonObject();
            receiptData.addProperty("DeviceId", account.getDeviceId().toString());
            var inventory = new JsonObject();
            inventory.add("ReceiptData", receiptData);
            var keys = new OwnedContentKeys(post(client, INVENTORY, token, inventory).get("Receipt").getAsString(),
                account.getDeviceId());
            ContentArchive archives = new ContentArchive();
            int index = 0;
            for (JsonObject content : downloads) {
                Path archive = staging.resolve("download.zip");
                download(client, URI.create(content.get("Url").getAsString()), archive);
                archives.unpack(archive, staging.resolve("content-" + index++), 0);
                Files.delete(archive);
            }
            keys.decode(staging);
            Prepared result = inspect(staging);
            success = true;
            return result;
        } finally {
            if (!success) {
                WorldStore.deleteTree(staging);
            }
        }
    }

    static List<JsonObject> downloads(JsonArray contents, String version) throws IOException {
        var selected = new LinkedHashMap<String, JsonObject>();
        if (contents != null) {
            for (var entry : contents) {
                var content = entry.getAsJsonObject();
                String type = content.get("Type").getAsString();
                if (!List.of("resourcebinary", "behaviorbinary", "worldbinary", "addonbinary").contains(type)) {
                    continue;
                }
                String min = content.has("MinClientVersion") ? content.get("MinClientVersion").getAsString() : "0.0.0";
                String max = content.has("MaxClientVersion") ? content.get("MaxClientVersion").getAsString() : "65535.65535.65535";
                if (compareVersions(version, min) < 0 || compareVersions(version, max) > 0) {
                    continue;
                }
                JsonObject previous = selected.get(type);
                if (previous == null || compareVersions(min, previous.has("MinClientVersion")
                    ? previous.get("MinClientVersion").getAsString() : "0.0.0") > 0) {
                    selected.put(type, content);
                }
            }
        }
        return List.copyOf(selected.values());
    }

    private static int compareVersions(String a, String b) throws IOException {
        try {
            String[] left = a.split("\\.");
            String[] right = b.split("\\.");
            for (int i = 0; i < Math.max(left.length, right.length); i++) {
                int comparison = Integer.compare(i < left.length ? Integer.parseInt(left[i]) : 0,
                    i < right.length ? Integer.parseInt(right[i]) : 0);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        } catch (NumberFormatException exception) {
            throw new IOException("The Marketplace returned an unsupported game version.");
        }
    }

    static Prepared inspect(Path staging) throws IOException {
        var templates = new ArrayList<Path>();
        var packs = new ArrayList<Path>();
        try (var paths = Files.walk(staging)) {
            for (Path manifest : paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals("manifest.json")).toList()) {
                Path directory = manifest.getParent();
                if (Files.isRegularFile(directory.resolve("level.dat")) && Files.isDirectory(directory.resolve("db"))) {
                    templates.add(directory);
                } else {
                    packs.add(directory);
                }
            }
        }
        if (templates.size() > 1) {
            throw new IOException("This Marketplace pack contains multiple world templates.");
        }
        Path template = templates.isEmpty() ? null : templates.getFirst();
        packs.removeIf(path -> template != null && path.startsWith(template));
        if (template == null && packs.isEmpty()) {
            throw new IOException("This Marketplace download contains no usable world or add-on packs.");
        }
        packs.sort(Comparator.comparing(Path::toString));
        return new Prepared(staging, template, List.copyOf(packs));
    }

    private static JsonObject post(HttpClient client, URI uri, String token, JsonObject payload)
        throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("X-EntityToken", token)
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
        return MarketplaceLibrary.request(client, request).getAsJsonObject("data");
    }

    private static void download(HttpClient client, URI uri, Path destination) throws IOException, InterruptedException {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
            || !(host.endsWith(".xboxlive.com") || host.endsWith(".minecraft.net")
                || host.endsWith(".minecraft-services.net") || host.endsWith(".playfab.com"))) {
            throw new IOException("The Marketplace returned an unsupported download host.");
        }
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5))
            .header("User-Agent", "libhttpclient/1.0.0.0").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Marketplace download returned HTTP " + response.statusCode() + ".");
            }
            try (var out = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long bytes = 0;
                for (int count; (count = in.read(buffer)) != -1;) {
                    bytes += count;
                    if (bytes > 1024L * 1024 * 1024) {
                        throw new IOException("The Marketplace download exceeds 1 GiB.");
                    }
                    out.write(buffer, 0, count);
                }
            }
        }
    }

    record Prepared(Path directory, Path template, List<Path> packs) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            WorldStore.deleteTree(directory);
        }
    }
}
