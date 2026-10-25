package com.smartbis.backend.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/admin/boundary-regions")
public class BoundaryRegionController {
    private static final Logger log = LoggerFactory.getLogger(BoundaryRegionController.class);

    private final BoundaryRegionService service;

    public BoundaryRegionController(BoundaryRegionService service) {
        this.service = service;
    }

    @PostMapping("/classify-unassigned")
    public Map<String, Object> classifyUnassigned() {
        log.info("Unassigned bus stop boundary classification requested");
        return service.classifyUnassigned();
    }
}
