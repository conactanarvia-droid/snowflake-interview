package com.anarvia.interview.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** End-to-end over real HTTP on a random loopback port, with a manual clock behind it. */
class RateLimitServerTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private ManualClock clock;
    private RateLimitServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        clock = new ManualClock();
        server = new RateLimitServer(0, new RateLimiterService(clock));
        server.start();
        base = "http://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> send(String method, String path, String clientId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (clientId != null) {
            b.header("X-Client-Id", clientId);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> check(String clientId) throws Exception {
        return send("POST", "/v1/check", clientId);
    }

    @Nested
    @DisplayName("POST /v1/check")
    class Check {

        @Test
        @DisplayName("allowed → 200 with rate-limit headers and JSON body")
        void allowed() throws Exception {
            HttpResponse<String> r = check("acme");
            assertEquals(200, r.statusCode());
            assertEquals("50", r.headers().firstValue("X-RateLimit-Limit").orElseThrow());
            assertEquals("49", r.headers().firstValue("X-RateLimit-Remaining").orElseThrow());
            assertTrue(r.headers().firstValue("Retry-After").isEmpty());
            assertEquals("{\"allowed\":true,\"clientId\":\"acme\",\"tier\":\"SILVER\",\"limit\":50,"
                + "\"remaining\":49,\"retryAfterMillis\":0}", r.body());
        }

        @Test
        @DisplayName("over the limit → 429 with Retry-After in whole seconds (at least 1)")
        void rejected() throws Exception {
            for (int i = 0; i < 50; i++) {
                assertEquals(200, check("acme").statusCode());
            }
            HttpResponse<String> r = check("acme");
            assertEquals(429, r.statusCode());
            assertEquals("1", r.headers().firstValue("Retry-After").orElseThrow());
            assertTrue(r.body().contains("\"allowed\":false"));
            assertTrue(r.body().contains("\"retryAfterMillis\":20"));
        }

        @Test
        @DisplayName("missing X-Client-Id → 400")
        void missingHeader() throws Exception {
            HttpResponse<String> r = check(null);
            assertEquals(400, r.statusCode());
            assertTrue(r.body().contains("client id is required"));
        }

        @Test
        @DisplayName("non-POST → 405 with Allow header")
        void wrongMethod() throws Exception {
            HttpResponse<String> r = send("GET", "/v1/check", "acme");
            assertEquals(405, r.statusCode());
            assertEquals("POST", r.headers().firstValue("Allow").orElseThrow());
        }

        @Test
        @DisplayName("paths that merely start with /v1/check → 404")
        void prefixPathsAreNotTheEndpoint() throws Exception {
            assertEquals(404, send("POST", "/v1/checkout", "acme").statusCode());
            assertEquals(404, send("POST", "/v1/check/extra", "acme").statusCode());
            assertEquals(404, send("GET", "/nope", null).statusCode());
        }

        @Test
        @DisplayName("client id is JSON-escaped in the response")
        void clientIdEscaped() throws Exception {
            HttpResponse<String> r = check("evil\"id\\");
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"clientId\":\"evil\\\"id\\\\\""), r.body());
        }
    }

    @Nested
    @DisplayName("admin API: runtime changes over HTTP")
    class Admin {

        @Test
        @DisplayName("GET /v1/admin/tiers lists all tiers")
        void listTiers() throws Exception {
            HttpResponse<String> r = send("GET", "/v1/admin/tiers", null);
            assertEquals(200, r.statusCode());
            assertEquals("[{\"tier\":\"SILVER\",\"requestsPerSecond\":50},"
                + "{\"tier\":\"GOLD\",\"requestsPerSecond\":100},"
                + "{\"tier\":\"PLATINUM\",\"requestsPerSecond\":1000}]", r.body());
        }

        @Test
        @DisplayName("upgrading a client over HTTP takes effect on its next check")
        void upgradeClient() throws Exception {
            HttpResponse<String> put = send("PUT", "/v1/admin/clients/acme/tier?tier=platinum", null);
            assertEquals(200, put.statusCode());
            assertEquals("{\"clientId\":\"acme\",\"tier\":\"PLATINUM\"}", put.body());
            assertTrue(send("GET", "/v1/admin/clients/acme/tier", null).body().contains("PLATINUM"));

            HttpResponse<String> r = check("acme");
            assertEquals("1000", r.headers().firstValue("X-RateLimit-Limit").orElseThrow());
        }

        @Test
        @DisplayName("changing a tier's rps over HTTP takes effect without restart")
        void updateTierRps() throws Exception {
            assertEquals(200, send("PUT", "/v1/admin/tiers/SILVER?rps=3", null).statusCode());
            assertEquals(200, check("acme").statusCode());
            assertEquals(200, check("acme").statusCode());
            assertEquals(200, check("acme").statusCode());
            assertEquals(429, check("acme").statusCode());
        }

        @Test
        @DisplayName("client ids in the path are URL-decoded")
        void urlDecodedClientId() throws Exception {
            assertEquals(200, send("PUT", "/v1/admin/clients/team%20a/tier?tier=GOLD", null).statusCode());
            assertEquals("100", check("team a").headers().firstValue("X-RateLimit-Limit").orElseThrow());
        }

        @Test
        @DisplayName("bad admin input → 4xx, and nothing changes")
        void badInput() throws Exception {
            assertEquals(404, send("PUT", "/v1/admin/tiers/BRONZE?rps=10", null).statusCode());
            assertEquals(404, send("PUT", "/v1/admin/clients/acme/tier?tier=BRONZE", null).statusCode());
            assertEquals(400, send("PUT", "/v1/admin/clients/acme/tier", null).statusCode());
            assertEquals(400, send("PUT", "/v1/admin/tiers/GOLD", null).statusCode());
            assertEquals(400, send("PUT", "/v1/admin/tiers/GOLD?rps=abc", null).statusCode());
            assertEquals(400, send("PUT", "/v1/admin/tiers/GOLD?rps=0", null).statusCode());
            assertEquals(400, send("PUT", "/v1/admin/tiers/GOLD?rps=-5", null).statusCode());
            assertEquals(405, send("DELETE", "/v1/admin/tiers", null).statusCode());
            assertEquals(404, send("GET", "/v1/admin/nothing", null).statusCode());
            assertTrue(send("GET", "/v1/admin/tiers", null).body().contains("{\"tier\":\"GOLD\",\"requestsPerSecond\":100}"));
        }

        @Test
        @DisplayName("GET /v1/admin/stats reports counters")
        void stats() throws Exception {
            check("a");
            check("b");
            assertEquals("{\"allowed\":2,\"rejected\":0,\"activeBuckets\":2}",
                send("GET", "/v1/admin/stats", null).body());
        }
    }

    @Nested
    @DisplayName("5. resilience: bad requests and handler failures don't take the server down")
    class Resilience {

        @Test
        @DisplayName("malformed URL encoding → 4xx and the server keeps serving")
        void malformedEncoding() throws Exception {
            // HttpClient refuses to send an invalid escape, so write the request by hand.
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                socket.setSoTimeout(5_000);
                OutputStream out = socket.getOutputStream();
                out.write(("GET /v1/admin/clients/bad%zz/tier HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
                assertTrue(statusLine.startsWith("HTTP/1.1 4"), statusLine);
            }
            assertEquals(200, check("acme").statusCode());
        }

        @Test
        @DisplayName("a handler that throws unexpectedly → 500, connection closed, next request fine")
        void unexpectedExceptionBecomes500() throws Exception {
            HttpServer raw = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            raw.createContext("/boom", new HttpUtil.JsonHandler() {
                @Override
                void doHandle(com.sun.net.httpserver.HttpExchange exchange) {
                    throw new IllegalStateException("simulated bug");
                }
            });
            raw.start();
            try {
                URI uri = URI.create("http://localhost:" + raw.getAddress().getPort() + "/boom");
                for (int i = 0; i < 3; i++) {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(500, r.statusCode());
                    assertEquals("{\"error\":\"internal error\"}", r.body(), "internals not leaked");
                }
            } finally {
                raw.stop(0);
            }
        }
    }
}
