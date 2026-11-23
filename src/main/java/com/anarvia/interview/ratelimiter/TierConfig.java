package com.anarvia.interview.ratelimiter;

import java.util.Objects;

/**
 * Immutable snapshot of a tier's limit. A runtime update never mutates one of
 * these; it publishes a new instance, so a thread holding a config always
 * sees a consistent value.
 *
 * <p>Burst capacity equals {@code requestsPerSecond}: a full bucket allows
 * one second's worth of requests at once, then refills at the steady rate.
 */
public record TierConfig(Tier tier, int requestsPerSecond) {

    public TierConfig {
        Objects.requireNonNull(tier, "tier");
        if (requestsPerSecond <= 0) {
            throw new InvalidRequestException("requestsPerSecond must be > 0, got " + requestsPerSecond);
        }
    }

    public int capacity() {
        return requestsPerSecond;
    }
}
