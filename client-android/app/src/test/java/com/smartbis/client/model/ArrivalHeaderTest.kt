package com.smartbis.client.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.io.File
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer

class ArrivalHeaderTest {
    private val now = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli()
    private fun value(seconds: Long, status: String? = null, node: String = "S1") = ArrivalSnapshot(
        "이전", "장성군청", "S0", node, 50.0, 100.0, true, 99, 999,
        status, Instant.ofEpochMilli(now + seconds * 1_000).toString())

    @Test fun floorsMinutesFromAbsoluteDbInstantNotLegacyValues() {
        assertEquals("다음 정류장 → 장성군청 3분 후 도착", ArrivalHeader.text(value(200), now))
        assertEquals("다음 정류장 → 장성군청 2분 후 도착", ArrivalHeader.text(value(150), now))
        assertEquals("다음 정류장 → 장성군청 1분 후 도착", ArrivalHeader.text(value(90), now))
        assertEquals("다음 정류장 → 장성군청 1분 후 도착", ArrivalHeader.text(value(50), now))
    }
    @Test fun exactThirtySecondBoundaryAndFraction() {
        assertEquals("다음 정류장 → 장성군청 곧 도착", ArrivalHeader.text(value(30), now))
        assertEquals("다음 정류장 → 장성군청 1분 후 도착", ArrivalHeader.text(value(31), now))
        assertEquals("다음 정류장 → 장성군청 1분 후 도착", ArrivalHeader.text(value(30), now - 1))
    }
    @Test fun thirtyThirtyOneFiftyNineAndSixtySecondBoundaries() {
        assertEquals("다음 정류장 → 장성군청 곧 도착", ArrivalHeader.text(value(30), now))
        for (seconds in listOf(31L, 59L, 60L)) {
            assertEquals("ETA=$seconds", "다음 정류장 → 장성군청 1분 후 도착",
                ArrivalHeader.text(value(seconds), now))
        }
        assertEquals("다음 정류장 → 장성군청 1분 후 도착", ArrivalHeader.text(value(60), now + 1))
    }
    @Test fun minutesAtAndAboveSixtySecondsStillRoundDown() {
        assertEquals("다음 정류장 → 장성군청 1분 후 도착", ArrivalHeader.text(value(119), now))
        assertEquals("다음 정류장 → 장성군청 2분 후 도착", ArrivalHeader.text(value(120), now))
    }
    @Test fun updatesWithoutAnotherApiResponse() {
        val saved = value(90)
        assertEquals("다음 정류장 → 장성군청 곧 도착", ArrivalHeader.text(saved, now + 60_000))
    }
    @Test fun fullScreenThirtySecondsIsSoon() {
        assertEquals("곧 도착", ArrivalHeader.timingText(value(30), now))
    }
    @Test fun fullScreenThirtyOneSecondsIsOneMinute() {
        assertEquals("1분 후 도착", ArrivalHeader.timingText(value(31), now))
    }
    @Test fun fullScreenFiftyNineSecondsIsOneMinute() {
        assertEquals("1분 후 도착", ArrivalHeader.timingText(value(59), now))
    }
    @Test fun fullScreenSixtySecondsIsOneMinute() {
        assertEquals("1분 후 도착", ArrivalHeader.timingText(value(60), now))
    }
    @Test fun fullScreenUsesSharedAbsoluteTimeFormatterNotRoundedMinutes() {
        val source = File("src/main/java/com/smartbis/client/view/NaturalDisplayView.kt").readText()
            .substringAfter("override fun showFullScreenArrival(").substringBefore("override fun showArrival(")
        assertTrue(source.contains("ArrivalHeader.timingText(arrival, System.currentTimeMillis())"))
        assertFalse(source.contains("estimatedArrivalMinutes"))
        assertEquals("정보 없음", ArrivalHeader.timingText(value(90).copy(estimatedArrivalAt = null), now))
        assertEquals("1분 후 도착", ArrivalHeader.timingText(value(119), now))
        assertEquals("2분 후 도착", ArrivalHeader.timingText(value(120), now))
    }
    @Test fun sixtySecondsAfterExpectedBoundary() {
        assertEquals("다음 정류장 → 장성군청 곧 도착", ArrivalHeader.text(value(-60), now))
        assertEquals("다음 정류장 → 장성군청 통과 추정", ArrivalHeader.text(value(-61), now))
    }
    @Test fun databaseConfirmedPassTakesPriority() {
        assertEquals("다음 정류장 → 장성군청 통과", ArrivalHeader.text(value(-20, "통과"), now))
    }
    @Test fun missingInvalidInstantNeverFallsBackToMinutes() {
        assertEquals("정보 없음", ArrivalHeader.text(null, now))
        assertEquals("정보 없음", ArrivalHeader.text(value(90).copy(estimatedArrivalAt = null), now))
        assertEquals("정보 없음", ArrivalHeader.text(value(90).copy(estimatedArrivalAt = "invalid"), now))
    }
    @Test fun passageShowsOnceForFiveSecondsThenNextStop() {
        val state = ArrivalHeaderState()
        val passed = value(-10, "통과")
        state.accept(passed, now)
        assertEquals(passed, state.display(now + 4_999))
        assertFalse(state.needsNext(now + 4_999))
        assertTrue(state.needsNext(now + 5_000))
        assertEquals("S1", state.cursor(now + 5_000))
        state.markNextRequested()
        assertFalse(state.needsNext(now + 5_001))
        state.accept(passed, now + 6_000)
        assertNull(state.display(now + 6_000)) // Same DB pass cannot start a second display window.
        val next = value(100, node = "S2").copy(nextStop = "다음역")
        state.accept(next, now + 6_100)
        assertEquals(next, state.display(now + 6_100))
        assertNull(state.cursor(now + 6_100))
    }
    @Test fun notFoundClearsOldHeader() {
        val state = ArrivalHeaderState()
        state.accept(value(90), now)
        state.accept(null, now + 1_000)
        assertEquals("정보 없음", ArrivalHeader.text(state.display(now + 1_000), now + 1_000))
    }
    @Test fun repository404IsNullAndDisplaysNoInformation() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/arrival") { exchange -> exchange.sendResponseHeaders(404, -1); exchange.close() }
        server.start()
        try {
            val repo = SmartBisRepository(File(System.getProperty("java.io.tmpdir")),
                { "http://127.0.0.1:${server.address.port}" }, { "전남76아7500" })
            val missing = repo.currentArrival()
            assertNull(missing)
            assertEquals("정보 없음", ArrivalHeader.text(missing, now))
        } finally { server.stop(0) }
    }
}
