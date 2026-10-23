package com.caseware.fanout.exception;

import java.time.Duration;

public class DownstreamException extends Exception {

    public enum Kind {
        THROTTLED,
        TRANSIENT,
        PERMANENT
    }

    private final Kind kind;
    private final Duration retryAfter;

    public DownstreamException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    public DownstreamException(Kind kind, String message, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
