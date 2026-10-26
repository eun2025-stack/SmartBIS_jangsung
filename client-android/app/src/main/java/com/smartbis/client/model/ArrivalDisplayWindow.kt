package com.smartbis.client.model

/** Tracks one stop from 100 m on approach until 100 m after it becomes the current stop. */
class ArrivalDisplayWindow {
    private var targetStopId: String? = null
    private var targetStopName: String? = null
    private var reachedTarget = false

    fun trackedStopName(fallback: String): String = targetStopName ?: fallback
    fun hasReachedTarget(): Boolean = reachedTarget

    fun reset() {
        targetStopId = null
        targetStopName = null
        reachedTarget = false
    }

    fun update(arrival: ArrivalSnapshot?): Boolean {
        if (arrival == null) return targetStopId != null

        if (targetStopId == null && arrival.within100m && arrival.nextNodeId.isNotBlank()) {
            targetStopId = arrival.nextNodeId
            targetStopName = arrival.nextStop
        }

        val target = targetStopId ?: return false
        if (arrival.currentNodeId == target) reachedTarget = true
        val remainsInWindow = when {
            arrival.currentNodeId == target -> arrival.distanceFromCurrentStopMeters <= WINDOW_METERS
            arrival.nextNodeId == target -> true
            else -> false
        }
        if (!remainsInWindow) reset()
        return remainsInWindow
    }

    companion object {
        private const val WINDOW_METERS = 100.0
    }
}
