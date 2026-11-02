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
    private final String apiName;
    private final java.util.Map<String,Instant> laneLastAllowed=new java.util.HashMap<>();
    private LocalDate day;
    private Instant lastAllowed;
    private int callCount;
    private boolean blockedLogged;

    TagoQuotaGuard(LocationCollectionProperties settings, GuardUsageStore store, Clock clock) {
        this(settings,store,clock,API_NAME);
    }
    TagoQuotaGuard(LocationCollectionProperties settings, GuardUsageStore store, Clock clock,String apiName) {
        this.settings = settings; this.store = store; this.clock = clock;
        this.apiName=apiName;
    }
    synchronized void initialize() {
        if (settings.isEnabled()) loadToday();
    }
    private void loadToday() {
        day = LocalDate.now(clock.withZone(ZoneId.of(settings.getZone())));
        callCount = store.read(apiName, day);
        laneLastAllowed.clear();
        blockedLogged = false;
        log.info("API guard resumed: api={} date={} callCount={} dailyLimit={}", apiName, day, callCount, settings.getDailyLimit());
    }
    synchronized boolean ready() {
        return ready("legacy",settings.getMinGapMs());
    }
    synchronized boolean ready(String lane,long gapMs) {
        if (!settings.isEnabled()) return false;
        settings.validate();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(settings.getZone())));
        if (!today.equals(day)) loadToday();
        if (callCount >= settings.getDailyLimit()) {
            if (!blockedLogged) {
                log.warn("API daily budget reached: api={} date={} callCount={} limit={}", apiName, day, callCount, settings.getDailyLimit());
                blockedLogged = true;
            }
            return false;
        }
        Instant last=laneLastAllowed.get(lane);
        return last==null || !clock.instant().isBefore(last.plusMillis(gapMs));
    }
    synchronized Decision acquire() {
        return acquire("legacy",settings.getMinGapMs());
    }
    synchronized Decision acquire(String lane,long gapMs) {
        if (!settings.isEnabled()) return Decision.DISABLED;
        settings.validate();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(settings.getZone())));
        if (!today.equals(day)) loadToday();
        Instant now = clock.instant();
        Instant last=laneLastAllowed.get(lane);
        if (callCount < settings.getDailyLimit() && last != null
                && now.isBefore(last.plusMillis(gapMs))) return Decision.TOO_SOON;
        // Do not trust memory for budget admission: persistence must succeed before HTTP is attempted.
        int count = store.reserve(apiName, day, settings.getDailyLimit(), now);
        if (count < 0) {
            callCount = store.read(apiName, day);
            if (!blockedLogged) {
                log.warn("API daily budget reached: api={} date={} callCount={} limit={}",
                        apiName, day, callCount, settings.getDailyLimit());
                blockedLogged = true;
            }
            return Decision.DAILY_LIMIT;
        }
        callCount = count;
        lastAllowed = now;
        laneLastAllowed.put(lane,now);
        return Decision.ALLOWED;
    }
    synchronized int callCount() { return callCount; }
    Instant now() { return clock.instant(); }
}
