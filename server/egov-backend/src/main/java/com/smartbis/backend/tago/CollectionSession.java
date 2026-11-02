package com.smartbis.backend.tago;

import java.time.*;
import java.util.UUID;

/** Pure lifecycle controller; the store owns transactionality and recovery of unfinished runs. */
public final class CollectionSession {
    public interface Store {
        void start(UUID run,Instant at);
        void heartbeat(UUID run,Instant at);
        void stop(UUID run,Instant at,String reason);
    }
    private final Store store;
    private final Clock clock;
    private final CollectionDeadline deadline;
    private UUID run;
    public CollectionSession(Store store,Clock clock,CollectionDeadline deadline) {
        this.store=store;this.clock=clock;this.deadline=deadline;
    }
    public synchronized void start() {
        deadline.requireOpen();
        if(run!=null) return;
        UUID next=UUID.randomUUID();store.start(next,clock.instant());run=next;
    }
    public synchronized void tick() {
        if(run==null) return;
        if(!deadline.open()) stop("시스템 적용");else store.heartbeat(run,clock.instant());
    }
    public synchronized void stop(String reason) {
        if(run==null) return;
        store.stop(run,clock.instant(),reason);run=null;
    }
}
