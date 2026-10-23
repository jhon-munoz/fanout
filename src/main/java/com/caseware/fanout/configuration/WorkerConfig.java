package com.caseware.fanout.configuration;

import java.time.Duration;

public record WorkerConfig(Duration leaseTtl, Duration pollInterval, Duration shutdownGrace) {
}
