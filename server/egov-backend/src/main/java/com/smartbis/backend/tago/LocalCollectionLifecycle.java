package com.smartbis.backend.tago;

import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local-collection")
public final class LocalCollectionLifecycle {
    private final CollectionSession session;
    public LocalCollectionLifecycle(JdbcTemplate jdbc,CollectionDeadline deadline) {
        session=new CollectionSession(new JdbcSessionStore(jdbc),Clock.systemUTC(),deadline);
    }
    @EventListener(ApplicationReadyEvent.class) public void ready() { session.start(); }
    @org.springframework.context.annotation.Bean(name="collectionSessionScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler sessionScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("collection-session-");return scheduler;
    }
    @Scheduled(fixedDelay=1000,scheduler="collectionSessionScheduler") public void tick() { session.tick(); }
    @EventListener(ContextClosedEvent.class) public void stop() { session.stop("시스템 적용"); }
    public static final class JdbcSessionStore implements CollectionSession.Store {
        private final JdbcTemplate jdbc;
        private final TransactionTemplate transaction;
        public JdbcSessionStore(JdbcTemplate jdbc) {
            this.jdbc=jdbc;transaction=new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));
        }
        public void start(UUID run,Instant now) {
            transaction.executeWithoutResult(status->{
                if(!"smartbis_db_copy2".equals(jdbc.queryForObject("SELECT current_database()",String.class)))
                    throw new IllegalStateException("LOCAL_COLLECTION_DATABASE_REQUIRED");
                jdbc.update("""
                    INSERT INTO collection_pause_intervals(started_at,ended_at,reason,exclude_from_analysis)
                    SELECT MIN(last_heartbeat_at),?,'기타',true FROM collection_process_runs
                    WHERE stopped_at IS NULL HAVING COUNT(*)>0
                      AND NOT EXISTS(SELECT 1 FROM collection_pause_intervals WHERE ended_at IS NULL)
                    """,Timestamp.from(now));
                jdbc.update("UPDATE collection_process_runs SET stopped_at=?,stop_reason='기타' WHERE stopped_at IS NULL",Timestamp.from(now));
                jdbc.update("UPDATE collection_pause_intervals SET ended_at=? WHERE ended_at IS NULL",Timestamp.from(now));
                jdbc.update("INSERT INTO collection_process_runs(run_id,started_at,last_heartbeat_at) VALUES(?,?,?)",run,Timestamp.from(now),Timestamp.from(now));
            });
        }
        public void heartbeat(UUID run,Instant at) {
            jdbc.update("UPDATE collection_process_runs SET last_heartbeat_at=? WHERE run_id=? AND stopped_at IS NULL",Timestamp.from(at),run);
        }
        public void stop(UUID run,Instant at,String reason) {
            transaction.executeWithoutResult(status->{
                int changed=jdbc.update("UPDATE collection_process_runs SET stopped_at=?,last_heartbeat_at=?,stop_reason=? WHERE run_id=? AND stopped_at IS NULL",
                    Timestamp.from(at),Timestamp.from(at),reason,run);
                if(changed==1) jdbc.update("INSERT INTO collection_pause_intervals(started_at,reason,exclude_from_analysis) VALUES(?,?,true)",Timestamp.from(at),reason);
            });
        }
    }
}
