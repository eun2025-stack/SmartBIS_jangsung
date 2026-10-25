package com.smartbis.backend.tago;

import java.time.Instant;
import java.time.LocalDate;

interface GuardUsageStore {
    int read(String api, LocalDate day);
    /** Atomically count an attempted HTTP call, including failures; -1 means quota denied. */
    int reserve(String api, LocalDate day, int limit, Instant now);
}
