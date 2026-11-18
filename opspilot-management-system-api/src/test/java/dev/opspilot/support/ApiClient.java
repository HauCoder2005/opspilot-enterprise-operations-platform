package dev.opspilot.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Client HTTP thật cho integration test: đi qua đầy đủ filter chain (Spring Security, CORS, request id...). */
public final class ApiClient {

    public static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final int port;

    public ApiClient(int port) {
        this.port = port;
    }

    public Response login(String orgCode, String identifier, String password, String clientType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orgCode", orgCode);
        body.put("identifier", identifier);
        body.put("password", password);
        body.put("clientType", clientType);
        return post("/api/v1/auth/login", json(body));
    }

    public static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    public Response post(String path, String jsonBody, String... headers) {
        return send("POST", path, jsonBody, "application/json", headers);
    }

    public Response get(String path, String... headers) {
        return send("GET", path, null, null, headers);
    }

    public Response send(String method, String path, String body, String contentType, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("User-Agent", "opspilot-it")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode node = raw == null || raw.isBlank() || !raw.trim().startsWith("{") ? null : JSON.readTree(raw);
            return new Response(response.statusCode(), raw, node, response.headers().map());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    public record Response(int status, String rawBody, JsonNode body, Map<String, List<String>> headers) {
        public String code() {
            return text("code");
        }

        public String text(String field) {
            return body == null || body.get(field) == null || body.get(field).isNull() ? null : body.get(field).asString();
        }

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst().orElse(null);
        }

        public List<String> setCookies() {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase("set-cookie"))
                    .flatMap(e -> e.getValue().stream())
                    .toList();
        }

        /** Giá trị cookie từ Set-Cookie (null nếu không có). */
        public String cookie(String name) {
            return setCookieHeader(name).map(h -> h.substring(name.length() + 1, h.indexOf(';') < 0 ? h.length() : h.indexOf(';')))
                    .orElse(null);
        }

        public Optional<String> setCookieHeader(String name) {
            return setCookies().stream().filter(h -> h.startsWith(name + "=")).findFirst();
        }
    }
}
