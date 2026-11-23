package com.anarvia.interview.ratelimiter;

/** Tier name does not match any {@link Tier}. Maps to HTTP 404. */
public class UnknownTierException extends RuntimeException {

    public UnknownTierException(String name) {
        super("unknown tier '" + name + "'");
    }
}
