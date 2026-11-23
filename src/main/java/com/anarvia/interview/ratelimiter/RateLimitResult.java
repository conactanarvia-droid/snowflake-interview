package com.anarvia.interview.ratelimiter;

/**
 * Outcome of one rate-limit check.
 *
 * @param limit            the tier's requests per second at the moment of the check
 * @param remaining        whole tokens left after this request
 * @param retryAfterMillis 0 when allowed; otherwise how long until one token is available
 */
public record RateLimitResult(
    boolean allowed,
    String clientId,
    Tier tier,
    int limit,
    long remaining,
    long retryAfterMillis) {
}
