package com.meshtastic.client.lua.api;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of {@code mesh.curl} for the Lua sandbox.
 * <p>
 * This is not a wrapper around the system {@code curl} binary. Requests are
 * performed through Java {@link HttpClient}, while local and private addresses,
 * methods, headers, timeouts, and response size are constrained by the API.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaCurlApi {

    private static final int DEFAULT_TIMEOUT_MS = 1_500;
    private static final int MAX_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_MAX_BYTES = 262_144;
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_REDIRECTS = 3;
    private static final Set<String> ALLOWED_METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"
    );
    private static final Set<String> BLOCKED_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "transfer-encoding", "upgrade"
    );
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Creates the Lua table for {@code mesh.curl}.
     *
     * @return curl API table
     */
    public LuaTable create() {
        LuaTable curl = new LuaTable();
        curl.set("get", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return execute(args, "GET");
            }
        });
        curl.set("request", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return execute(args, "GET");
            }
        });
        return curl;
    }

    private LuaValue execute(Varargs args, String defaultMethod) {
        try {
            CurlRequest request = parseRequest(args, defaultMethod);
            return perform(request);
        } catch (IOException e) {
            return errorTable(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorTable("Request interrupted");
        }
    }

    private CurlRequest parseRequest(Varargs args, String defaultMethod) {
        LuaValue first = args.arg(1);
        LuaTable options;
        String url;
        if (first.istable()) {
            options = first.checktable();
            url = LuaValueMapper.tableString(options, "url");
        } else {
            url = first.checkjstring();
            LuaValue second = args.arg(2);
            options = second.istable() ? second.checktable() : null;
        }
        if (url == null || url.isBlank()) {
            throw new LuaError("mesh.curl: url is required");
        }

        String method = defaultMethod;
        String body = null;
        int timeoutMs = DEFAULT_TIMEOUT_MS;
        int maxBytes = DEFAULT_MAX_BYTES;
        Map<String, String> headers = new LinkedHashMap<>();
        if (options != null) {
            String optionMethod = LuaValueMapper.tableString(options, "method");
            if (optionMethod != null && !optionMethod.isBlank()) {
                method = optionMethod;
            }
            body = LuaValueMapper.tableString(options, "body");
            timeoutMs = clamp(LuaValueMapper.tableInt(options, "timeout_ms", DEFAULT_TIMEOUT_MS), 100, MAX_TIMEOUT_MS);
            maxBytes = clamp(LuaValueMapper.tableInt(options, "max_bytes", DEFAULT_MAX_BYTES), 0, MAX_BYTES);
            LuaValue headerTable = options.get("headers");
            if (headerTable.istable()) {
                headers.putAll(luaHeaders(headerTable.checktable()));
            }
        }

        method = method.toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new LuaError("mesh.curl: method is not allowed: " + method);
        }

        URI uri = parseUri(url);
        validateUri(uri);
        return new CurlRequest(method, uri, headers, body, timeoutMs, maxBytes);
    }

    private LuaTable perform(CurlRequest request) throws IOException, InterruptedException {
        CurlRequest current = request;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpRequest httpRequest = buildHttpRequest(current);
            HttpResponse<InputStream> response =
                    HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (isRedirect(status)) {
                String location = response.headers().firstValue("location").orElse(null);
                if (location != null && redirect < MAX_REDIRECTS) {
                    response.body().close();
                    current = redirectRequest(current, status, location);
                    continue;
                }
            }

            try (InputStream input = response.body()) {
                CurlBody body = readBody(input, current.maxBytes());
                return responseTable(current.uri(), status, response.headers().map(), body);
            }
        }
        return errorTable("Too many redirects");
    }

    private HttpRequest buildHttpRequest(CurlRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(request.uri())
                .timeout(Duration.ofMillis(request.timeoutMs()))
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "MeshAppLua/1.0");
        request.headers().forEach(builder::header);

        HttpRequest.BodyPublisher bodyPublisher = "GET".equals(request.method()) || "HEAD".equals(request.method())
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body() != null ? request.body() : "", StandardCharsets.UTF_8);
        return builder.method(request.method(), bodyPublisher).build();
    }

    private CurlRequest redirectRequest(CurlRequest request, int status, String location) {
        URI redirected = request.uri().resolve(location);
        validateUri(redirected);
        String method = request.method();
        String body = request.body();
        if (status == 303 || ((status == 301 || status == 302) && !"GET".equals(method) && !"HEAD".equals(method))) {
            method = "GET";
            body = null;
        }
        return new CurlRequest(method, redirected, request.headers(), body, request.timeoutMs(), request.maxBytes());
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private CurlBody readBody(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int remaining = maxBytes - output.size();
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            if (read > remaining) {
                output.write(buffer, 0, remaining);
                truncated = true;
                break;
            }
            output.write(buffer, 0, read);
        }
        return new CurlBody(output.toString(StandardCharsets.UTF_8), truncated);
    }

    private LuaTable responseTable(URI uri,
                                   int status,
                                   Map<String, List<String>> headers,
                                   CurlBody body) {
        LuaTable table = new LuaTable();
        table.set("ok", LuaValue.valueOf(status >= 200 && status < 300));
        table.set("status", LuaValue.valueOf(status));
        table.set("url", LuaValue.valueOf(uri.toString()));
        table.set("body", LuaValue.valueOf(body.text()));
        table.set("truncated", LuaValue.valueOf(body.truncated()));
        table.set("headers", headersToLuaTable(headers));
        return table;
    }

    private LuaTable errorTable(String error) {
        LuaTable table = new LuaTable();
        table.set("ok", LuaValue.FALSE);
        table.set("status", LuaValue.valueOf(0));
        table.set("url", LuaValue.NIL);
        table.set("body", LuaValue.valueOf(""));
        table.set("truncated", LuaValue.FALSE);
        table.set("headers", new LuaTable());
        table.set("error", LuaValueMapper.stringOrNil(error));
        return table;
    }

    private LuaTable headersToLuaTable(Map<String, List<String>> headers) {
        LuaTable table = new LuaTable();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            table.set(entry.getKey().toLowerCase(Locale.ROOT), String.join(", ", entry.getValue()));
        }
        return table;
    }

    private Map<String, String> luaHeaders(LuaTable table) {
        Map<String, String> headers = new LinkedHashMap<>();
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil()) {
                break;
            }
            String name = key.checkjstring();
            String value = next.arg(2).checkjstring();
            headers.put(validateHeaderName(name), validateHeaderValue(value));
        }
        return headers;
    }

    private String validateHeaderName(String name) {
        String normalized = name != null ? name.trim().toLowerCase(Locale.ROOT) : "";
        if (normalized.isBlank() || !normalized.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) {
            throw new LuaError("mesh.curl: invalid header name");
        }
        if (BLOCKED_HEADERS.contains(normalized)) {
            throw new LuaError("mesh.curl: header is not allowed: " + name);
        }
        return name.trim();
    }

    private String validateHeaderValue(String value) {
        if (value == null || value.contains("\r") || value.contains("\n")) {
            throw new LuaError("mesh.curl: invalid header value");
        }
        return value;
    }

    private URI parseUri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new LuaError("mesh.curl: invalid url");
        }
    }

    private void validateUri(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new LuaError("mesh.curl: only http and https are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw new LuaError("mesh.curl: credentials in URL are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new LuaError("mesh.curl: host is required");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(address)) {
                    throw new LuaError("mesh.curl: local and private addresses are not allowed");
                }
            }
        } catch (IOException e) {
            throw new LuaError("mesh.curl: failed to resolve host");
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            return b0 == 0
                    || b0 == 10
                    || b0 == 127
                    || b0 >= 224
                    || (b0 == 100 && b1 >= 64 && b1 <= 127)
                    || (b0 == 169 && b1 == 254)
                    || (b0 == 172 && b1 >= 16 && b1 <= 31)
                    || (b0 == 192 && b1 == 0)
                    || (b0 == 192 && b1 == 168)
                    || (b0 == 198 && (b1 == 18 || b1 == 19));
        }
        if (address instanceof Inet6Address) {
            int b0 = bytes[0] & 0xff;
            return (b0 & 0xfe) == 0xfc;
        }
        return true;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record CurlRequest(String method,
                               URI uri,
                               Map<String, String> headers,
                               String body,
                               int timeoutMs,
                               int maxBytes) {}

    private record CurlBody(String text, boolean truncated) {}
}
