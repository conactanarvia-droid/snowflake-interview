package com.anarvia.interview.ratelimiter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Test clock: time moves only when a test moves it, so every refill is exact and nothing sleeps. */
final class ManualClock implements Clock {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    @Override
    public long nanoTime() {
        return nanos.get();
    }

    void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    void advanceMillis(long ms) {
        advance(Duration.ofMillis(ms));
    }
}
