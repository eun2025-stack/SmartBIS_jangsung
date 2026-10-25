package com.smartbis.backend.tago;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded execution gate. Created only when operational location protection is explicitly enabled. */
final class TagoLocationExecution {
    @FunctionalInterface interface Call<T> { T run() throws Exception; }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
    @FunctionalInterface interface FailureRecorder { void record(String route, String reason); }
    private static final Logger log = LoggerFactory.getLogger(TagoLocationExecution.class);
    private final LocationCollectionProperties settings;
    private final TagoQuotaGuard guard;
    private final LocationCollectorLock lock;
    private final Sleeper sleeper;
    private final FailureRecorder failureRecorder;
    TagoLocationExecution(LocationCollectionProperties settings, TagoQuotaGuard guard,
                          LocationCollectorLock lock, Sleeper sleeper) {
        this(settings, guard, lock, sleeper, (route, reason) -> {});
    }
    TagoLocationExecution(LocationCollectionProperties settings, TagoQuotaGuard guard,
                          LocationCollectorLock lock, Sleeper sleeper, FailureRecorder recorder) {
        this.settings = settings; this.guard = guard; this.lock = lock; this.sleeper = sleeper;
        this.failureRecorder = recorder;
    }
    @PostConstruct
    void start() throws Exception {
        settings.validate();
        lock.acquire();
        try { guard.initialize(); }
        catch (Exception error) { lock.close(); throw error; }
    }
    @PreDestroy
    void stop() throws Exception { lock.close(); }
    boolean ready() { return guard.ready(); }

    synchronized <T> T execute(Call<T> call) throws Exception {
        return execute("", call);
    }
    synchronized <T> T execute(String route, Call<T> call) throws Exception {
        settings.validate();
        for (int attempt = 0; ; attempt++) {
            lock.ensureHeld();
            var decision = guard.acquire();
            if (decision != TagoQuotaGuard.Decision.ALLOWED) {
                throw new IllegalStateException("LOCATION_GUARD_" + decision);
            }
            try { return call.run(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw error; }
            catch (Exception error) {
                String reason = failureReason(error);
                log.warn("Location attempt failed: routeId={} attempt={} failureReason={}", route, attempt + 1, reason);
                if (!route.isBlank()) failureRecorder.record(route, reason);
                // 429 is not retried immediately; keep the existing collector cooldown path.
                if (attempt >= settings.getMaxRetries() || "TAGO_HTTP_429".equals(error.getMessage())) throw error;
                log.warn("Location request failed; bounded retry {}/{}", attempt + 1, settings.getMaxRetries());
                try { sleeper.sleep(settings.getRetryDelayMs()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
            }
        }
    }
    static String failureReason(Exception error) {
        String value = error.getMessage();
        if (value != null && value.matches("TAGO_(HTTP_[0-9]{3}|RESULT_[A-Za-z0-9_-]{1,30}|BODY_MISSING)|LOCATION_GUARD_[A-Z_]+")) return value;
        String type = error.getClass().getSimpleName();
        return type.matches("[A-Za-z][A-Za-z0-9]{0,79}") ? type : "DETAIL_UNAVAILABLE";
    }
}
