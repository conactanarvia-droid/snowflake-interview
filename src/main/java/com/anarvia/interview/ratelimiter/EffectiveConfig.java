package com.anarvia.interview.ratelimiter;

/**
 * The config a client should be on right now, plus the moment it took effect.
 * {@code sinceNanos} is the later of "this tier's limit was last set" and
 * "this client was last assigned a tier", so a bucket can split an idle
 * period exactly: time before the change at the old rate, time after at the new.
 */
record EffectiveConfig(TierConfig config, long sinceNanos) {
}
