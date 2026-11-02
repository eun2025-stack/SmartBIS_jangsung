package com.smartbis.backend.tago;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Checked at every HTTP attempt, including recovery attempts. Empty outside the local profile. */
@Component
public final class CollectionDeadline {
    private final Clock clock;
    private final Instant stop;
    @Autowired public CollectionDeadline(@Value("${collection.stop-at:}") String configured) {
        this(Clock.systemUTC(),configured.isBlank()?null:Instant.parse(configured));
    }
    public CollectionDeadline(Clock clock,Instant stop) { this.clock=clock;this.stop=stop; }
    static CollectionDeadline unlimited() { return new CollectionDeadline(Clock.systemUTC(),null); }
    public boolean open() { return stop==null||clock.instant().isBefore(stop); }
    public void requireOpen() {
        if(!open()) {
            org.slf4j.LoggerFactory.getLogger(CollectionDeadline.class).info("Collection deadline reached; API calls blocked");
            throw new IllegalStateException("COLLECTION_DEADLINE_REACHED");
        }
    }
}
