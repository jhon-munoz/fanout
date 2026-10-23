package com.caseware.fanout.model;

import java.time.Instant;

public record Lease(EngagementRef engagement, String token, int attempt, Instant expiresAt) {
}
