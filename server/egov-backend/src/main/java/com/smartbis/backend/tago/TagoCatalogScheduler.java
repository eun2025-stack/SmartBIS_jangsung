package com.smartbis.backend.tago;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Refreshes route/stops daily before service starts; catalog sync triggers automatic region grouping. */
@Component
public class TagoCatalogScheduler {
    private static final Logger log = LoggerFactory.getLogger(TagoCatalogScheduler.class);
    private final TagoCatalogService catalogService;

    public TagoCatalogScheduler(TagoCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Scheduled(cron = "${tago.catalog.sync-cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void synchronizeDaily() {
        log.info("Scheduled TAGO route catalog refresh started");
        try {
            Map<String, Object> result = catalogService.synchronizeCatalog("AUTOMATIC");
            Object classification = result.get("regionClassification");
            log.info("Scheduled TAGO refresh finished: routes={} stops={} regionClassification={}",
                    result.get("routeCount"), result.get("uniqueStopCount"),
                    classification instanceof Map<?, ?> details ? details.get("status") : "UNKNOWN");
        } catch (Exception exception) {
            log.error("Scheduled TAGO refresh failed; exceptionType={}", exception.getClass().getSimpleName());
        }
    }
}
