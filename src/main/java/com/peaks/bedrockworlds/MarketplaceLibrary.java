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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.playfab.data.PlayFabConstants;

final class MarketplaceLibrary {
    private static final URI INVENTORY = URI.create(
        "https://entitlements.mktpl.minecraft-services.net/api/v1.0/player/inventory?includeReceipt=false");
    private static final URI CATALOG = URI.create(
        "https://" + PlayFabConstants.BEDROCK_PLAY_FAB_TITLE_ID + ".playfabapi.com/Catalog/GetItems");
    private static final int CATALOG_BATCH_SIZE = 50;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final Map<String, String> PACK_TYPES = Map.of(
        "addon", "Add-on", "resourcepack", "Resource pack", "worldtemplate", "World template", "mashup", "Mash-up");

    static List<Item> read(BedrockAuthManager account, String locale) throws Exception {
        String session = account.getMinecraftSession().getUpToDate().getAuthorizationHeader();
        String entity = account.getPlayFabToken().getUpToDate().getEntityToken().getToken();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            JsonObject inventory = request(client, requestTo(INVENTORY).header("Authorization", session).GET().build())
                .getAsJsonObject("result").getAsJsonObject("inventory");
            JsonArray entitlements = inventory.getAsJsonArray("entitlements");
            var ids = new ArrayList<String>();
            var seen = new HashSet<String>();
            for (var entitlement : entitlements) {
                var idValue = entitlement.getAsJsonObject().get("id");
                if (idValue == null || !idValue.isJsonPrimitive()) {
                    continue;
                }
                String id = idValue.getAsString();
                if (seen.add(id)) {
                    ids.add(id);
                }
            }

            List<Item> items = new ArrayList<>();
            for (int start = 0; start < ids.size(); start += CATALOG_BATCH_SIZE) {
                var idBatch = new JsonArray();
                ids.subList(start, Math.min(start + CATALOG_BATCH_SIZE, ids.size())).forEach(idBatch::add);
                var payload = new JsonObject();
                payload.add("Ids", idBatch);
                var response = request(client, requestTo(CATALOG).header("X-EntityToken", entity)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build());
                for (var entry : response.getAsJsonObject("data").getAsJsonArray("Items")) {
                    JsonObject item = entry.getAsJsonObject();
                    var type = item.get("ContentType");
                    if (type == null || !type.isJsonPrimitive()) {
                        continue;
                    }
                    String kind = PACK_TYPES.get(type.getAsString().toLowerCase(Locale.ROOT));
                    if (kind != null) {
                        UUID id = UUID.fromString(item.get("Id").getAsString());
                        items.add(new Item(id, localizedTitle(item.getAsJsonObject("Title"), locale), kind));
                    }
                }
            }
            items.sort(Comparator.comparing(Item::name, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(items);
        }
    }

    private static String localizedTitle(JsonObject titles, String locale) {
        if (titles == null) {
            return "Marketplace pack";
        }
        String key = locale.toLowerCase(Locale.ROOT).replace('_', '-');
        for (String candidate : List.of(key, key.split("-", 2)[0], "en-us", "en", "NEUTRAL")) {
            var value = titles.get(candidate);
            if (value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()) {
                return value.getAsString();
            }
        }
        return titles.entrySet().stream().map(Map.Entry::getValue).filter(value -> value.isJsonPrimitive())
            .map(value -> value.getAsString()).filter(value -> !value.isBlank()).findFirst().orElse("Marketplace pack");
    }

    private static HttpRequest.Builder requestTo(URI uri) {
        return HttpRequest.newBuilder(uri).header("Accept", "application/json").timeout(Duration.ofSeconds(20));
    }

    private static JsonObject request(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Marketplace returned HTTP " + response.statusCode() + ".");
            }
            byte[] data = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (data.length > MAX_RESPONSE_BYTES) {
                throw new IOException("Marketplace returned more data than expected.");
            }
            return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    record Item(UUID id, String name, String kind) {
        URI storePage() {
            return URI.create("https://www.minecraft.net/en-us/marketplace/pdp?id=" + id);
        }
    }
}
