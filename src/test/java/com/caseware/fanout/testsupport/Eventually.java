package com.caseware.fanout.testsupport;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public final class Eventually {

    private Eventually() {
    }

    public static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(5);
        }
    }
}
