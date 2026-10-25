package com.smartbis.backend.content;

public record ContentAssetSummary(
        String role,
        String fileName,
        int sequence,
        String mimeType,
        Long sizeBytes,
        String sha256,
        String mediaUrl
) {
}
