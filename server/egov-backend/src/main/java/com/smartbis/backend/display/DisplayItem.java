package com.smartbis.backend.display;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import com.smartbis.backend.content.ContentAssetSummary;

public record DisplayItem(
        String displayType,
        int priority,
        boolean within100m,
        String currentStop,
        String nextStop,
        BigDecimal distanceMeters,
        String contentId,
        String template,
        String text,
        String mediaUrl,
        OffsetDateTime expiresAt,
        Integer displaySeconds,
        Integer repeatIntervalSeconds,
        String category,
        String presentationType,
        List<ContentAssetSummary> assets,
        String title
) {
    public DisplayItem(
            String displayType, int priority, boolean within100m,
            String currentStop, String nextStop, BigDecimal distanceMeters,
            String contentId, String template, String text, String mediaUrl,
            OffsetDateTime expiresAt, Integer displaySeconds,
            Integer repeatIntervalSeconds) {
        this(displayType, priority, within100m, currentStop, nextStop,
                distanceMeters, contentId, template, text, mediaUrl, expiresAt,
                displaySeconds, repeatIntervalSeconds, null, null, List.of(), null);
    }
}
