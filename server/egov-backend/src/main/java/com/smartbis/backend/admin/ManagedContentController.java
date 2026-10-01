package com.smartbis.backend.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/contents")
public class ManagedContentController {
    private final ManagedContentService service;

    public ManagedContentController(ManagedContentService service) {
        this.service = service;
    }

    @GetMapping
    public List<ManagedContent> list(
            @RequestParam(defaultValue = "ACTIVE") String status
    ) {
        return service.list(status);
    }

    @PutMapping("/{contentId}/publication-state")
    public PublicationStateResponse updateState(
            @PathVariable String contentId,
            @RequestBody PublicationStateRequest request,
            Authentication authentication
    ) {
        return service.updateState(contentId, request, authentication.getName());
    }

    public record PublicationStateRequest(String status) {
    }

    public record PublicationStateResponse(
            String contentId,
            String publicationStatus,
            String updatedBy
    ) {
    }
}
