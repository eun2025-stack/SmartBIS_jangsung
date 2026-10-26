package com.smartbis.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class DisplayContentTest {
    @Test
    fun serverArrivalAndDisasterTypesOverrideContentPresentation() {
        assertEquals(DisplayKind.ARRIVAL, DisplayContent.kindOf("ARRIVAL", ""))
        assertEquals(DisplayKind.DISASTER, DisplayContent.kindOf("DISASTER", ""))
    }

    @Test
    fun allApprovedContentPresentationTypesHaveTheirOwnScreen() {
        assertEquals(DisplayKind.POSTER_TEXT, DisplayContent.kindOf("IMAGE", "POSTER_TEXT"))
        assertEquals(DisplayKind.TEXT, DisplayContent.kindOf("CARD", "TEXT"))
        assertEquals(DisplayKind.CARD_NEWS, DisplayContent.kindOf("CARD", "CARD_NEWS"))
        assertEquals(DisplayKind.IMAGE, DisplayContent.kindOf("IMAGE", "IMAGE"))
        assertEquals(DisplayKind.VIDEO, DisplayContent.kindOf("VIDEO", "VIDEO"))
    }

    @Test
    fun legacyVideoTypeStillUsesVideoScreen() {
        assertEquals(DisplayKind.VIDEO, DisplayContent.kindOf("VIDEO", ""))
    }

    @Test
    fun assetIntegrityChecksDeclaredSizeAndSha256() {
        val file = File.createTempFile("smartbis-asset", ".bin")
        try {
            val bytes = "media-test".toByteArray()
            file.writeBytes(bytes)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val valid = DisplayAsset("IMAGE", "poster.jpg", 1, "/media", sha, bytes.size.toLong())
            assertTrue(AssetIntegrity.isValid(file, valid))
            assertFalse(AssetIntegrity.isValid(file, valid.copy(sizeBytes = bytes.size + 1L)))
            assertFalse(AssetIntegrity.isValid(file, valid.copy(sha256 = "0".repeat(64))))
        } finally {
            file.delete()
        }
    }
}
