package com.anarvia.interview.ratelimiter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo-only load generator: sends real HTTP requests to {@code POST /v1/check}
 * at a configurable rate per client and records what came back, per second,
 * for the dashboard. Not used by the service itself.
 */
final class TrafficGenerator implements AutoCloseable {

    static final int WINDOW_SECONDS = 60;
    static final int MAX_RPS = 5_000;
    private static final int MAX_IN_FLIGHT_PER_CLIENT = 64;
    private static final long TICK_MS = 10;

    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    private final URI checkUri;
    private final long startNanos = System.nanoTime();
    private final ConcurrentHashMap<String, ClientLoad> clients = new ConcurrentHashMap<>();
    private final List<String> order = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "traffic-ticker");
        t.setDaemon(true);
        return t;
    });

    /** Per-client target rate plus a 60-second ring buffer of outcomes. */
    static final class ClientLoad {
        final String id;
        volatile int targetRps;
        volatile long lastRemaining = -1;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        private double carry; // ticker thread only

        private final long[] allowed = new long[WINDOW_SECONDS];
        private final long[] rejected = new long[WINDOW_SECONDS];
        private final long[] stamp = new long[WINDOW_SECONDS];

        ClientLoad(String id) {
            this.id = id;
            java.util.Arrays.fill(stamp, -1);
        }

        synchronized void record(long second, boolean ok) {
            int i = (int) (second % WINDOW_SECONDS);
            if (stamp[i] != second) {
                stamp[i] = second;
                allowed[i] = 0;
                rejected[i] = 0;
            }
            if (ok) {
                allowed[i]++;
            } else {
                rejected[i]++;
            }
        }

        /** The last WINDOW_SECONDS <i>completed</i> seconds, oldest first: {allowed[], rejected[]}. */
        synchronized long[][] window(long currentSecond) {
            long[] a = new long[WINDOW_SECONDS];
            long[] r = new long[WINDOW_SECONDS];
            for (int k = 0; k < WINDOW_SECONDS; k++) {
                long s = currentSecond - WINDOW_SECONDS + k;
                if (s < 0) {
                    continue;
                }
                int i = (int) (s % WINDOW_SECONDS);
                if (stamp[i] == s) {
                    a[k] = allowed[i];
                    r[k] = rejected[i];
                }
            }
            return new long[][] {a, r};
        }
    }

    record BurstResult(int allowed, int rejected, int errors) {
    }

    TrafficGenerator(URI baseUri) {
        this.checkUri = baseUri.resolve("/v1/check");
    }

    void start() {
        ticker.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    void addClient(String id) {
        if (clients.putIfAbsent(id, new ClientLoad(id)) == null) {
            order.add(id);
        }
    }

    boolean hasClient(String id) {
        return clients.containsKey(id);
    }

    void setTargetRps(String id, int rps) {
        ClientLoad c = clients.get(id);
        if (c == null) {
            throw new InvalidRequestException("unknown demo client '" + id + "'");
        }
        c.targetRps = Math.max(0, Math.min(MAX_RPS, rps));
    }

    List<ClientLoad> clients() {
        List<ClientLoad> list = new ArrayList<>();
        for (String id : order) {
            list.add(clients.get(id));
        }
        return list;
    }

    long currentSecond() {
        return (System.nanoTime() - startNanos) / 1_000_000_000L;
    }

    /** Fires {@code n} requests as close to simultaneously as possible and waits for all of them. */
    BurstResult burst(String id, int n) {
        ClientLoad c = clients.get(id);
        if (c == null) {
            throw new InvalidRequestException("unknown demo client '" + id + "'");
        }
        List<CompletableFuture<Integer>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            futures.add(send(c));
        }
        int ok = 0;
        int limited = 0;
        int failed = 0;
        for (CompletableFuture<Integer> f : futures) {
            int status = f.join();
            if (status == 200) {
                ok++;
            } else if (status == 429) {
                limited++;
            } else {
                failed++;
            }
        }
        return new BurstResult(ok, limited, failed);
    }

    private void tick() {
        try {
            for (ClientLoad c : clients.values()) {
                c.carry += c.targetRps * (TICK_MS / 1000.0);
                int n = (int) c.carry;
                c.carry -= n;
                for (int i = 0; i < n; i++) {
                    if (c.inFlight.get() >= MAX_IN_FLIGHT_PER_CLIENT) {
                        c.skipped.incrementAndGet();
                    } else {
                        send(c);
                    }
                }
            }
        } catch (RuntimeException e) {
            System.err.println("traffic tick failed: " + e);
        }
    }

    /** Sends one check; completes with the HTTP status, or -1 on a transport error. */
    private CompletableFuture<Integer> send(ClientLoad c) {
        HttpRequest request = HttpRequest.newBuilder(checkUri)
            .header(CheckHandler.CLIENT_ID_HEADER, c.id)
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        c.inFlight.incrementAndGet();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .handle((response, error) -> {
                c.inFlight.decrementAndGet();
                if (error != null) {
                    c.errors.incrementAndGet();
                    return -1;
                }
                int status = response.statusCode();
                response.headers().firstValue("X-RateLimit-Remaining")
                    .ifPresent(v -> c.lastRemaining = Long.parseLong(v));
                if (status == 200 || status == 429) {
                    c.record(currentSecond(), status == 200);
                } else {
                    c.errors.incrementAndGet();
                }
                return status;
            });
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }
}
