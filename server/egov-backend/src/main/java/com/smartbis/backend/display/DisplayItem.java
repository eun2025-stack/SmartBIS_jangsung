package com.smartbis.backend.display;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
        Integer repeatIntervalSeconds
) {
}
