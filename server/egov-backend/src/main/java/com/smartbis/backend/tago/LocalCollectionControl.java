package com.smartbis.backend.tago;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.file.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Local-only, fail-closed switch. Preparing a run never authorizes HTTP. */
@Component
@Profile("local-collection")
public final class LocalCollectionControl {
    public record Run(UUID collectionRunId,Instant preparedAt,String commitHash,String configHash) {
        public Run {
            if(collectionRunId==null || collectionRunId.equals(new UUID(0,0)) || preparedAt==null
                || commitHash==null || !commitHash.matches("[0-9a-f]{40}")
                || configHash==null || !configHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("INVALID_COLLECTION_RUN");
        }
    }
    interface Store { void prepare(Run run); void activate(UUID run,Instant at); }
    private final Store store;
    private final Path directory;
    private final Clock clock;
    private Run run;
    private boolean ready;
    @Autowired public LocalCollectionControl(JdbcTemplate jdbc,
        @Value("${collection.control-directory:/collection-control}") String directory) {
        this(new JdbcStore(jdbc),Path.of(directory),Clock.systemUTC());
    }
    LocalCollectionControl(Store store,Path directory,Clock clock) {
        this.store=store;this.directory=directory;this.clock=clock;
    }
    @PostConstruct public synchronized void prepare() throws Exception {
        if(ready)return;
        var json=new ObjectMapper().readTree(Files.readString(directory.resolve("run.json")));
        Run candidate=new Run(UUID.fromString(json.path("collection_run_id").asText()),
            Instant.parse(json.path("prepared_at").asText()),json.path("commit_hash").asText(),json.path("config_hash").asText());
        store.prepare(candidate);
        run=candidate;
        // A prepared marker is not a call authorization. Publish it atomically.
        Path staging=directory.resolve("ready.json.tmp");
        Files.writeString(staging,new ObjectMapper().writeValueAsString(Map.of(
            "collection_run_id",run.collectionRunId().toString(),"commit_hash",run.commitHash(),"config_hash",run.configHash())));
        Files.move(staging,directory.resolve("ready.json"),StandardCopyOption.REPLACE_EXISTING);
        ready=true;
    }
    public synchronized UUID runId() {
        if(!ready)throw new IllegalStateException("COLLECTION_RUN_NOT_PREPARED");
        return run.collectionRunId();
    }
    public synchronized boolean callsAllowed() {
        if(!ready)return false;
        try {
            if(!Files.readString(directory.resolve("enabled")).trim().equals(run.collectionRunId().toString()))return false;
        }catch(java.io.IOException failure){return false;}
        // Idempotent: first observed approval defines the actual start time.
        store.activate(run.collectionRunId(),clock.instant());
        return true;
    }
    public void requireEnabled() {
        if(!callsAllowed())throw new IllegalStateException("LOCAL_COLLECTION_SWITCH_OFF");
    }
    static final class JdbcStore implements Store {
        private final JdbcTemplate jdbc;
        JdbcStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
        public void prepare(Run run) {
            if(!"smartbis_db_copy2".equals(jdbc.queryForObject("SELECT current_database()",String.class)))
                throw new IllegalStateException("LOCAL_COLLECTION_DATABASE_REQUIRED");
            jdbc.update("""
                INSERT INTO collection_runs(collection_run_id,prepared_at,commit_hash,config_hash,archived)
                VALUES(?,?,?,?,false) ON CONFLICT(collection_run_id) DO NOTHING
                """,run.collectionRunId(),Timestamp.from(run.preparedAt()),run.commitHash(),run.configHash());
            Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM collection_runs WHERE collection_run_id=? AND commit_hash=? AND config_hash=? AND NOT archived
                """,Integer.class,run.collectionRunId(),run.commitHash(),run.configHash());
            if(count==null||count!=1)throw new IllegalStateException("COLLECTION_RUN_METADATA_CONFLICT");
        }
        public void activate(UUID run,Instant at) {
            jdbc.update("UPDATE collection_runs SET started_at=COALESCE(started_at,?) WHERE collection_run_id=? AND NOT archived",Timestamp.from(at),run);
        }
    }
}
