package com.meshtastic.client.lua;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Загружает каталог Lua-скриптов из Gitea-хранилища MeshApp Store.
 */
public final class LuaScriptStoreService {

    private static final Logger log = LoggerFactory.getLogger(LuaScriptStoreService.class);
    private static final Gson JSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String DEFAULT_DIRECTORY_API_URL =
            "https://git.privatepractice.app/api/v1/repos/covox/meshappstore/contents/scripts?ref=main";

    private final HttpClient httpClient;
    private final URI directoryApiUri;

    public LuaScriptStoreService() {
        this(URI.create(DEFAULT_DIRECTORY_API_URL), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    LuaScriptStoreService(URI directoryApiUri, HttpClient httpClient) {
        this.directoryApiUri = directoryApiUri;
        this.httpClient = httpClient;
    }

    public List<StoreScript> fetchScripts() throws IOException, InterruptedException {
        String directoryJson = get(directoryApiUri);
        List<StoreEntry> entries = parseDirectory(directoryJson);
        List<StoreScript> scripts = new ArrayList<>();
        for (StoreEntry entry : entries) {
            try {
                parseStoreScript(entry).ifPresent(scripts::add);
            } catch (IOException | JsonParseException | IllegalArgumentException e) {
                log.warn("Skipping broken store script {}", entry.name(), e);
            }
        }
        scripts.sort(Comparator.comparing(StoreScript::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(StoreScript::guid));
        return scripts;
    }

    private java.util.Optional<StoreScript> parseStoreScript(StoreEntry entry)
            throws IOException, InterruptedException {
        String scriptJson = get(entry.downloadUri());
        LuaScriptService.LuaScriptExportFile exportFile =
                JSON.fromJson(scriptJson, LuaScriptService.LuaScriptExportFile.class);
        if (!isSupportedExport(exportFile)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new StoreScript(
                normalizeGuid(exportFile.guid()),
                LuaScript.normalizeIcon(exportFile.icon()),
                exportFile.name().trim(),
                LuaScript.normalizeVersion(exportFile.scriptVersion()),
                LuaScript.BotType.fromStorage(exportFile.botType()),
                LuaScript.normalizeDescription(exportFile.description()),
                LuaScript.normalizeAuthor(exportFile.author()),
                entry.downloadUri().toString(),
                exportFile));
    }

    private List<StoreEntry> parseDirectory(String directoryJson) {
        JsonElement root = JsonParser.parseString(directoryJson);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("Gitea returned unexpected directory response");
        }
        JsonArray array = root.getAsJsonArray();
        List<StoreEntry> entries = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String type = stringValue(object, "type");
            String name = stringValue(object, "name");
            String downloadUrl = stringValue(object, "download_url");
            if (!"file".equalsIgnoreCase(type)
                    || name.isBlank()
                    || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                    || downloadUrl.isBlank()) {
                continue;
            }
            entries.add(new StoreEntry(name, URI.create(downloadUrl)));
        }
        return entries;
    }

    private String get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "MeshApp Script Store")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Gitea request failed: HTTP " + status);
        }
        return response.body();
    }

    private boolean isSupportedExport(LuaScriptService.LuaScriptExportFile exportFile) {
        return exportFile != null
                && "meshapp-lua-script".equals(exportFile.format())
                && exportFile.version() <= 1
                && !normalizeGuid(exportFile.guid()).isBlank()
                && exportFile.name() != null
                && !exportFile.name().isBlank();
    }

    private static String normalizeGuid(String guid) {
        if (guid == null || guid.isBlank()) {
            return "";
        }
        try {
            return UUID.fromString(guid.trim()).toString();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private record StoreEntry(String name, URI downloadUri) {}

    public record StoreScript(String guid,
                              String icon,
                              String name,
                              long version,
                              LuaScript.BotType botType,
                              String description,
                              String author,
                              String downloadUrl,
                              LuaScriptService.LuaScriptExportFile exportFile) {}
}
