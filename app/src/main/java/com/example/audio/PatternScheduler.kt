package com.example.audio

import com.example.model.NoteEvent
import com.example.model.TimeSignature

/**
 * Pre-indexed time slice in a HandpanPattern.
 * Supports fractional beats (e.g. 0.0, 0.25, 0.5, 0.75, 1.333) and multiple simultaneous events.
 */
data class ScheduledTimeSlice(
    val beatPosition: Double,
    val barIndex: Int,
    val beatInBar: Double,
    val isDownbeat: Boolean,
    val events: List<NoteEvent>
)

/**
 * High-performance pattern scheduler that pre-indexes events into ordered time slices.
 * Avoids O(N) linear scans during real-time playback.
 */
class PatternScheduler {

    companion object {
        const val BEAT_EPSILON = 0.005

        /**
         * Pre-indexes and organizes pattern events along with fractional beat positions into structured slices.
         */
        fun buildSchedule(
            events: List<NoteEvent>,
            beatsPerBar: Int,
            totalBars: Int,
            startBar: Int = 1,
            endBar: Int = totalBars,
            timeSignature: TimeSignature = TimeSignature.Common44
        ): List<ScheduledTimeSlice> {
            val clampedStart = startBar.coerceIn(1, totalBars)
            val clampedEnd = endBar.coerceIn(clampedStart, totalBars)

            val startBeat = ((clampedStart - 1) * beatsPerBar).toDouble()
            val endBeat = (clampedEnd * beatsPerBar).toDouble()

            // Collect all unique beat positions including downbeats and fractional hits
            val beatPositions = mutableSetOf<Double>()

            // 1. Add all measure downbeats and integer beats in the range
            var b = startBeat
            while (b < endBeat - BEAT_EPSILON) {
                beatPositions.add(Math.round(b * 1000.0) / 1000.0)
                b += 1.0
            }

            // 2. Add all event beat positions in range
            events.filter { it.beatPosition in (startBeat - BEAT_EPSILON)..(endBeat - BEAT_EPSILON) }
                .forEach { event ->
                    beatPositions.add(Math.round(event.beatPosition * 1000.0) / 1000.0)
                }

            val sortedPositions = beatPositions.sorted()

            return sortedPositions.map { pos ->
                val barIndex = (pos / beatsPerBar).toInt() + 1
                val beatInBar = (pos % beatsPerBar) + 1.0
                val beatInBarIndex = (pos % beatsPerBar).toInt() + 1
                val isDownbeat = timeSignature.isGroupedAccent(beatInBarIndex) &&
                    Math.abs(pos - Math.floor(pos)) < BEAT_EPSILON

                val matchingEvents = events.filter {
                    Math.abs(it.beatPosition - pos) < BEAT_EPSILON
                }

                ScheduledTimeSlice(
                    beatPosition = pos,
                    barIndex = barIndex,
                    beatInBar = beatInBar,
                    isDownbeat = isDownbeat,
                    events = matchingEvents
                )
            }
        }
    }
}
