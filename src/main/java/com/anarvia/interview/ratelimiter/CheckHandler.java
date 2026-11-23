package com.anarvia.interview.ratelimiter;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * {@code POST /v1/check} with header {@code X-Client-Id}.
 * 200 when allowed, 429 + {@code Retry-After} when over the limit, 400 when
 * the header is missing or blank.
 */
final class CheckHandler extends HttpUtil.JsonHandler {

    static final String PATH = "/v1/check";
    static final String CLIENT_ID_HEADER = "X-Client-Id";

    private final RateLimiterService service;

    CheckHandler(RateLimiterService service) {
        this.service = service;
    }

    @Override
    void doHandle(HttpExchange exchange) throws IOException {
        // HttpServer contexts match by prefix; reject /v1/checkXYZ and /v1/check/foo.
        if (!PATH.equals(exchange.getRequestURI().getPath())) {
            HttpUtil.sendError(exchange, 404, "not found");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            HttpUtil.methodNotAllowed(exchange, "POST");
            return;
        }

        RateLimitResult result = service.tryAcquire(exchange.getRequestHeaders().getFirst(CLIENT_ID_HEADER));

        Headers headers = exchange.getResponseHeaders();
        headers.set("X-RateLimit-Limit", String.valueOf(result.limit()));
        headers.set("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        if (!result.allowed()) {
            // Retry-After is whole seconds per RFC 9110; round up so a client that obeys it succeeds.
            headers.set("Retry-After", String.valueOf(Math.max(1, Math.ceilDiv(result.retryAfterMillis(), 1000L))));
        }
        HttpUtil.sendJson(exchange, result.allowed() ? 200 : 429, toJson(result));
    }

    private static String toJson(RateLimitResult r) {
        return "{\"allowed\":" + r.allowed()
            + ",\"clientId\":" + HttpUtil.quote(r.clientId())
            + ",\"tier\":\"" + r.tier() + "\""
            + ",\"limit\":" + r.limit()
            + ",\"remaining\":" + r.remaining()
            + ",\"retryAfterMillis\":" + r.retryAfterMillis()
            + "}";
    }
}
