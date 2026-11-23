package com.anarvia.interview.ratelimiter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Minimal JSON/HTTP helpers so the service needs no framework or JSON library. */
final class HttpUtil {

    private HttpUtil() {
    }

    /**
     * Base handler: maps domain exceptions to status codes in one place and
     * always closes the exchange, so a handler bug can't leak a connection.
     */
    abstract static class JsonHandler implements HttpHandler {

        @Override
        public final void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                try {
                    doHandle(exchange);
                } catch (InvalidRequestException e) {
                    sendError(exchange, 400, e.getMessage());
                } catch (UnknownTierException e) {
                    sendError(exchange, 404, e.getMessage());
                } catch (RuntimeException e) {
                    System.err.println("unhandled error on " + exchange.getRequestURI() + ": " + e);
                    sendError(exchange, 500, "internal error");
                }
            }
        }

        abstract void doHandle(HttpExchange exchange) throws IOException;
    }

    static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, "{\"error\":" + quote(message) + "}");
    }

    static void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendError(exchange, 405, "method not allowed; use " + allowed);
    }

    static Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            params.putIfAbsent(key, value);
        }
        return params;
    }

    static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("malformed URL encoding: " + raw);
        }
    }

    /** JSON string literal; client ids are caller-controlled, so they must be escaped. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static String toJson(TierConfig config) {
        return "{\"tier\":\"" + config.tier() + "\",\"requestsPerSecond\":" + config.requestsPerSecond() + "}";
    }
}
