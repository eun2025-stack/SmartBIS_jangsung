package com.smartbis.backend.admin;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ManagedContent(
        String contentId,
        String contentType,
        String title,
        String targetFileName,
        LocalDate displayStartDate,
        LocalDate displayEndDate,
        List<String> targetRegions,
        String template,
        String publicationStatus,
        String updatedBy,
        OffsetDateTime updatedAt
) {
}
