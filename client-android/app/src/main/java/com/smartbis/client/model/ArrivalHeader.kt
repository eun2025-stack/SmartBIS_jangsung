package com.smartbis.client.model

import java.time.Instant

/** Header timing uses ONLY the DB's absolute arrival instant, never the rounded legacy ETA. */
object ArrivalHeader {
    fun text(value: ArrivalSnapshot?, nowMillis: Long): String {
        val stop = value?.nextStop?.takeIf { it.isNotBlank() } ?: return "정보 없음"
        val suffix = timingText(value, nowMillis)
        return if (suffix == "정보 없음") suffix else "다음 정류장 → $stop $suffix"
    }

    /** Shared by the header and full-screen arrival panel; never uses legacy rounded ETA. */
    fun timingText(value: ArrivalSnapshot?, nowMillis: Long): String {
        if (value == null) return "정보 없음"
        val expected = runCatching { Instant.parse(value.estimatedArrivalAt).toEpochMilli() }.getOrNull()
            ?: return "정보 없음"
        val remaining = expected - nowMillis
        return when {
            value.arrivalStatusText == "통과" -> "통과" // Already confirmed from DB node sequence.
            remaining < -60_000L -> "통과 추정"
            remaining <= 30_000L -> "곧 도착"
            remaining < 60_000L -> "1분 후 도착"
            else -> "${remaining / 60_000L}분 후 도착"
        }
    }
}

/** Main-thread state only. Passage is shown once, then a DB-only cursor requests the following stop. */
class ArrivalHeaderState(private val passDisplayMillis: Long = 5_000L) {
    private var current: ArrivalSnapshot? = null
    private var handledPass: String? = null
    private var held: ArrivalSnapshot? = null
    private var holdUntil = 0L
    private var skipUntil = 0L
    private var skipNode: String? = null
    private var requestedNext = false

    fun accept(value: ArrivalSnapshot?, now: Long) {
        current = value
        if (value == null) { held = null; return }
        val key = "${value.nextNodeId}|${value.estimatedArrivalAt}"
        if (value.arrivalStatusText == "통과" && value.estimatedArrivalAt != null && key != handledPass) {
            handledPass = key; held = value; holdUntil = now + passDisplayMillis
            skipUntil = now + 60_000L; skipNode = value.nextNodeId; requestedNext = false
        }
        if (value.nextNodeId != skipNode && value.arrivalStatusText != "통과") {
            held = null; skipNode = null; requestedNext = false
        }
    }
    fun display(now: Long): ArrivalSnapshot? {
        held?.let { if (now < holdUntil) return it }
        val value = current ?: return null
        return if (value.arrivalStatusText == "통과" && now >= holdUntil) null else value
    }
    fun needsNext(now: Long) = skipNode != null && now >= holdUntil && now < skipUntil && !requestedNext
    fun markNextRequested() { requestedNext = true }
    fun cursor(now: Long): String? = skipNode?.takeIf { now >= holdUntil && now < skipUntil }
}
