package com.anarvia.interview.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Single-threaded behaviour, driven by a {@link ManualClock} so every number is exact. */
class RateLimiterServiceTest {

    private ManualClock clock;
    private RateLimiterService service;

    @BeforeEach
    void setUp() {
        clock = new ManualClock();
        service = new RateLimiterService(clock);
    }

    /** Spends {@code n} requests and returns how many were allowed. */
    private int spend(String clientId, int n) {
        int allowed = 0;
        for (int i = 0; i < n; i++) {
            if (service.tryAcquire(clientId).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    /** Spends requests until the first rejection; returns how many were allowed. */
    private int drain(String clientId) {
        int allowed = 0;
        while (service.tryAcquire(clientId).allowed()) {
            allowed++;
        }
        return allowed;
    }

    @Nested
    @DisplayName("1. regular traffic: allow and reject")
    class RegularTraffic {

        @Test
        @DisplayName("allows up to the limit, then rejects with a retry-after")
        void allowsThenRejects() {
            for (int i = 0; i < 50; i++) {
                RateLimitResult r = service.tryAcquire("acme");
                assertTrue(r.allowed(), "request " + (i + 1) + " should be allowed");
                assertEquals(49 - i, r.remaining());
                assertEquals(0, r.retryAfterMillis());
            }
            RateLimitResult rejected = service.tryAcquire("acme");
            assertFalse(rejected.allowed());
            assertEquals(0, rejected.remaining());
            assertTrue(rejected.retryAfterMillis() > 0);
        }

        @Test
        @DisplayName("unassigned client gets the default tier, SILVER (50 rps)")
        void unknownClientGetsDefaultTier() {
            RateLimitResult r = service.tryAcquire("stranger");
            assertEquals(Tier.SILVER, r.tier());
            assertEquals(50, r.limit());
            assertEquals(Tier.SILVER, service.tierOf("stranger"));
        }

        @Test
        @DisplayName("assigned client is limited by its own tier")
        void assignedTierApplies() {
            service.assignClientTier("acme", Tier.GOLD);
            assertEquals(100, drain("acme"));
            assertEquals(Tier.GOLD, service.tryAcquire("acme").tier());
        }

        @Test
        @DisplayName("exactly one token-interval later (10ms at 100 rps) one request is allowed — no 0.9999 float miss")
        void refillsExactlyOneTokenAfterOneInterval() {
            service.assignClientTier("acme", Tier.GOLD);
            drain("acme");
            clock.advanceMillis(10);
            assertTrue(service.tryAcquire("acme").allowed());
            assertFalse(service.tryAcquire("acme").allowed());
        }

        @Test
        @DisplayName("fractional refill carries forward: 5ms + 5ms at 100 rps = one token")
        void fractionalTokensAccumulate() {
            service.assignClientTier("acme", Tier.GOLD);
            drain("acme");
            clock.advanceMillis(5);
            assertFalse(service.tryAcquire("acme").allowed(), "half a token is not enough");
            clock.advanceMillis(5);
            assertTrue(service.tryAcquire("acme").allowed(), "the two halves add up");
        }

        @Test
        @DisplayName("waiting the reported retry-after is enough to succeed")
        void honouringRetryAfterSucceeds() {
            drain("acme");
            clock.advanceMillis(1); // leave a fractional token so retry-after is not a round number
            RateLimitResult rejected = service.tryAcquire("acme");
            assertFalse(rejected.allowed());
            assertTrue(rejected.retryAfterMillis() <= 20, "at most one SILVER token-interval");

            clock.advanceMillis(rejected.retryAfterMillis());
            assertTrue(service.tryAcquire("acme").allowed());
        }

        @Test
        @DisplayName("clients are isolated: one exhausting its bucket doesn't affect another")
        void clientsAreIndependent() {
            drain("noisy");
            assertFalse(service.tryAcquire("noisy").allowed());
            RateLimitResult quiet = service.tryAcquire("quiet");
            assertTrue(quiet.allowed());
            assertEquals(49, quiet.remaining(), "quiet client has its own full bucket");
        }

        @Test
        @DisplayName("stats count allowed and rejected decisions")
        void statsCountDecisions() {
            spend("acme", 60);
            RateLimiterService.Stats stats = service.stats();
            assertEquals(50, stats.allowed());
            assertEquals(10, stats.rejected());
            assertEquals(1, stats.activeBuckets());
        }
    }

    @Nested
    @DisplayName("2. burst traffic")
    class BurstTraffic {

        @ParameterizedTest(name = "{0}: a full bucket absorbs exactly one second of requests at once")
        @EnumSource(Tier.class)
        void burstCappedAtCapacity(Tier tier) {
            service.assignClientTier("acme", tier);
            assertEquals(tier.defaultRps(), spend("acme", tier.defaultRps() * 3));
        }

        @Test
        @DisplayName("after the burst, sustained traffic is held to the steady rate")
        void sustainedRateAfterBurst() {
            // SILVER: 50 burst, then 50/s. Hammer it every 1ms for 5 simulated seconds.
            int allowed = drain("acme");
            for (int ms = 0; ms < 5_000; ms++) {
                clock.advanceMillis(1);
                allowed += spend("acme", 3);
            }
            int bound = 50 + 50 * 5; // capacity + rps × T
            assertTrue(allowed <= bound, "never more than capacity + rps×T; got " + allowed);
            // The last few ms of accrual may still be a fraction of a token when the loop ends.
            assertTrue(allowed >= bound - 1, "should use the whole budget; got " + allowed);
        }

        @Test
        @DisplayName("idling for 30 days still only buys one second of burst, without overflow")
        void longIdleCapsAtCapacity() {
            service.assignClientTier("acme", Tier.PLATINUM);
            drain("acme");
            clock.advance(Duration.ofDays(30));
            assertEquals(1000, spend("acme", 5_000));
        }

        @Test
        @DisplayName("a frozen clock means no refill at all between requests")
        void noTimeNoRefill() {
            drain("acme");
            assertEquals(0, spend("acme", 100));
        }
    }

    @Nested
    @DisplayName("4. tier changes: upgrade and downgrade")
    class TierChanges {

        @Test
        @DisplayName("downgrade with a full bucket clamps to the new, smaller capacity")
        void downgradeFullBucketClamps() {
            service.assignClientTier("acme", Tier.GOLD);
            service.tryAcquire("acme"); // create the GOLD bucket: 99 left
            service.assignClientTier("acme", Tier.SILVER);
            assertEquals(50, drain("acme"), "no 100-request burst under SILVER");
        }

        @Test
        @DisplayName("downgrade below the new capacity keeps the tokens the client already had")
        void downgradeKeepsTokensBelowNewCapacity() {
            service.assignClientTier("acme", Tier.GOLD);
            spend("acme", 80); // 20 left
            service.assignClientTier("acme", Tier.SILVER);
            assertEquals(20, drain("acme"));

            clock.advanceMillis(20); // SILVER: one token per 20ms
            assertTrue(service.tryAcquire("acme").allowed());
            assertFalse(service.tryAcquire("acme").allowed());
        }

        @Test
        @DisplayName("upgrade keeps current tokens (no instant fill) but refills at the new rate up to the new capacity")
        void upgradeKeepsTokensAndUsesNewRate() {
            spend("acme", 40); // SILVER, 10 left
            service.assignClientTier("acme", Tier.PLATINUM);
            RateLimitResult first = service.tryAcquire("acme");
            assertEquals(Tier.PLATINUM, first.tier());
            assertEquals(1000, first.limit());
            assertEquals(9, first.remaining(), "upgrade does not refill the bucket to 1000");
            assertEquals(9, drain("acme"));

            clock.advanceMillis(1); // PLATINUM: one token per 1ms (SILVER would need 20ms)
            assertTrue(service.tryAcquire("acme").allowed());

            clock.advance(Duration.ofSeconds(2));
            assertEquals(1000, drain("acme"), "grows to the new, larger capacity");
        }

        @Test
        @DisplayName("tier limit change mid-idle: time before it at the old rate, after it at the new rate")
        void tierUpdateSplitsIdlePeriod() {
            service.assignClientTier("acme", Tier.GOLD);
            drain("acme");
            clock.advanceMillis(10);            // 10ms at old 100 rps = 1 token (not yet observed)
            service.updateTier(Tier.GOLD, 200);
            clock.advanceMillis(5);             // 5ms at new 200 rps = 1 token

            RateLimitResult r = service.tryAcquire("acme");
            assertTrue(r.allowed());
            assertEquals(200, r.limit());
            assertTrue(service.tryAcquire("acme").allowed(), "1 + 1 = 2 tokens");
            assertFalse(service.tryAcquire("acme").allowed());
        }

        @Test
        @DisplayName("upgrade mid-idle: SILVER until the move, PLATINUM after")
        void upgradeSplitsIdlePeriod() {
            drain("acme");
            clock.advanceMillis(10);            // 10ms at SILVER 50 rps = 0.5 token
            service.assignClientTier("acme", Tier.PLATINUM);
            clock.advanceMillis(1);             // 1ms at PLATINUM 1000 rps = 1 token
            // Old-rate-for-whole-gap would give 0.55 (rejected); new-rate-for-whole-gap would give 11.
            assertTrue(service.tryAcquire("acme").allowed());
            assertFalse(service.tryAcquire("acme").allowed(), "0.5 left");
        }

        @Test
        @DisplayName("downgrade mid-idle: PLATINUM until the move, SILVER after")
        void downgradeSplitsIdlePeriod() {
            service.assignClientTier("acme", Tier.PLATINUM);
            drain("acme");
            clock.advanceMillis(1);             // 1ms at PLATINUM = 1 token
            service.assignClientTier("acme", Tier.SILVER);
            clock.advanceMillis(10);            // 10ms at SILVER = 0.5 token
            assertTrue(service.tryAcquire("acme").allowed());
            assertFalse(service.tryAcquire("acme").allowed(), "0.5 left");
        }

        @Test
        @DisplayName("lowering a tier's limit at runtime applies to existing clients on their next request")
        void lowerTierLimitAtRuntime() {
            service.assignClientTier("acme", Tier.GOLD);
            service.tryAcquire("acme"); // existing GOLD bucket, 99 left
            service.updateTier(Tier.GOLD, 10);
            assertEquals(10, drain("acme"));
        }

        @Test
        @DisplayName("raising a tier's limit at runtime grows existing clients' capacity")
        void raiseTierLimitAtRuntime() {
            service.assignClientTier("acme", Tier.GOLD);
            drain("acme");
            service.updateTier(Tier.GOLD, 300);
            clock.advance(Duration.ofSeconds(5));
            assertEquals(300, drain("acme"));
        }

        @Test
        @DisplayName("updating one tier leaves other tiers' clients untouched")
        void tierUpdateIsScoped() {
            service.assignClientTier("gold-client", Tier.GOLD);
            service.updateTier(Tier.SILVER, 5);
            assertEquals(100, drain("gold-client"));
            assertEquals(5, drain("silver-client"));
        }

        @Test
        @DisplayName("new clients use the updated limit from their first request")
        void newClientsSeeUpdatedLimit() {
            service.updateTier(Tier.SILVER, 7);
            assertEquals(7, drain("brand-new"));
        }

        @Test
        @DisplayName("moving a client back and forth never mints tokens")
        void flipFloppingTiersNeverMintsTokens() {
            spend("acme", 45); // SILVER, 5 left
            for (int i = 0; i < 10; i++) {
                service.assignClientTier("acme", i % 2 == 0 ? Tier.PLATINUM : Tier.SILVER);
                service.tryAcquire("acme");
            }
            // 5 tokens, 10 attempts with no time passing: only 5 can have succeeded.
            assertEquals(0, drain("acme"));
            assertEquals(50, service.stats().allowed());
        }

        @Test
        @DisplayName("listTiers reflects runtime updates")
        void listReflectsUpdates() {
            service.updateTier(Tier.PLATINUM, 5000);
            assertEquals(new TierConfig(Tier.PLATINUM, 5000), service.listTiers().get(2));
            assertEquals(Tier.SILVER, service.listTiers().get(0).tier());
        }
    }

    @Nested
    @DisplayName("input validation")
    class Validation {

        @Test
        @DisplayName("null client id is rejected")
        void nullClientId() {
            assertThrows(InvalidRequestException.class, () -> service.tryAcquire(null));
        }

        @ParameterizedTest(name = "blank client id ''{0}'' is rejected")
        @ValueSource(strings = {"", " ", "\t\n"})
        void blankClientId(String id) {
            assertThrows(InvalidRequestException.class, () -> service.tryAcquire(id));
        }

        @Test
        @DisplayName("client id at the length limit is accepted; one over is rejected")
        void clientIdLength() {
            String atLimit = "x".repeat(RateLimiterService.MAX_CLIENT_ID_LENGTH);
            assertTrue(service.tryAcquire(atLimit).allowed());
            assertThrows(InvalidRequestException.class, () -> service.tryAcquire(atLimit + "x"));
        }

        @ParameterizedTest(name = "rps {0} is rejected")
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        void nonPositiveRps(int rps) {
            assertThrows(InvalidRequestException.class, () -> service.updateTier(Tier.GOLD, rps));
            assertEquals(100, service.listTiers().get(1).requestsPerSecond(), "failed update changes nothing");
        }

        @Test
        @DisplayName("null tier is rejected on update and assign")
        void nullTier() {
            assertThrows(InvalidRequestException.class, () -> service.updateTier(null, 10));
            assertThrows(InvalidRequestException.class, () -> service.assignClientTier("acme", null));
        }

        @Test
        @DisplayName("assigning a tier to a blank client id is rejected")
        void assignBlankClient() {
            assertThrows(InvalidRequestException.class, () -> service.assignClientTier(" ", Tier.GOLD));
        }

        @Test
        @DisplayName("Tier.parse is case-insensitive and rejects unknown / blank names")
        void tierParse() {
            assertEquals(Tier.GOLD, Tier.parse("gold"));
            assertEquals(Tier.PLATINUM, Tier.parse(" Platinum "));
            assertThrows(UnknownTierException.class, () -> Tier.parse("BRONZE"));
            assertThrows(InvalidRequestException.class, () -> Tier.parse(""));
            assertThrows(InvalidRequestException.class, () -> Tier.parse(null));
        }
    }

    @Nested
    @DisplayName("5. restart after a crash (decision: in-memory only, documented)")
    class Restart {

        @Test
        @DisplayName("a fresh instance starts every client with a full bucket")
        void restartStartsFull() {
            drain("acme");
            RateLimiterService restarted = new RateLimiterService(clock);
            RateLimitResult first = restarted.tryAcquire("acme");
            assertTrue(first.allowed());
            assertEquals(49, first.remaining(), "bucket came back full");
        }

        @Test
        @DisplayName("known limitation: tier assignments and runtime limits are not persisted")
        void restartLosesAssignments() {
            service.assignClientTier("acme", Tier.PLATINUM);
            service.updateTier(Tier.GOLD, 500);
            RateLimiterService restarted = new RateLimiterService(clock);
            assertEquals(Tier.SILVER, restarted.tierOf("acme"));
            assertEquals(100, restarted.listTiers().get(1).requestsPerSecond());
        }
    }
}
