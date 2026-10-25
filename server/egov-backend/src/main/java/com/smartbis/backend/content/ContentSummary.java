package com.smartbis.backend.content;

import java.time.LocalDate;
import java.util.List;

public record ContentSummary(
        String contentId,
        String contentType,
        String category,
        String presentationType,
        String title,
        String targetFileName,
        LocalDate displayStartDate,
        LocalDate displayEndDate,
        List<String> targetRegions,
        String template,
        String mediaUrl,
        List<ContentAssetSummary> assets
) {
}
