package com.anarvia.interview.ratelimiter;

/**
 * Monotonic time source in nanoseconds. Injected so tests can advance time
 * exactly instead of sleeping.
 *
 * <p>Monotonic (not wall-clock) on purpose: a wall-clock jump from NTP or a
 * manual change would otherwise make elapsed time negative or huge, and the
 * bucket would refill wrongly.
 */
@FunctionalInterface
interface Clock {

    Clock SYSTEM = System::nanoTime;

    long nanoTime();
}
