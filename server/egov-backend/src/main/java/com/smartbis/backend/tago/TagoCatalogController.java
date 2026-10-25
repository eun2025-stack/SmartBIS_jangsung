package com.smartbis.backend.tago;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@RestController
@RequestMapping("/v1/admin/tago/catalog")
public class TagoCatalogController {
    private static final Logger log = LoggerFactory.getLogger(TagoCatalogController.class);
    private final TagoCatalogService service;

    public TagoCatalogController(TagoCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> status() {
        return service.catalogStatus();
    }

    @PostMapping("/sync")
    public Map<String, Object> synchronize() {
        log.info("TAGO catalog synchronization request reached controller");
        return service.synchronizeCatalog("MANUAL");
    }

    @PostMapping("/validate")
    public Map<String, Object> validate() {
        log.info("TAGO read-only cross-validation request reached controller");
        return service.validateCatalog();
    }
}
