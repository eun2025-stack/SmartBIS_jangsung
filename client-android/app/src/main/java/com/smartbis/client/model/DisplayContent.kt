package com.smartbis.client.model

import org.json.JSONObject

enum class DisplayKind {
    ARRIVAL, DISASTER, POSTER_TEXT, TEXT, CARD_NEWS, IMAGE, VIDEO, UNKNOWN
}

data class DisplayAsset(
    val role: String,
    val fileName: String,
    val sequence: Int,
    val mediaUrl: String,
    val sha256: String?,
    val sizeBytes: Long?
)

data class ArrivalSnapshot(
    val currentStop: String,
    val nextStop: String,
    val currentNodeId: String,
    val nextNodeId: String,
    val distanceMeters: Double,
    val distanceFromCurrentStopMeters: Double,
    val within100m: Boolean,
    val estimatedArrivalMinutes: Int?,
    val estimatedArrivalSeconds: Long? = null,
    val arrivalStatusText: String? = null,
    val estimatedArrivalAt: String? = null
)

data class DisplayContent(
    val kind: DisplayKind,
    val displayType: String,
    val category: String,
    val contentId: String,
    val title: String,
    val text: String,
    val currentStop: String?,
    val nextStop: String?,
    val distanceMeters: Double?,
    val displaySeconds: Int,
    val mediaUrl: String?,
    val assets: List<DisplayAsset>,
    val offlineFallback: Boolean = false
) {
    companion object {
        fun from(json: JSONObject): DisplayContent {
            val type = json.optString("displayType").clean()
            val presentation = json.optString("presentationType", type).clean()
            val kind = kindOf(type, presentation)
            val assets = buildList {
                val array = json.optJSONArray("assets") ?: return@buildList
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("mediaUrl").clean()
                    if (url.isNotEmpty()) add(
                        DisplayAsset(
                            role = item.optString("role").clean(),
                            fileName = item.optString("fileName").clean(),
                            sequence = item.optInt("sequence", index + 1),
                            mediaUrl = url,
                            sha256 = item.optString("sha256").clean().ifEmpty { null },
                            sizeBytes = item.optLong("sizeBytes", -1L).takeIf { it >= 0L }
                        )
                    )
                }
            }.sortedBy { it.sequence }
            val contentId = json.optString("contentId").clean()
            val legacyUrl = json.optString("mediaUrl").clean().ifEmpty { null }
            val normalizedAssets = if (assets.isNotEmpty() || legacyUrl == null) assets else listOf(
                DisplayAsset(
                    role = if (kind == DisplayKind.VIDEO) "VIDEO" else "IMAGE",
                    fileName = contentId,
                    sequence = 1,
                    mediaUrl = legacyUrl,
                    sha256 = null,
                    sizeBytes = null
                )
            )
            return DisplayContent(
                kind = kind,
                displayType = type,
                category = json.optString("category").clean(),
                contentId = contentId,
                title = json.optString("title").clean(),
                text = json.optString("text").clean(),
                currentStop = json.optString("currentStop").clean().ifEmpty { null },
                nextStop = json.optString("nextStop").clean().ifEmpty { null },
                distanceMeters = if (json.isNull("distanceMeters")) null else json.optDouble("distanceMeters"),
                displaySeconds = json.optInt("displaySeconds", DEFAULT_DISPLAY_SECONDS).coerceAtLeast(1),
                mediaUrl = legacyUrl,
                assets = normalizedAssets
            )
        }

        fun kindOf(displayType: String, presentationType: String): DisplayKind {
            val type = displayType.uppercase()
            val presentation = presentationType.uppercase()
            return when {
                type == "ARRIVAL" -> DisplayKind.ARRIVAL
                type == "DISASTER" -> DisplayKind.DISASTER
                presentation == "POSTER_TEXT" -> DisplayKind.POSTER_TEXT
                presentation == "TEXT" -> DisplayKind.TEXT
                presentation == "CARD_NEWS" -> DisplayKind.CARD_NEWS
                presentation == "IMAGE" -> DisplayKind.IMAGE
                presentation == "VIDEO" || type == "VIDEO" -> DisplayKind.VIDEO
                type == "IMAGE" && presentation.isBlank() -> DisplayKind.IMAGE
                else -> DisplayKind.UNKNOWN
            }
        }

        private fun String.clean(): String = if (this == "null") "" else trim()

        private const val DEFAULT_DISPLAY_SECONDS = 10
    }
}
