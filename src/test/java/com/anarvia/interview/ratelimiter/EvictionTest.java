package com.anarvia.interview.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Idle-bucket eviction: frees memory, never changes what a client is allowed. */
class EvictionTest {

    private final ManualClock clock = new ManualClock();
    private final RateLimiterService service = new RateLimiterService(clock);

    @Test
    @DisplayName("only buckets idle for at least the threshold are evicted")
    void evictsOnlyIdleBuckets() {
        service.tryAcquire("idle");
        clock.advance(Duration.ofSeconds(90));
        service.tryAcquire("active");

        assertEquals(1, service.evictIdleBuckets(Duration.ofSeconds(60)));
        assertEquals(1, service.stats().activeBuckets());
    }

    @Test
    @DisplayName("an evicted client comes back with a full bucket, same as if it had stayed")
    void evictedClientStartsFull() {
        for (int i = 0; i < 50; i++) {
            service.tryAcquire("acme");
        }
        clock.advance(Duration.ofSeconds(5)); // would have refilled fully anyway
        assertEquals(1, service.evictIdleBuckets(Duration.ofSeconds(1)));
        assertEquals(49, service.tryAcquire("acme").remaining());
    }

    @Test
    @DisplayName("eviction keeps the client's tier assignment")
    void evictionKeepsTier() {
        service.assignClientTier("acme", Tier.PLATINUM);
        service.tryAcquire("acme");
        clock.advance(Duration.ofSeconds(5));
        service.evictIdleBuckets(Duration.ofSeconds(1));
        assertEquals(Tier.PLATINUM, service.tryAcquire("acme").tier());
    }

    @Test
    @DisplayName("an evicted bucket refuses to serve and tells the caller to re-fetch")
    void evictedBucketReturnsEvicted() {
        TokenBucket bucket = new TokenBucket(new TierConfig(Tier.SILVER, 50), clock.nanoTime());
        clock.advance(Duration.ofSeconds(2));
        assertTrue(bucket.tryMarkEvicted(clock.nanoTime(), Duration.ofSeconds(1).toNanos()));
        assertSame(TokenBucket.Outcome.EVICTED,
            bucket.tryAcquire(() -> new EffectiveConfig(new TierConfig(Tier.SILVER, 50), 0), clock));
    }

    @Test
    @DisplayName("a recently used bucket is not marked evicted")
    void activeBucketNotMarked() {
        TokenBucket bucket = new TokenBucket(new TierConfig(Tier.SILVER, 50), clock.nanoTime());
        clock.advance(Duration.ofMillis(500));
        assertEquals(false, bucket.tryMarkEvicted(clock.nanoTime(), Duration.ofSeconds(1).toNanos()));
    }

    @Test
    @DisplayName("evictor rejects an idle threshold shorter than the full refill time")
    void rejectsUnsafeThreshold() {
        assertThrows(IllegalArgumentException.class,
            () -> new IdleBucketEvictor(service, Duration.ofMillis(999), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
            () -> new IdleBucketEvictor(service, Duration.ofSeconds(1), Duration.ZERO));
    }

    @Test
    @DisplayName("5. a failing sweep is contained, and the next sweep still runs")
    void sweepSurvivesFailure() {
        AtomicBoolean failNext = new AtomicBoolean(false);
        Clock flaky = () -> {
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("simulated failure");
            }
            return clock.nanoTime();
        };
        RateLimiterService flakyService = new RateLimiterService(flaky);
        flakyService.tryAcquire("acme");
        clock.advance(Duration.ofSeconds(5));

        try (IdleBucketEvictor evictor = new IdleBucketEvictor(flakyService, Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            failNext.set(true);
            assertDoesNotThrow(evictor::sweep, "failure must not propagate and cancel the schedule");
            assertEquals(1, flakyService.stats().activeBuckets(), "failed sweep evicted nothing");

            evictor.sweep();
            assertEquals(0, flakyService.stats().activeBuckets(), "next sweep works");
        }
    }

    @Test
    @DisplayName("scheduled evictor actually runs in the background")
    void scheduledEvictorRuns() throws InterruptedException {
        service.tryAcquire("acme");
        clock.advance(Duration.ofSeconds(5));
        try (IdleBucketEvictor evictor = new IdleBucketEvictor(service, Duration.ofSeconds(1), Duration.ofMillis(20))) {
            evictor.start();
            long deadline = System.currentTimeMillis() + 5_000;
            while (service.stats().activeBuckets() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
        }
        assertEquals(0, service.stats().activeBuckets());
    }
}
