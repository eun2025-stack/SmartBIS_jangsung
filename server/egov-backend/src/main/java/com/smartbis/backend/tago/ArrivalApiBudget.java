package com.smartbis.backend.tago;

import java.time.Clock;

/** A separate persisted API budget. A failed HTTP attempt consumes one reservation. */
final class ArrivalApiBudget {
    static final String API_NAME="TAGO_ARRIVAL";
    private final TagoQuotaGuard guard;
    ArrivalApiBudget(boolean enabled,int dailyLimit,GuardUsageStore store,Clock clock) {
        if(dailyLimit<1||dailyLimit>9000) throw new IllegalArgumentException("ARRIVAL_BUDGET_MAX_9000");
        var settings=new LocationCollectionProperties();
        settings.setEnabled(enabled);settings.setDailyLimit(dailyLimit);
        guard=new TagoQuotaGuard(settings,store,clock,API_NAME);
    }
    boolean ready() { return guard.ready("arrival",10000); }
    <T> T execute(TagoLocationExecution.Call<T> call) throws Exception {
        // The visit's one-request hard cap takes precedence over optional retries.
        if(guard.acquire("arrival",10000)!=TagoQuotaGuard.Decision.ALLOWED)
            throw new IllegalStateException("ARRIVAL_BUDGET_BLOCKED");
        return call.run();
    }
    int count() { return guard.callCount(); }
}
