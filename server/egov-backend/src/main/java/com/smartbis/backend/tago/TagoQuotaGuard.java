package com.smartbis.backend.tago;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Location API only. Arrival budgets and counters cannot enter this guard. */
final class TagoQuotaGuard {
    static final String API_NAME = "TAGO_LOCATION";
    enum Decision { ALLOWED, DISABLED, TOO_SOON, DAILY_LIMIT }
    private static final Logger log = LoggerFactory.getLogger(TagoQuotaGuard.class);
    private final LocationCollectionProperties settings;
    private final GuardUsageStore store;
    private final Clock clock;
    private LocalDate day;
    private Instant lastAllowed;
    private int callCount;
    private boolean blockedLogged;

    TagoQuotaGuard(LocationCollectionProperties settings, GuardUsageStore store, Clock clock) {
        this.settings = settings; this.store = store; this.clock = clock;
    }
    synchronized void initialize() {
        if (settings.isEnabled()) loadToday();
    }
    private void loadToday() {
        day = LocalDate.now(clock.withZone(ZoneId.of(settings.getZone())));
        callCount = store.read(API_NAME, day);
        blockedLogged = false;
        log.info("Location guard resumed: date={} callCount={} dailyLimit={}", day, callCount, settings.getDailyLimit());
    }
    synchronized boolean ready() {
        if (!settings.isEnabled()) return false;
        settings.validate();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(settings.getZone())));
        if (!today.equals(day)) loadToday();
        if (callCount >= settings.getDailyLimit()) {
            if (!blockedLogged) {
                log.warn("Location API daily budget reached; stopped until next KST day: date={} callCount={} limit={}", day, callCount, settings.getDailyLimit());
                blockedLogged = true;
            }
            return false;
        }
        return lastAllowed == null || !clock.instant().isBefore(lastAllowed.plusMillis(settings.getMinGapMs()));
    }
    synchronized Decision acquire() {
        if (!settings.isEnabled()) return Decision.DISABLED;
        settings.validate();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(settings.getZone())));
        if (!today.equals(day)) loadToday();
        Instant now = clock.instant();
        if (callCount < settings.getDailyLimit() && lastAllowed != null
                && now.isBefore(lastAllowed.plusMillis(settings.getMinGapMs()))) return Decision.TOO_SOON;
        // Do not trust memory for budget admission: persistence must succeed before HTTP is attempted.
        int count = store.reserve(API_NAME, day, settings.getDailyLimit(), now);
        if (count < 0) {
            callCount = store.read(API_NAME, day);
            if (!blockedLogged) {
                log.warn("Location API daily budget reached; stopped until next KST day: date={} callCount={} limit={}",
                        day, callCount, settings.getDailyLimit());
                blockedLogged = true;
            }
            return Decision.DAILY_LIMIT;
        }
        callCount = count;
        lastAllowed = now;
        return Decision.ALLOWED;
    }
    synchronized int callCount() { return callCount; }
}
