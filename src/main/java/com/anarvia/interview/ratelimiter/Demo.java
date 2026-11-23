package com.anarvia.interview.ratelimiter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Visual live demo. Starts the real rate limiter service, a traffic generator
 * that hits it over real HTTP, and a dashboard to drive and watch it:
 *
 * <pre>
 * mvn -q compile
 * java -cp target/classes com.anarvia.interview.ratelimiter.Demo
 * open http://localhost:8090
 * </pre>
 *
 * Admin actions from the dashboard are forwarded to the service's own admin
 * API over HTTP, exactly as a curl call would make them.
 */
public final class Demo {

    private static final String PAGE = "/ratelimiter/demo.html";

    private final RateLimiterService service;
    private final TrafficGenerator traffic;
    private final String serviceBase;
    private final HttpClient http = HttpClient.newHttpClient();

    private Demo(RateLimiterService service, TrafficGenerator traffic, String serviceBase) {
        this.service = service;
        this.traffic = traffic;
        this.serviceBase = serviceBase;
    }

    public static void main(String[] args) throws IOException {
        int apiPort = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int uiPort = args.length > 1 ? Integer.parseInt(args[1]) : 8090;

        RateLimiterService service = new RateLimiterService();
        IdleBucketEvictor evictor = new IdleBucketEvictor(service, Duration.ofSeconds(60), Duration.ofSeconds(60));
        RateLimitServer api = new RateLimitServer(apiPort, service);
        api.start();
        evictor.start();
        String serviceBase = "http://localhost:" + api.port();

        // Three demo clients, one per tier.
        service.assignClientTier("acme", Tier.SILVER);
        service.assignClientTier("globex", Tier.GOLD);
        service.assignClientTier("initech", Tier.PLATINUM);
        TrafficGenerator traffic = new TrafficGenerator(URI.create(serviceBase));
        traffic.addClient("acme");
        traffic.addClient("globex");
        traffic.addClient("initech");
        traffic.start();

        Demo demo = new Demo(service, traffic, serviceBase);
        HttpServer ui = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), uiPort), 0);
        ExecutorService uiExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ui.setExecutor(uiExecutor);
        ui.createContext("/", demo.new PageHandler());
        ui.createContext("/api/", demo.new ApiHandler());
        ui.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ui.stop(0);
            uiExecutor.shutdown();
            traffic.close();
            api.close();
            evictor.close();
        }, "shutdown"));

        System.out.println("Rate limiter service : " + serviceBase);
        System.out.println("Live dashboard       : http://localhost:" + ui.getAddress().getPort());
        System.out.println("Ctrl+C to stop.");
    }

    private final class PageHandler extends HttpUtil.JsonHandler {
        @Override
        void doHandle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/") && !path.equals("/index.html")) {
                HttpUtil.sendError(exchange, 404, "not found");
                return;
            }
            try (InputStream in = Demo.class.getResourceAsStream(PAGE)) {
                if (in == null) {
                    HttpUtil.sendError(exchange, 500, "dashboard page missing; run mvn compile");
                    return;
                }
                byte[] body = in.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }

    /**
     * Dashboard API:
     * GET  /api/state                     everything the dashboard draws
     * POST /api/load?id=&rps=             set a client's offered load
     * POST /api/burst?id=&n=              fire n requests at once
     * POST /api/clients?id=               add a demo client
     * POST /api/assign?id=&tier=          → PUT /v1/admin/clients/{id}/tier on the service
     * POST /api/tier-limit?tier=&rps=     → PUT /v1/admin/tiers/{tier} on the service
     */
    private final class ApiHandler extends HttpUtil.JsonHandler {
        @Override
        void doHandle(HttpExchange exchange) throws IOException {
            String action = exchange.getRequestURI().getPath().substring("/api/".length());
            String method = exchange.getRequestMethod();
            Map<String, String> q = HttpUtil.queryParams(exchange);

            if (action.equals("state") && method.equals("GET")) {
                HttpUtil.sendJson(exchange, 200, stateJson());
                return;
            }
            if (!method.equals("POST")) {
                HttpUtil.methodNotAllowed(exchange, "POST");
                return;
            }
            switch (action) {
                case "load" -> {
                    traffic.setTargetRps(required(q, "id"), parseInt(required(q, "rps")));
                    HttpUtil.sendJson(exchange, 200, "{\"ok\":true}");
                }
                case "burst" -> {
                    int n = Math.max(1, Math.min(2_000, parseInt(required(q, "n"))));
                    TrafficGenerator.BurstResult r = traffic.burst(required(q, "id"), n);
                    HttpUtil.sendJson(exchange, 200, "{\"sent\":" + n + ",\"allowed\":" + r.allowed()
                        + ",\"rejected\":" + r.rejected() + ",\"errors\":" + r.errors() + "}");
                }
                case "clients" -> {
                    String id = required(q, "id");
                    service.tierOf(id); // same validation the service applies
                    traffic.addClient(id);
                    HttpUtil.sendJson(exchange, 200, "{\"ok\":true}");
                }
                case "assign" -> forward(exchange, "/v1/admin/clients/" + encode(required(q, "id"))
                    + "/tier?tier=" + encode(required(q, "tier")));
                case "tier-limit" -> forward(exchange, "/v1/admin/tiers/" + encode(required(q, "tier"))
                    + "?rps=" + encode(required(q, "rps")));
                default -> HttpUtil.sendError(exchange, 404, "not found");
            }
        }
    }

    /** Calls the service's real admin API and relays its status and body. */
    private void forward(HttpExchange exchange, String pathAndQuery) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(serviceBase + pathAndQuery))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            HttpUtil.sendJson(exchange, response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HttpUtil.sendError(exchange, 503, "interrupted");
        }
    }

    private String stateJson() {
        Map<Tier, Integer> limits = service.listTiers().stream()
            .collect(Collectors.toMap(TierConfig::tier, TierConfig::requestsPerSecond));
        long second = traffic.currentSecond();
        String clients = traffic.clients().stream().map(c -> {
            Tier tier = service.tierOf(c.id);
            long[][] w = c.window(second);
            return "{\"id\":" + HttpUtil.quote(c.id)
                + ",\"tier\":\"" + tier + "\""
                + ",\"limit\":" + limits.get(tier)
                + ",\"targetRps\":" + c.targetRps
                + ",\"remaining\":" + c.lastRemaining
                + ",\"inFlight\":" + c.inFlight.get()
                + ",\"errors\":" + c.errors.get()
                + ",\"allowed\":" + Arrays.toString(w[0]).replace(" ", "")
                + ",\"rejected\":" + Arrays.toString(w[1]).replace(" ", "")
                + "}";
        }).collect(Collectors.joining(",", "[", "]"));
        String tiers = service.listTiers().stream().map(HttpUtil::toJson).collect(Collectors.joining(",", "[", "]"));
        RateLimiterService.Stats s = service.stats();
        return "{\"serviceUrl\":" + HttpUtil.quote(serviceBase)
            + ",\"tiers\":" + tiers
            + ",\"stats\":{\"allowed\":" + s.allowed() + ",\"rejected\":" + s.rejected()
            + ",\"activeBuckets\":" + s.activeBuckets() + "}"
            + ",\"clients\":" + clients + "}";
    }

    private static String required(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isBlank()) {
            throw new InvalidRequestException("query parameter '" + key + "' is required");
        }
        return v;
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("not a number: '" + raw + "'");
        }
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
