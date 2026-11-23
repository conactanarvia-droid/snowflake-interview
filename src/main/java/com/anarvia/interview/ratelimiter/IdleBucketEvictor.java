package com.anarvia.interview.ratelimiter;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background memory cleanup only. It never adds or removes tokens; refill
 * stays entirely lazy. Without it, every client id ever seen keeps a bucket
 * until restart.
 *
 * <p>The idle threshold must be at least the full-refill time (1s, since
 * capacity = one second of requests). After that a bucket is full and
 * identical to a fresh one, so dropping it changes nothing for the client.
 */
final class IdleBucketEvictor implements AutoCloseable {

    static final Duration MIN_IDLE_THRESHOLD = Duration.ofSeconds(1);

    private final RateLimiterService service;
    private final Duration idleThreshold;
    private final Duration period;
    private final ScheduledExecutorService scheduler;

    IdleBucketEvictor(RateLimiterService service, Duration idleThreshold, Duration period) {
        if (idleThreshold.compareTo(MIN_IDLE_THRESHOLD) < 0) {
            throw new IllegalArgumentException("idleThreshold must be >= " + MIN_IDLE_THRESHOLD
                + " (full refill time) or eviction could hand a client a fresh full bucket early");
        }
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive");
        }
        this.service = service;
        this.idleThreshold = idleThreshold;
        this.period = period;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "idle-bucket-evictor");
            t.setDaemon(true);
            return t;
        });
    }

    void start() {
        long periodMs = period.toMillis();
        scheduler.scheduleWithFixedDelay(this::sweep, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /** One sweep. Package-private so tests can drive it directly. */
    void sweep() {
        try {
            service.evictIdleBuckets(idleThreshold);
        } catch (RuntimeException e) {
            // An uncaught exception would silently cancel all future runs.
            System.err.println("idle bucket sweep failed: " + e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
