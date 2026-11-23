package com.anarvia.interview.ratelimiter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * The rate limiter's single public entry point: per-client checks plus the
 * runtime admin operations (change a tier's limit, move a client to a tier).
 *
 * <p>Neither admin operation touches any bucket. Each just swaps one map
 * entry; every bucket notices on its own next request (see {@link TokenBucket}).
 * So an update is O(1) no matter how many clients exist, and needs no
 * restart, broadcast, or background job.
 */
public final class RateLimiterService {

    /** Bounds memory per client: ids are map keys held for the life of the bucket. */
    static final int MAX_CLIENT_ID_LENGTH = 256;

    private final TierRegistry tiers;
    private final ClientTierRegistry clients;
    private final BucketStore buckets = new BucketStore();
    private final Clock clock;

    private final LongAdder allowedCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();

    public RateLimiterService() {
        this(Clock.SYSTEM);
    }

    RateLimiterService(Clock clock) {
        this.clock = clock;
        this.tiers = new TierRegistry(clock);
        this.clients = new ClientTierRegistry(clock);
    }

    /** Spends one token for {@code clientId}, or rejects if its bucket is empty. */
    public RateLimitResult tryAcquire(String clientId) {
        String id = validateClientId(clientId);
        while (true) {
            TierConfig initial = currentConfigFor(id).config();
            TokenBucket bucket = buckets.getOrCreate(id, initial, clock.nanoTime());
            TokenBucket.Outcome outcome = bucket.tryAcquire(() -> currentConfigFor(id), clock);
            if (outcome.evicted()) {
                // Lost a race with the idle evictor; drop the stale bucket and retry with a fresh one.
                buckets.discard(id, bucket);
                continue;
            }
            return toResult(id, outcome);
        }
    }

    public TierConfig updateTier(Tier tier, int requestsPerSecond) {
        if (tier == null) {
            throw new InvalidRequestException("tier is required");
        }
        return tiers.update(tier, requestsPerSecond);
    }

    public void assignClientTier(String clientId, Tier tier) {
        String id = validateClientId(clientId);
        if (tier == null) {
            throw new InvalidRequestException("tier is required");
        }
        clients.assign(id, tier);
    }

    public Tier tierOf(String clientId) {
        return clients.tierOf(validateClientId(clientId));
    }

    public List<TierConfig> listTiers() {
        return tiers.all();
    }

    /** Frees memory for clients idle at least {@code idleThreshold}. Never changes any client's tokens. */
    public int evictIdleBuckets(Duration idleThreshold) {
        return buckets.evictIdle(clock.nanoTime(), idleThreshold.toNanos());
    }

    public Stats stats() {
        return new Stats(allowedCount.sum(), rejectedCount.sum(), buckets.size());
    }

    public record Stats(long allowed, long rejected, int activeBuckets) {
    }

    /** The client's current config, effective since the later of its tier's last update and its last reassignment. */
    private EffectiveConfig currentConfigFor(String clientId) {
        ClientTierRegistry.Assignment assignment = clients.assignmentOf(clientId);
        TierRegistry.Entry entry = tiers.get(assignment.tier());
        return new EffectiveConfig(entry.config(), Math.max(entry.effectiveAtNanos(), assignment.assignedAtNanos()));
    }

    private RateLimitResult toResult(String clientId, TokenBucket.Outcome outcome) {
        (outcome.allowed() ? allowedCount : rejectedCount).increment();
        TierConfig config = outcome.config();
        long retryAfterMillis = outcome.allowed() ? 0 : Math.ceilDiv(outcome.retryAfterNanos(), 1_000_000L);
        return new RateLimitResult(
            outcome.allowed(),
            clientId,
            config.tier(),
            config.requestsPerSecond(),
            (long) Math.floor(outcome.remainingTokens()),
            retryAfterMillis);
    }

    private static String validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new InvalidRequestException("client id is required");
        }
        if (clientId.length() > MAX_CLIENT_ID_LENGTH) {
            throw new InvalidRequestException("client id longer than " + MAX_CLIENT_ID_LENGTH + " characters");
        }
        return clientId;
    }
}
