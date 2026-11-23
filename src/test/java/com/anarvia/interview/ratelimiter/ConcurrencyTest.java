package com.anarvia.interview.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * 3. Thread safety. The clock is frozen in most of these, which turns "no
 * lost updates" into an exact equality: with no refill, a client can be
 * allowed exactly its capacity, no matter how many threads race for it.
 * Every task waits on a start latch so the threads actually collide.
 */
class ConcurrencyTest {

    private static final int THREADS = 64;

    private ManualClock clock;
    private RateLimiterService service;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        clock = new ManualClock();
        service = new RateLimiterService(clock);
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    /** Runs {@code task} on every thread at once; returns the sum of the results. */
    private int runConcurrently(Callable<Integer> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        int total = 0;
        for (Future<Integer> f : futures) {
            total += f.get(15, TimeUnit.SECONDS); // also a deadlock detector
        }
        return total;
    }

    @RepeatedTest(5)
    @DisplayName("64 threads hammering one client: exactly capacity allowed, no token spent twice")
    void noDoubleSpend() throws Exception {
        int allowed = runConcurrently(() -> {
            int ok = 0;
            for (int i = 0; i < 20; i++) {
                if (service.tryAcquire("acme").allowed()) {
                    ok++;
                }
            }
            return ok;
        });
        assertEquals(50, allowed);
        assertEquals(50, service.stats().allowed());
        assertEquals(THREADS * 20 - 50, service.stats().rejected());
    }

    @RepeatedTest(5)
    @DisplayName("concurrent first requests for a new client create exactly one bucket")
    void firstRequestRaceCreatesOneBucket() throws Exception {
        service.assignClientTier("newbie", Tier.GOLD);
        // 64 threads × 2 = 128 attempts against a 100-token bucket. Two buckets would allow up to 128.
        int allowed = runConcurrently(() -> {
            int ok = 0;
            for (int i = 0; i < 2; i++) {
                if (service.tryAcquire("newbie").allowed()) {
                    ok++;
                }
            }
            return ok;
        });
        assertEquals(100, allowed);
        assertEquals(1, service.stats().activeBuckets());
    }

    @Test
    @DisplayName("many clients on mixed tiers concurrently: each gets exactly its own capacity")
    void clientsIsolatedUnderLoad() throws Exception {
        Tier[] tiers = Tier.values();
        for (int c = 0; c < 12; c++) {
            service.assignClientTier("client-" + c, tiers[c % tiers.length]);
        }
        Map<String, AtomicInteger> allowedPerClient = new ConcurrentHashMap<>();
        runConcurrently(() -> {
            for (int i = 0; i < 400; i++) {
                String id = "client-" + (i % 12);
                if (service.tryAcquire(id).allowed()) {
                    allowedPerClient.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                }
            }
            return 0;
        });
        // 64 threads × 400 / 12 ≈ 2133 attempts per client: enough to exhaust even PLATINUM.
        for (int c = 0; c < 12; c++) {
            Tier tier = tiers[c % tiers.length];
            assertEquals(tier.defaultRps(), allowedPerClient.get("client-" + c).get(), "client-" + c + " on " + tier);
        }
    }

    @RepeatedTest(5)
    @DisplayName("tier limit updates racing with requests never let a client exceed its starting capacity")
    void tierUpdatesDuringTraffic() throws Exception {
        service.assignClientTier("acme", Tier.GOLD);
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread admin = new Thread(() -> {
            int i = 0;
            while (!stop.get()) {
                service.updateTier(Tier.GOLD, i++ % 2 == 0 ? 100 : 30); // flip limit constantly
            }
        });
        admin.start();
        int allowed;
        try {
            allowed = runConcurrently(() -> {
                int ok = 0;
                for (int i = 0; i < 20; i++) {
                    if (service.tryAcquire("acme").allowed()) {
                        ok++;
                    }
                }
                return ok;
            });
        } finally {
            stop.set(true);
            admin.join();
        }
        // Frozen clock: no refill. Clamping can only remove tokens, so at most the initial 100.
        assertTrue(allowed <= 100, "allowed " + allowed);
        assertTrue(allowed >= 30, "never clamped below the smaller limit; allowed " + allowed);
    }

    @RepeatedTest(5)
    @DisplayName("client tier reassignment racing with requests never mints tokens")
    void tierReassignmentDuringTraffic() throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread admin = new Thread(() -> {
            int i = 0;
            while (!stop.get()) {
                service.assignClientTier("acme", i++ % 2 == 0 ? Tier.PLATINUM : Tier.SILVER);
            }
        });
        service.tryAcquire("acme"); // SILVER bucket with 49 left
        admin.start();
        int allowed;
        try {
            allowed = runConcurrently(() -> {
                int ok = 0;
                for (int i = 0; i < 20; i++) {
                    if (service.tryAcquire("acme").allowed()) {
                        ok++;
                    }
                }
                return ok;
            });
        } finally {
            stop.set(true);
            admin.join();
        }
        assertTrue(allowed <= 49, "upgrade must not refill the bucket; allowed " + allowed);
    }

    @RepeatedTest(5)
    @DisplayName("with time moving, total allowed never exceeds capacity + rps × elapsed")
    void respectsBoundWithAdvancingClock() throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread ticker = new Thread(() -> {
            for (int ms = 0; ms < 1_000 && !stop.get(); ms++) {
                clock.advanceMillis(1);
                Thread.onSpinWait();
            }
        });
        ticker.start();
        int allowed;
        try {
            allowed = runConcurrently(() -> {
                int ok = 0;
                for (int i = 0; i < 500; i++) {
                    if (service.tryAcquire("acme").allowed()) {
                        ok++;
                    }
                }
                return ok;
            });
        } finally {
            stop.set(true);
            ticker.join();
        }
        assertTrue(allowed <= 50 + 50, "SILVER over ≤1s: at most 100, got " + allowed);
        assertTrue(allowed >= 50, "at least the initial burst, got " + allowed);
    }

    @RepeatedTest(10)
    @DisplayName("idle eviction racing with requests never gives the client an extra token")
    void evictionRaceDoesNotLeakTokens() throws Exception {
        service.tryAcquire("acme");
        clock.advance(Duration.ofSeconds(10)); // bucket now idle beyond the 1s threshold

        AtomicBoolean stop = new AtomicBoolean(false);
        Thread evictor = new Thread(() -> {
            while (!stop.get()) {
                service.evictIdleBuckets(Duration.ofSeconds(1));
            }
        });
        evictor.start();
        int allowed;
        try {
            allowed = runConcurrently(() -> {
                int ok = 0;
                for (int i = 0; i < 5; i++) {
                    if (service.tryAcquire("acme").allowed()) {
                        ok++;
                    }
                }
                return ok;
            });
        } finally {
            stop.set(true);
            evictor.join();
        }
        // Whether the old (refilled) bucket survives or a fresh one replaces it, the client
        // has exactly one full bucket. A token spent on an orphaned bucket would show up as > 50.
        assertEquals(50, allowed);
    }
}
