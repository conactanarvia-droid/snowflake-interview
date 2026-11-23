package com.anarvia.interview.ratelimiter;

import java.util.Locale;

/**
 * The fixed set of rate-limit tiers. The enum is the tier's identity and its
 * starting limit only.
 *
 * <p>The live, runtime-updatable limit is deliberately NOT a mutable field
 * here: an enum constant is a JVM-wide singleton, so mutating it would be
 * global state shared across every limiter instance and every test. Live
 * limits live in {@link TierRegistry} as immutable {@link TierConfig}s.
 */
public enum Tier {
    SILVER(50),
    GOLD(100),
    PLATINUM(1000);

    private final int defaultRps;

    Tier(int defaultRps) {
        this.defaultRps = defaultRps;
    }

    public int defaultRps() {
        return defaultRps;
    }

    /** Case-insensitive lookup for values arriving over HTTP. */
    public static Tier parse(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidRequestException("tier is required");
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnknownTierException(name);
        }
    }
}
