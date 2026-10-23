package com.caseware.fanout.model;

public record StoreStats(int pending, int leased, int done, int dead) {
}
