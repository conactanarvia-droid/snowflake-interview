package com.anarvia.interview.ratelimiter;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Current limit per tier. An update is a single atomic map write of a new
 * immutable entry, O(1) and independent of how many clients or buckets
 * exist. Buckets pick the change up lazily on their next request.
 *
 * <p>Each entry records when it took effect, so buckets can credit idle time
 * before the change at the old rate and after it at the new rate.
 */
final class TierRegistry {

    record Entry(TierConfig config, long effectiveAtNanos) {
    }

    private final ConcurrentHashMap<Tier, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    TierRegistry(Clock clock) {
        this.clock = clock;
        long now = clock.nanoTime();
        for (Tier tier : Tier.values()) {
            entries.put(tier, new Entry(new TierConfig(tier, tier.defaultRps()), now));
        }
    }

    Entry get(Tier tier) {
        return entries.get(Objects.requireNonNull(tier, "tier"));
    }

    TierConfig update(Tier tier, int requestsPerSecond) {
        TierConfig updated = new TierConfig(tier, requestsPerSecond); // validates before anything changes
        entries.put(tier, new Entry(updated, clock.nanoTime()));
        return updated;
    }

    /** All tiers, in enum order. */
    List<TierConfig> all() {
        return Arrays.stream(Tier.values()).map(t -> entries.get(t).config()).toList();
    }
}
