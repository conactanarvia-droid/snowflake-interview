package com.anarvia.interview.ratelimiter;

/** Caller sent bad input (blank client id, non-positive rps, …). Maps to HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
