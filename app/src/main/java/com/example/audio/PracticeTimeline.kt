package com.example.audio

import com.example.model.HandpanPattern
import com.example.model.NoteEvent

data class PracticeTimelinePosition(
    val elapsedNanos: Long,
    val elapsedMs: Long,
    val currentBeat: Double,
    val currentBeatInBar: Double,
    val beatNumber: Int,
    val barNumber: Int,
    val currentNoteIndex: Int,
    val currentNote: NoteEvent?,
    val nextNote: NoteEvent?,
    val beatProgress: Float,
    val patternProgress: Float,
    val bpm: Int,
    val isOnBeatWindow: Boolean,
    val countdownRemaining: Int = 0
)

class PracticeTimeline(
    private val pattern: HandpanPattern,
    val bpm: Int = pattern.bpm
) {
    private val beatDurationNanos = MusicalTiming.beatDurationNanos(bpm)
    private val totalDurationNanos = MusicalTiming.beatToNanos(
        pattern.totalBeats,
        bpm,
        pattern.timeSignature
    )

    fun positionAt(elapsedNanos: Long, countdownBeats: Int = 0): PracticeTimelinePosition {
        val elapsed = elapsedNanos.coerceIn(0L, totalDurationNanos)
        val beat = elapsed.toDouble() / beatDurationNanos.toDouble()
        val countdownRemaining = (countdownBeats - kotlin.math.floor(beat).toInt()).coerceAtLeast(0)
        return positionAtBeat(beat, elapsed, countdownRemaining)
    }

    fun positionAtBeat(beat: Double, countdownRemaining: Int = 0): PracticeTimelinePosition {
        val clampedBeat = beat.coerceIn(0.0, pattern.totalBeats)
        val elapsed = MusicalTiming.beatToNanos(clampedBeat, bpm, pattern.timeSignature)
        return positionAtBeat(clampedBeat, elapsed, countdownRemaining)
    }

    private fun positionAtBeat(
        beat: Double,
        elapsed: Long,
        countdownRemaining: Int
    ): PracticeTimelinePosition {
        val currentIndex = pattern.events.indexOfLast { event ->
            !event.isRest && event.beatPosition <= beat + PatternScheduler.BEAT_EPSILON
        }
        val current = pattern.events.getOrNull(currentIndex)
        val next = pattern.events.drop(currentIndex + 1).firstOrNull { !it.isRest }
        val beatFraction = (beat - kotlin.math.floor(beat)).toFloat()
        val progress = if (totalDurationNanos == 0L) 1f
        else elapsed.toFloat() / totalDurationNanos.toFloat()
        val beatNumber = kotlin.math.floor(beat).toInt() + 1
        val barNumber = kotlin.math.floor(beat / pattern.timeSignature.beatsPerBar).toInt() + 1
        val beatProgress = beatFraction

        return PracticeTimelinePosition(
            elapsedNanos = elapsed,
            elapsedMs = elapsed / 1_000_000L,
            currentBeat = beat,
            currentBeatInBar = (beat % pattern.timeSignature.beatsPerBar) + 1.0,
            beatNumber = beatNumber,
            barNumber = barNumber,
            currentNoteIndex = currentIndex,
            currentNote = current,
            nextNote = next,
            beatProgress = beatFraction,
            patternProgress = progress.coerceIn(0f, 1f),
            bpm = bpm,
            isOnBeatWindow = beatProgress <= PatternScheduler.BEAT_EPSILON ||
                beatProgress >= 1.0f - PatternScheduler.BEAT_EPSILON,
            countdownRemaining = countdownRemaining
        )
    }
}