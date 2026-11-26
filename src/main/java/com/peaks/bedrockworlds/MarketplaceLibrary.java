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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

            List<JsonObject> owned = catalogItems(client, entity, ids);
            var productIds = new LinkedHashSet<String>();
            for (JsonObject item : owned) {
                if (!PACK_TYPES.containsKey(text(item, "ContentType").toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (item.get("Tags") instanceof JsonArray tags) {
                    for (var tag : tags) {
                        if (tag.isJsonPrimitive() && uuid(tag.getAsString()) != null) {
                            productIds.add(tag.getAsString());
                        }
                    }
                }
            }
            // Friendly IDs identify pack contents, while the remaining UUID tags link the store products.
            for (JsonObject item : owned) {
                if (item.get("AlternateIds") instanceof JsonArray aliases) {
                    for (var alias : aliases) {
                        if (alias instanceof JsonObject value && "FriendlyId".equals(text(value, "Type"))) {
                            productIds.remove(text(value, "Value"));
                        }
                    }
                }
            }
            return resolve(owned, catalogItems(client, entity, List.copyOf(productIds)), locale);
        }
    }

    private static List<JsonObject> catalogItems(HttpClient client, String entity, List<String> ids)
        throws IOException, InterruptedException {
        List<JsonObject> items = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += CATALOG_BATCH_SIZE) {
            var idBatch = new JsonArray();
            ids.subList(start, Math.min(start + CATALOG_BATCH_SIZE, ids.size())).forEach(idBatch::add);
            var payload = new JsonObject();
            payload.add("Ids", idBatch);
            var response = request(client, requestTo(CATALOG).header("X-EntityToken", entity)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build());
            for (var entry : response.getAsJsonObject("data").getAsJsonArray("Items")) {
                items.add(entry.getAsJsonObject());
            }
        }
        return items;
    }

    static List<Item> resolve(List<JsonObject> owned, List<JsonObject> products, String locale) {
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonObject product : products) {
            byId.put(text(product, "Id"), product);
        }
        Map<String, Item> result = new LinkedHashMap<>();
        for (JsonObject entitlement : owned) {
            String kind = PACK_TYPES.get(text(entitlement, "ContentType").toLowerCase(Locale.ROOT));
            if (kind == null) {
                continue;
            }
            JsonObject product = entitlement;
            if (entitlement.get("Tags") instanceof JsonArray tags) {
                for (var tag : tags) {
                    JsonObject candidate = tag.isJsonPrimitive() ? byId.get(tag.getAsString()) : null;
                    if (candidate != null && references(candidate, text(entitlement, "Id"))) {
                        product = candidate;
                        break;
                    }
                }
            }
            String title = localizedTitle(product.getAsJsonObject("Title"), locale);
            UUID id = uuid(text(product, "Id"));
            String creator = product.get("DisplayProperties") instanceof JsonObject display
                ? text(display, "creatorName") : "";
            if (product.get("Tags") instanceof JsonArray tags) {
                for (String type : List.of("mashup", "addon", "worldtemplate", "resourcepack")) {
                    if (tags.contains(new com.google.gson.JsonPrimitive(type))) {
                        kind = PACK_TYPES.get(type);
                        break;
                    }
                }
            }
            String key = text(product, "Id");
            result.putIfAbsent(key, new Item(title == null ? null : id,
                title == null ? "Name unavailable" : title, kind, creator));
        }
        return result.values().stream().sorted(Comparator.comparing(Item::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Item::kind)).toList();
    }

    private static boolean references(JsonObject product, String entitlementId) {
        if (product.get("ItemReferences") instanceof JsonArray references) {
            for (var reference : references) {
                if (reference instanceof JsonObject item && entitlementId.equals(text(item, "Id"))) {
                    return true;
                }
            }
        }
        return false;
    }

    static String localizedTitle(JsonObject titles, String locale) {
        if (titles == null) {
            return null;
        }
        String key = locale.toLowerCase(Locale.ROOT).replace('_', '-');
        for (String candidate : List.of(key, key.split("-", 2)[0], "en-us", "en", "neutral")) {
            for (var entry : titles.entrySet()) {
                if (entry.getKey().replace('_', '-').equalsIgnoreCase(candidate)) {
                    String value = readableTitle(entry.getValue());
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return titles.entrySet().stream().map(Map.Entry::getValue).map(MarketplaceLibrary::readableTitle)
            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static String readableTitle(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        String title = value.getAsString().strip();
        String lower = title.toLowerCase(Locale.ROOT);
        return title.isBlank() || lower.startsWith("mc pack item") || lower.equals("entitlement product")
            || uuid(title) != null ? null : title;
    }

    private static String text(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
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

    record Item(UUID id, String name, String kind, String creator) {
        URI storePage() {
            return URI.create("https://www.minecraft.net/en-us/marketplace/pdp?id=" + id);
        }
    }
}
