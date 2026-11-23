package com.anarvia.interview.ratelimiter;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One client's token bucket with lazy refill. There is no background job:
 * each request first works out how many tokens accrued since the last touch
 * ({@code elapsed × rate}, capped at capacity), then tries to spend one.
 * O(1) per request, and no work at all for idle clients.
 *
 * <p><b>Runtime limit changes</b> are also lazy, but exact. Under the lock,
 * if the client's config changed since the bucket last ran, it:
 * <ol>
 *   <li>refills at the <i>old</i> rate and capacity up to the moment of the
 *       change, so tokens earned under the old limit are kept, not invented;</li>
 *   <li>switches config and clamps tokens to the new capacity (no free burst
 *       on a downgrade, no reset to full on an upgrade);</li>
 *   <li>refills at the <i>new</i> rate and capacity from the change to now.</li>
 * </ol>
 * Without the split, an idle period spanning an upgrade would be credited
 * entirely under the old, smaller capacity: a client raised from 100 to 300
 * rps and idle for 5s would come back with only 100 tokens.
 *
 * <p><b>Why the clock and the config are both read inside the lock:</b> if
 * they were read by the caller before blocking on the lock, a thread could
 * arrive holding an older timestamp or an older config than the thread that
 * went before it. An older timestamp means negative elapsed time; an older
 * config would flip the bucket back to a superseded limit. Reading both
 * under the lock makes each bucket's history strictly forward-moving.
 *
 * <p><b>Precision:</b> tokens are a {@code double} so fractional refill
 * (e.g. 0.5 token after 5ms at 100 rps) is carried forward, not truncated.
 * Refill is computed as {@code elapsedNanos * rps / 1e9} rather than
 * {@code elapsedNanos * (rps / 1e9)}: the per-nano rate is not exactly
 * representable in binary, so the latter lands at 0.9999… instead of 1.0
 * after exactly one token-interval and wrongly rejects.
 */
final class TokenBucket {

    static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /** Result of an acquire attempt, translated into a public {@link RateLimitResult} by the service. */
    record Outcome(boolean evicted, boolean allowed, double remainingTokens, long retryAfterNanos, TierConfig config) {
        static final Outcome EVICTED = new Outcome(true, false, 0, 0, null);
    }

    private final ReentrantLock lock = new ReentrantLock();

    // All guarded by lock.
    private TierConfig appliedConfig;
    private double tokens;
    private long lastRefillNanos;
    private boolean evicted;

    /** A new bucket starts full: a first-time client may burst immediately. */
    TokenBucket(TierConfig initialConfig, long nowNanos) {
        this.appliedConfig = initialConfig;
        this.tokens = initialConfig.capacity();
        this.lastRefillNanos = nowNanos;
    }

    Outcome tryAcquire(Supplier<EffectiveConfig> currentConfig, Clock clock) {
        lock.lock();
        try {
            if (evicted) {
                // The evictor removed this bucket from the store after the caller looked
                // it up. Spending a token here would be lost; tell the caller to re-fetch.
                return Outcome.EVICTED;
            }
            long now = clock.nanoTime();
            EffectiveConfig effective = currentConfig.get();
            TierConfig current = effective.config();

            if (!current.equals(appliedConfig)) {
                // Clamp the switch point into [lastRefill, now]: a change older than our last
                // refill can't be applied retroactively, and one stamped a hair after our clock
                // read (admin thread raced us) is treated as happening now.
                long switchAt = Math.min(now, Math.max(lastRefillNanos, effective.sinceNanos()));
                refill(switchAt);
                appliedConfig = current;
                tokens = Math.min(tokens, current.capacity());
            }
            refill(now);

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Outcome(false, true, tokens, 0, current);
            }
            long retryAfterNanos = (long) Math.ceil((1.0 - tokens) * NANOS_PER_SECOND / current.requestsPerSecond());
            return new Outcome(false, false, tokens, retryAfterNanos, current);
        } finally {
            lock.unlock();
        }
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed <= 0) {
            return; // never move lastRefill backwards or refill a negative amount
        }
        double accrued = (double) elapsed * appliedConfig.requestsPerSecond() / NANOS_PER_SECOND;
        tokens = Math.min(appliedConfig.capacity(), tokens + accrued);
        lastRefillNanos = nowNanos;
    }

    /**
     * Marks the bucket evicted if it has been idle for at least {@code idleNanos}.
     * Uses tryLock: a bucket whose lock is held is in use right now, so it is
     * not idle and the evictor skips it instead of waiting.
     *
     * <p>Safe as long as {@code idleNanos} is at least the full-refill time:
     * by then the bucket is full, identical to the fresh bucket that replaces it.
     */
    boolean tryMarkEvicted(long nowNanos, long idleNanos) {
        if (!lock.tryLock()) {
            return false;
        }
        try {
            if (!evicted && nowNanos - lastRefillNanos >= idleNanos) {
                evicted = true;
            }
            return evicted;
        } finally {
            lock.unlock();
        }
    }
}
