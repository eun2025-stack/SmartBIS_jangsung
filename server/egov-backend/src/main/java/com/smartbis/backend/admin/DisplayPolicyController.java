package com.smartbis.backend.admin;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/display-policies")
public class DisplayPolicyController {

    private final DisplayPolicyService service;

    public DisplayPolicyController(DisplayPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> findAll() {
        return service.findAll();
    }

    @PutMapping("/{policyKey}")
    public Map<String, Object> update(
            @PathVariable String policyKey,
            @RequestBody PolicyUpdateRequest request,
            Authentication authentication
    ) {
        return service.update(
                policyKey,
                request.displaySeconds(),
                request.repeatIntervalSeconds(),
                request.priority(),
                request.enabled(),
                authentication.getName()
        );
    }

    public record PolicyUpdateRequest(
            Integer displaySeconds,
            Integer repeatIntervalSeconds,
            Integer priority,
            Boolean enabled
    ) {
    }
}
