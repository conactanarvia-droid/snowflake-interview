package com.anarvia.interview.ratelimiter;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory client → tier mapping. Clients never assigned a tier get
 * {@link #DEFAULT_TIER}, the lowest one, so an unknown caller is limited
 * rather than rejected or left unlimited.
 *
 * <p>Each assignment records when it was made, so a bucket can switch rate
 * at exactly that moment rather than at its next request.
 */
final class ClientTierRegistry {

    static final Tier DEFAULT_TIER = Tier.SILVER;

    record Assignment(Tier tier, long assignedAtNanos) {
    }

    /** Unassigned clients have "always" been on the default tier. */
    private static final Assignment DEFAULT_ASSIGNMENT = new Assignment(DEFAULT_TIER, Long.MIN_VALUE);

    private final ConcurrentHashMap<String, Assignment> assignments = new ConcurrentHashMap<>();
    private final Clock clock;

    ClientTierRegistry(Clock clock) {
        this.clock = clock;
    }

    Assignment assignmentOf(String clientId) {
        return assignments.getOrDefault(clientId, DEFAULT_ASSIGNMENT);
    }

    Tier tierOf(String clientId) {
        return assignmentOf(clientId).tier();
    }

    void assign(String clientId, Tier tier) {
        assignments.put(clientId, new Assignment(Objects.requireNonNull(tier, "tier"), clock.nanoTime()));
    }
}
