package com.anarvia.interview.ratelimiter;

import java.io.IOException;
import java.time.Duration;

/**
 * Starts the rate limiting service. Run with:
 *
 * <pre>
 * mvn -q compile
 * java -cp target/classes com.anarvia.interview.ratelimiter.RateLimiterApplication [port]
 * </pre>
 */
public final class RateLimiterApplication {

    static final Duration IDLE_THRESHOLD = Duration.ofSeconds(60);
    static final Duration SWEEP_PERIOD = Duration.ofSeconds(60);

    private RateLimiterApplication() {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        RateLimiterService service = new RateLimiterService();
        IdleBucketEvictor evictor = new IdleBucketEvictor(service, IDLE_THRESHOLD, SWEEP_PERIOD);
        RateLimitServer server = new RateLimitServer(port, service);

        evictor.start();
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            evictor.close();
        }, "shutdown"));

        String base = "http://localhost:" + server.port();
        System.out.println("Rate limiter listening on " + base);
        System.out.println("Tiers: " + service.listTiers());
        System.out.println("""

            Try:
              curl -i -X POST -H 'X-Client-Id: acme' %1$s/v1/check
              curl -X PUT '%1$s/v1/admin/clients/acme/tier?tier=PLATINUM'
              curl -X PUT '%1$s/v1/admin/tiers/GOLD?rps=200'
              curl %1$s/v1/admin/tiers
              curl %1$s/v1/admin/stats
            """.formatted(base));
    }
}
