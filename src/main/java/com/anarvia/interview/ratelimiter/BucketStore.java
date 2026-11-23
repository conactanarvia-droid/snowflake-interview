package com.anarvia.interview.ratelimiter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * clientId → bucket. {@code computeIfAbsent} guarantees exactly one bucket
 * per client even when its first requests arrive concurrently; two buckets
 * for one client would double its effective limit.
 */
final class BucketStore {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    TokenBucket getOrCreate(String clientId, TierConfig initialConfig, long nowNanos) {
        return buckets.computeIfAbsent(clientId, id -> new TokenBucket(initialConfig, nowNanos));
    }

    /** Removes {@code bucket} only if it is still the one mapped, so a newer replacement is never dropped. */
    void discard(String clientId, TokenBucket bucket) {
        buckets.remove(clientId, bucket);
    }

    /** Removes every bucket idle for at least {@code idleNanos}. Returns how many were removed. */
    int evictIdle(long nowNanos, long idleNanos) {
        int removed = 0;
        for (Map.Entry<String, TokenBucket> entry : buckets.entrySet()) {
            TokenBucket bucket = entry.getValue();
            if (bucket.tryMarkEvicted(nowNanos, idleNanos) && buckets.remove(entry.getKey(), bucket)) {
                removed++;
            }
        }
        return removed;
    }

    int size() {
        return buckets.size();
    }
}
