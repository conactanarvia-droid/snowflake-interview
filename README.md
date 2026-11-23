# Rate limiting service

An HTTP rate-limiting service with per-client tiers whose limits, and each
client's tier, can be changed at runtime without a restart.

| Tier | Limit |
|---|---|
| `SILVER` (default for unknown clients) | 50 requests/sec |
| `GOLD` | 100 requests/sec |
| `PLATINUM` | 1000 requests/sec |

Token bucket, capacity = one second of requests, **lazily refilled**: there
is no refill job; each request computes `elapsed × rate` itself. Java 21, the
JDK's built-in `HttpServer`, and JUnit 5. No other dependencies.

## Run it

```bash
mvn -q compile
java -cp target/classes com.anarvia.interview.ratelimiter.RateLimiterApplication   # port 8080

curl -i -X POST -H 'X-Client-Id: acme' localhost:8080/v1/check        # 200 or 429 + Retry-After
curl -X PUT 'localhost:8080/v1/admin/clients/acme/tier?tier=PLATINUM'  # move a client
curl -X PUT 'localhost:8080/v1/admin/tiers/GOLD?rps=200'               # change a tier's limit
curl localhost:8080/v1/admin/tiers ; curl localhost:8080/v1/admin/stats
```

## Live visual demo

```bash
mvn -q compile
java -cp target/classes com.anarvia.interview.ratelimiter.Demo
open http://localhost:8090
```

Starts the service on `:8080`, a traffic generator that sends real HTTP to
it, and a dashboard on `:8090` with a live allowed/rejected chart per client
against the tier's limit line. Six guided scenarios: under the limit,
overload, burst, live upgrade, live downgrade, and raising a tier's limit.
Each admin action is logged with its `curl` equivalent.

## Tests

```bash
mvn test        # 97 tests
```

Driven by a manual clock, so every refill is exact and nothing sleeps.
Covers allow/reject, bursts, 64-thread concurrency (exactly the limit is
allowed, never more), upgrades and downgrades including mid-idle changes,
eviction races, restart behaviour, and the HTTP API end to end.

## HTTP API

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/check` | Header `X-Client-Id`. 200 allowed, 429 + `Retry-After` over limit, 400 if missing |
| GET | `/v1/admin/tiers` | Current limits |
| PUT | `/v1/admin/tiers/{tier}?rps=N` | Change a tier's limit at runtime |
| GET / PUT | `/v1/admin/clients/{clientId}/tier?tier=T` | Read / move a client's tier |
| GET | `/v1/admin/stats` | Allowed / rejected counters, active buckets |

Responses carry `X-RateLimit-Limit` and `X-RateLimit-Remaining`.

## Design: what it answers, and where

| Question | Where | How |
|---|---|---|
| How does refill work without a background job? | `TokenBucket` | On each request: `tokens = min(capacity, tokens + elapsed × rps)`. O(1), exact to the nanosecond, zero work for idle clients. Computed as `elapsed * rps / 1e9` so one token-interval yields exactly `1.0`, not `0.9999…`. |
| How do limit changes apply without a restart? | `TierRegistry`, `ClientTierRegistry`, `TokenBucket` | An update swaps one immutable map entry and records when. Each bucket notices on its next request and splits the idle time at that moment: old rate before, new rate after, clamped to the new capacity. Downgrades can't keep a bigger burst; upgrades aren't refilled for free. |
| How is a token never spent twice? | `TokenBucket`, `BucketStore` | Per-bucket `ReentrantLock`; `computeIfAbsent` guarantees one bucket per client. Clock **and** config are read inside the lock so a bucket never sees time or config go backwards. |
| How does memory stay bounded? | `IdleBucketEvictor` | Memory-only sweep of buckets idle ≥ 60s (must be ≥ the 1s full-refill time, when a bucket equals a fresh one). An `evicted` flag set under the lock stops a racing request from spending a token on an orphaned bucket. |

`RateLimiterService` and `RateLimitServer` are the public entry points;
everything else in the package is package-private on purpose.

## Known limitations (deliberate, for this version)

- **State is in memory only.** On a crash or restart every bucket comes back
  full (at most one second of extra burst per client), and **client tier
  assignments and runtime limit changes are lost**: clients revert to
  `SILVER`, tiers to their defaults. Production would persist assignments
  in a database and reload on start.
- **Fail-open vs fail-closed** if this service is unreachable is the
  caller's decision, not something the limiter can make for it.
- **Single node.** Multiple instances would each enforce the full limit;
  sharing limits needs a shared store (e.g. Redis with an atomic script).
- **Admin API is unauthenticated**, so the server binds to loopback only.

## How it was built

`docs/ratelimiter-session-history.md` (and the PDF beside it) records the
step-by-step design session: requirement gaps, architecture, class design,
implementation, edge cases, tests, and demo, with every prompt and answer.
