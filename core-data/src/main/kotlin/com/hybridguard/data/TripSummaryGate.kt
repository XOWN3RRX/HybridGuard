package com.hybridguard.data

/**
 * Decides when the trip summary is shown (owner's request, 2026-11-14): the
 * moment the lever goes into P after at least [minKm] of driving. It stays
 * until the driver taps Done or the lever leaves P; parking again later in
 * the same trip shows it again, with the figures of the whole trip.
 *
 * A gear reading that is momentarily missing changes nothing. A new trip
 * (the distance went down: a new connection) starts from scratch, so a
 * reconnect while standing in P shows nothing.
 *
 * Pure and single-threaded: the caller feeds it every new state.
 */
class TripSummaryGate(private val minKm: Double = MIN_KM) {
    private var lastGear: String? = null
    private var lastKm = 0.0

    var showing: Boolean = false
        private set

    /** Feeds the newest gear and trip distance; returns [showing]. */
    fun update(gear: String?, tripKm: Double): Boolean {
        if (tripKm < lastKm) {
            lastGear = null
            showing = false
        }
        lastKm = tripKm
        if (gear == null) return showing
        if (gear == PARK) {
            if (lastGear != null && lastGear != PARK && tripKm >= minKm) showing = true
        } else {
            showing = false
        }
        lastGear = gear
        return showing
    }

    /** The driver tapped Done: back to the dashboard until the next time the car is parked. */
    fun dismiss() {
        showing = false
    }

    companion object {
        const val PARK = "P"

        /** Shorter trips (moving the car in a car park) get no summary. A display choice. */
        const val MIN_KM = 1.0
    }
}
