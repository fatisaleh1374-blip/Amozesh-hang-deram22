package com.example.audio

import com.example.model.Subdivision

/** Shared musical-time conversions used by playback, metronome, and evaluation. */
object MusicalTiming {
    fun beatDurationNanos(bpm: Int): Long {
        return (60_000_000_000L / bpm.coerceIn(40, 300).toLong())
    }

    fun subdivisionDurationNanos(bpm: Int, subdivision: Subdivision): Long {
        return beatDurationNanos(bpm) / subdivision.divisionsPerBeat
    }

    fun beatToNanos(beatPosition: Double, bpm: Int): Long {
        return (beatPosition * beatDurationNanos(bpm)).toLong()
    }

    fun tickIndexToNanos(tickIndex: Long, bpm: Int, subdivision: Subdivision): Long {
        return tickIndex * subdivisionDurationNanos(bpm, subdivision)
    }
}
