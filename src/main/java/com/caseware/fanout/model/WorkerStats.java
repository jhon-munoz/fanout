package com.caseware.fanout.model;

public record WorkerStats(long succeeded, long retried, long throttled, long dead, long fenced, long internalErrors) {
}
