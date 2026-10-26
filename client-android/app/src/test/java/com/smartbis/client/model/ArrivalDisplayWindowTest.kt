package com.smartbis.client.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalDisplayWindowTest {
    private val window = ArrivalDisplayWindow()

    @Test
    fun startsAtOneHundredMetersBeforeAndEndsAfterOneHundredMetersPastStop() {
        assertFalse(window.update(snapshot(current = "A", next = "B", nextDistance = 101.0, currentDistance = 20.0)))
        assertTrue(window.update(snapshot(current = "A", next = "B", nextDistance = 100.0, currentDistance = 5.0)))
        assertTrue(window.update(snapshot(current = "A", next = "B", nextDistance = 12.0, currentDistance = 95.0)))
        assertTrue(window.update(snapshot(current = "B", next = "C", nextDistance = 250.0, currentDistance = 100.0)))
        assertFalse(window.update(snapshot(current = "B", next = "C", nextDistance = 240.0, currentDistance = 100.01)))
    }

    @Test
    fun keepsCurrentWindowDuringTemporaryArrivalApiFailure() {
        assertTrue(window.update(snapshot(current = "A", next = "B", nextDistance = 90.0, currentDistance = 10.0)))
        assertTrue(window.update(null))
    }

    private fun snapshot(current: String, next: String, nextDistance: Double, currentDistance: Double) =
        ArrivalSnapshot(
            currentStop = current,
            nextStop = next,
            currentNodeId = current,
            nextNodeId = next,
            distanceMeters = nextDistance,
            distanceFromCurrentStopMeters = currentDistance,
            within100m = nextDistance <= 100.0,
            estimatedArrivalMinutes = 1
        )
}
