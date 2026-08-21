package com.example.audio

import com.example.model.Subdivision
import com.example.model.TimeSignature
import com.example.util.HapticHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MetronomeState(
    val isPlaying: Boolean = false,
    val bpm: Int = 70,
    val timeSignature: TimeSignature = TimeSignature.Common44,
    val subdivision: Subdivision = Subdivision.QUARTER,
    val currentBeat: Int = 1,          // 1-indexed (e.g. 1..4)
    val currentSubBeat: Int = 1,       // 1-indexed for subdivision
    val isDownbeat: Boolean = false,
    val accentFirstBeat: Boolean = true,
    val isMuted: Boolean = false,
    val hapticEnabled: Boolean = true
)

class MetronomeEngine(
    private val audioEngine: AudioEngine,
    private val hapticHelper: HapticHelper? = null
) {
    private val _state = MutableStateFlow(MetronomeState())
    val state: StateFlow<MetronomeState> = _state.asStateFlow()

    private var metronomeJob: Job? = null
    private val tapTimes = mutableListOf<Long>()

    fun togglePlay() {
        if (_state.value.isPlaying) {
            stop()
        } else {
            start()
        }
    }

    fun start() {
        if (_state.value.isPlaying) return
        _state.update { it.copy(isPlaying = true, currentBeat = 1, currentSubBeat = 1) }

        metronomeJob = CoroutineScope(Dispatchers.Default).launch {
            runMetronomeLoop()
        }
    }

    fun stop() {
        metronomeJob?.cancel()
        metronomeJob = null
        _state.update { it.copy(isPlaying = false, currentBeat = 1, currentSubBeat = 1, isDownbeat = false) }
    }

    fun release() {
        stop()
    }

    private suspend fun runMetronomeLoop() {
        var tickIndex = 0L
        val startTimeNanos = System.nanoTime()

        while (true) {
            val currentState = _state.value
            val beatsPerBar = currentState.timeSignature.beatsPerBar
            val divsPerBeat = currentState.subdivision.divisionsPerBeat

            val totalTicksPerBar = beatsPerBar * divsPerBeat
            val tickInBar = (tickIndex % totalTicksPerBar).toInt()

            val currentBeatIndex = (tickInBar / divsPerBeat) + 1
            val currentSubIndex = (tickInBar % divsPerBeat) + 1
            val isDownbeat = (tickInBar == 0)
            val isMainBeat = (currentSubIndex == 1)

            // Play audio
            val shouldAccent = isDownbeat && currentState.accentFirstBeat
            if (isMainBeat) {
                audioEngine.playMetronomeClick(isAccent = shouldAccent)
            } else {
                // Subtle sub-click
                audioEngine.playMetronomeClick(isAccent = false)
            }

            // Haptic
            if (currentState.hapticEnabled && isMainBeat) {
                hapticHelper?.performClick(isAccent = shouldAccent)
            }

            // Update UI State
            _state.update {
                it.copy(
                    currentBeat = currentBeatIndex,
                    currentSubBeat = currentSubIndex,
                    isDownbeat = isDownbeat
                )
            }

            tickIndex++

            // Calculate precise target timestamp in nanoseconds
            val intervalNanos = calculateTickIntervalNanos(currentState.bpm, divsPerBeat)
            val nextTargetNanos = startTimeNanos + (tickIndex * intervalNanos)
            val currentNanos = System.nanoTime()
            val nanosToWait = nextTargetNanos - currentNanos

            if (nanosToWait > 0) {
                val millis = nanosToWait / 1_000_000L
                val remainingNanos = (nanosToWait % 1_000_000L).toInt()
                if (millis > 0) {
                    delay(millis)
                }
            }
        }
    }

    fun setBpm(newBpm: Int) {
        val clamped = newBpm.coerceIn(40, 240)
        _state.update { it.copy(bpm = clamped) }
    }

    fun setTimeSignature(timeSignature: TimeSignature) {
        _state.update { it.copy(timeSignature = timeSignature) }
    }

    fun setSubdivision(subdivision: Subdivision) {
        _state.update { it.copy(subdivision = subdivision) }
    }

    fun setAccentFirstBeat(accent: Boolean) {
        _state.update { it.copy(accentFirstBeat = accent) }
    }

    fun setHapticEnabled(enabled: Boolean) {
        _state.update { it.copy(hapticEnabled = enabled) }
    }

    fun tapTempo() {
        val now = System.currentTimeMillis()
        tapTimes.add(now)
        // Keep last 4 taps within 3 seconds
        tapTimes.removeAll { now - it > 3000 }

        if (tapTimes.size >= 2) {
            val intervals = mutableListOf<Long>()
            for (i in 1 until tapTimes.size) {
                intervals.add(tapTimes[i] - tapTimes[i - 1])
            }
            val avgIntervalMs = intervals.average()
            if (avgIntervalMs > 0) {
                val calculatedBpm = (60000.0 / avgIntervalMs).toInt().coerceIn(40, 240)
                setBpm(calculatedBpm)
            }
        }
    }

    companion object {
        fun calculateTickIntervalNanos(bpm: Int, subdivisionDivisions: Int): Long {
            val beatIntervalNanos = (60.0 / bpm.toDouble()) * 1_000_000_000.0
            return (beatIntervalNanos / subdivisionDivisions.toDouble()).toLong()
        }

        fun calculateBeatIntervalMs(bpm: Int): Long {
            return (60_000.0 / bpm.toDouble()).toLong()
        }
    }
}
