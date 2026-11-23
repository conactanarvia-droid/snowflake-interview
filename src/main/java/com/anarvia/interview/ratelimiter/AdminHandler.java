package com.anarvia.interview.ratelimiter;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runtime admin API under {@code /v1/admin}:
 * <pre>
 * GET /v1/admin/tiers                                  list current limits
 * PUT /v1/admin/tiers/{tier}?rps=200                   change a tier's limit
 * GET /v1/admin/clients/{clientId}/tier                show a client's tier
 * PUT /v1/admin/clients/{clientId}/tier?tier=PLATINUM  move a client to a tier
 * GET /v1/admin/stats                                  allowed/rejected counters
 * </pre>
 * No authentication: the server binds to loopback only (see {@link RateLimitServer}).
 */
final class AdminHandler extends HttpUtil.JsonHandler {

    static final String PREFIX = "/v1/admin/";

    private final RateLimiterService service;

    AdminHandler(RateLimiterService service) {
        this.service = service;
    }

    @Override
    void doHandle(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getRawPath();
        List<String> segments = Arrays.stream(rawPath.substring(PREFIX.length()).split("/"))
            .map(HttpUtil::decode)
            .toList();
        String method = exchange.getRequestMethod();

        if (segments.equals(List.of("tiers"))) {
            if (!"GET".equals(method)) {
                HttpUtil.methodNotAllowed(exchange, "GET");
                return;
            }
            String body = service.listTiers().stream().map(HttpUtil::toJson).collect(Collectors.joining(",", "[", "]"));
            HttpUtil.sendJson(exchange, 200, body);

        } else if (segments.size() == 2 && segments.get(0).equals("tiers")) {
            if (!"PUT".equals(method)) {
                HttpUtil.methodNotAllowed(exchange, "PUT");
                return;
            }
            Tier tier = Tier.parse(segments.get(1));
            int rps = parseRps(HttpUtil.queryParams(exchange).get("rps"));
            HttpUtil.sendJson(exchange, 200, HttpUtil.toJson(service.updateTier(tier, rps)));

        } else if (segments.size() == 3 && segments.get(0).equals("clients") && segments.get(2).equals("tier")) {
            String clientId = segments.get(1);
            switch (method) {
                case "GET" -> sendClientTier(exchange, clientId, service.tierOf(clientId));
                case "PUT" -> {
                    Tier tier = Tier.parse(HttpUtil.queryParams(exchange).get("tier"));
                    service.assignClientTier(clientId, tier);
                    sendClientTier(exchange, clientId, tier);
                }
                default -> HttpUtil.methodNotAllowed(exchange, "GET, PUT");
            }

        } else if (segments.equals(List.of("stats"))) {
            if (!"GET".equals(method)) {
                HttpUtil.methodNotAllowed(exchange, "GET");
                return;
            }
            RateLimiterService.Stats s = service.stats();
            HttpUtil.sendJson(exchange, 200, "{\"allowed\":" + s.allowed() + ",\"rejected\":" + s.rejected()
                + ",\"activeBuckets\":" + s.activeBuckets() + "}");

        } else {
            HttpUtil.sendError(exchange, 404, "not found");
        }
    }

    private static void sendClientTier(HttpExchange exchange, String clientId, Tier tier) throws IOException {
        HttpUtil.sendJson(exchange, 200, "{\"clientId\":" + HttpUtil.quote(clientId) + ",\"tier\":\"" + tier + "\"}");
    }

    private static int parseRps(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("query parameter 'rps' is required");
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("rps must be a positive integer, got '" + raw + "'");
        }
    }
}
