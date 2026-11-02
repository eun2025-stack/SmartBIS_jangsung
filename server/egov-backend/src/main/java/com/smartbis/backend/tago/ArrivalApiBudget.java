package com.smartbis.backend.tago;

import java.time.Clock;

/** A separate persisted API budget. A failed HTTP attempt consumes one reservation. */
final class ArrivalApiBudget {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ArrivalApiBudget.class);
    static final String API_NAME="TAGO_ARRIVAL";
    private final TagoQuotaGuard guard;
    private final int maxRetries;
    private final long retryGapMillis;
    private final TagoLocationExecution.Sleeper sleeper;
    private CollectionDeadline deadline=CollectionDeadline.unlimited();
    void setDeadline(CollectionDeadline deadline) { this.deadline=deadline; }
    ArrivalApiBudget(boolean enabled,int dailyLimit,GuardUsageStore store,Clock clock) {
        this(enabled,dailyLimit,store,clock,2,10000,Thread::sleep);
    }
    ArrivalApiBudget(boolean enabled,int dailyLimit,GuardUsageStore store,Clock clock,
            int maxRetries,long retryGapMillis,TagoLocationExecution.Sleeper sleeper) {
        if(dailyLimit<1||dailyLimit>9000) throw new IllegalArgumentException("ARRIVAL_BUDGET_MAX_9000");
        if(maxRetries<0||maxRetries>2||retryGapMillis<10000) throw new IllegalArgumentException("ARRIVAL_RETRY_MAX_2_GAP_MIN_10_SECONDS");
        this.maxRetries=maxRetries;this.retryGapMillis=retryGapMillis;this.sleeper=sleeper;
        var settings=new LocationCollectionProperties();
        settings.setEnabled(enabled);settings.setDailyLimit(dailyLimit);
        guard=new TagoQuotaGuard(settings,store,clock,API_NAME);
    }
    boolean ready() { return guard.ready("arrival",10000); }
    <T> T execute(TagoLocationExecution.Call<T> call) throws Exception {
        // A visit owns one logical result; each initial/recovery HTTP attempt consumes its own quota.
        for(int attempt=0;;attempt++) {
            deadline.requireOpen();
            if(guard.acquire("arrival",10000)!=TagoQuotaGuard.Decision.ALLOWED)
                throw new IllegalStateException("ARRIVAL_BUDGET_BLOCKED");
            try { return call.run(); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw interrupted; }
            catch(Exception failure) {
                log.warn("Arrival HTTP attempt failed: attempt={} failureReason={}",attempt+1,TagoLocationExecution.failureReason(failure));
                if(attempt>=maxRetries) throw failure;
                try { sleeper.sleep(retryGapMillis); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw interrupted; }
            }
        }
    }
    int count() { return guard.callCount(); }
}
