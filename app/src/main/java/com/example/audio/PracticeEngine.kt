package com.example.audio

import com.example.model.HandpanPattern
import com.example.model.NoteEvent
import com.example.model.PracticeInputMode
import com.example.model.PracticeMode
import com.example.util.HapticHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PracticeUiState(
    val pattern: HandpanPattern? = null,
    val isPlaying: Boolean = false,
    val isCountIn: Boolean = false,
    val countInBeat: Int = 1,
    val currentBar: Int = 1,
    val currentBeatInBar: Double = 1.0,
    val currentBeatAbsolute: Double = 0.0,
    val currentNoteIndex: Int = -1,
    val activeEvents: List<NoteEvent> = emptyList(),
    val activeNoteEvent: NoteEvent? = null,
    val activeNoteNumber: Int = -1,
    val bpm: Int = 70,
    val speedMultiplier: Float = 1.0f,
    val isLoopEnabled: Boolean = true,
    val loopStartBar: Int = 1,
    val loopEndBar: Int = 1,
    val mode: PracticeMode = PracticeMode.FOLLOW,
    val inputMode: PracticeInputMode = PracticeInputMode.REAL_HANDPAN,
    val metronomeEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val countInEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
    val totalRoundsCompleted: Int = 0,
    // Speed Ladder
    val speedLadderEnabled: Boolean = false,
    val ladderBpmIncrement: Int = 4,
    val ladderRoundsPerStep: Int = 2,
    val ladderTargetBpm: Int = 110,
    val isStandModeFullscreen: Boolean = false,
    val acousticAssessmentEnabled: Boolean = true
) {
    val effectiveBpm: Int
        get() = (bpm * speedMultiplier).toInt().coerceIn(30, 300)
}

class PracticeEngine(
    private val audioEngine: AudioEngine,
    private val hapticHelper: HapticHelper? = null,
    private val clock: PracticeClock = PracticeClock.Default,
    val acousticEvaluator: AcousticPracticeEvaluator = AcousticPracticeEvaluator(clock = clock)
) {
    private val engineJob = SupervisorJob()
    private val engineScope = CoroutineScope(Dispatchers.Default + engineJob)

    private val _uiState = MutableStateFlow(PracticeUiState())
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    private var playbackJob: Job? = null
    private var sessionStartedNanos: Long = 0L

    var onRoundCompleted: ((HandpanPattern, Int, Int) -> Unit)? = null

    fun loadPattern(pattern: HandpanPattern) {
        stop()
        _uiState.update {
            it.copy(
                pattern = pattern,
                bpm = pattern.bpm,
                currentBar = 1,
                currentBeatInBar = 1.0,
                currentBeatAbsolute = 0.0,
                currentNoteIndex = -1,
                activeEvents = emptyList(),
                activeNoteEvent = null,
                activeNoteNumber = -1,
                loopStartBar = 1,
                loopEndBar = pattern.bars,
                totalRoundsCompleted = 0
            )
        }
    }

    fun togglePlay() {
        if (_uiState.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        val pattern = _uiState.value.pattern ?: return
        if (_uiState.value.isPlaying) return

        _uiState.update { it.copy(isPlaying = true) }
        sessionStartedNanos = clock.nowNanos()

        if (acousticEvaluator.state.value.isEnabled) {
            acousticEvaluator.startAssessment(
                pattern = pattern,
                scaleConfig = audioEngine.getPitchConfig(),
                bpm = _uiState.value.effectiveBpm
            )
        }

        playbackJob = engineScope.launch {
            runPracticeLoop(pattern)
        }
    }

    fun pause() {
        playbackJob?.cancel()
        playbackJob = null
        if (acousticEvaluator.state.value.isEnabled) {
            acousticEvaluator.stopAssessment(showSummary = true)
        }
        _uiState.update {
            it.copy(
                isPlaying = false,
                isCountIn = false,
                activeNoteNumber = -1,
                activeEvents = emptyList(),
                activeNoteEvent = null
            )
        }
    }

    fun stop() {
        pause()
        _uiState.update {
            it.copy(
                currentBar = 1,
                currentBeatInBar = 1.0,
                currentBeatAbsolute = 0.0,
                currentNoteIndex = -1,
                activeEvents = emptyList(),
                activeNoteEvent = null,
                activeNoteNumber = -1
            )
        }
    }

    fun restart() {
        stop()
        play()
    }

    fun release() {
        stop()
        engineJob.cancel()
    }

    private suspend fun runPracticeLoop(pattern: HandpanPattern) {
        val beatsPerBar = pattern.timeSignature.beatsPerBar

        // 1. Count-in with unified monotonic PracticeClock
        if (_uiState.value.countInEnabled) {
            _uiState.update { it.copy(isCountIn = true, countInBeat = 1) }

            val countInStartNanos = clock.nowNanos()
            val effectiveBpm = _uiState.value.effectiveBpm
            val beatIntervalNanos = MusicalTiming.beatDurationNanos(effectiveBpm)

            for (c in 1..beatsPerBar) {
                _uiState.update { it.copy(countInBeat = c) }
                val isFirst = (c == 1)

                audioEngine.playMetronomeClick(isAccent = isFirst)
                triggerHaptic(isAccent = isFirst)

                val nextTargetNanos = countInStartNanos + (c * beatIntervalNanos).toLong()
                val remainingNanos = nextTargetNanos - clock.nowNanos()
                if (remainingNanos > 0) {
                    delay(remainingNanos / 1_000_000L)
                }
            }

            _uiState.update { it.copy(isCountIn = false) }
        }

        // 2. Main Practice Execution using pre-indexed slices
        var currentLoopIteration = 0

        while (playbackJob?.isActive == true) {
            val currentState = _uiState.value
            val currentBpm = currentState.effectiveBpm
            val startBar = if (currentState.isLoopEnabled) currentState.loopStartBar else 1
            val endBar = if (currentState.isLoopEnabled) currentState.loopEndBar else pattern.bars

            // Build lookahead pre-indexed schedule slices for the current bar loop range
            val schedule = PatternScheduler.buildSchedule(
                events = pattern.events,
                beatsPerBar = beatsPerBar,
                totalBars = pattern.bars,
                startBar = startBar,
                endBar = endBar,
                timeSignature = pattern.timeSignature
            )

            if (schedule.isEmpty()) {
                delay(100)
                continue
            }

            val loopStartBeat = ((startBar - 1) * beatsPerBar).toDouble()
            val loopStartNanos = clock.nowNanos()

            for (slice in schedule) {
                val sliceOffsetBeats = slice.beatPosition - loopStartBeat
                val targetSliceNanos = loopStartNanos +
                    MusicalTiming.beatToNanos(sliceOffsetBeats, currentBpm, pattern.timeSignature)

                // Wait until monotonic timestamp for this slice
                val waitNanos = targetSliceNanos - clock.nowNanos()
                if (waitNanos > 0) {
                    delay(waitNanos / 1_000_000L)
                }

                if (playbackJob?.isActive != true) break

                // Metronome click on whole beats / downbeats
                if (currentState.metronomeEnabled && slice.isDownbeat) {
                    audioEngine.playMetronomeClick(isAccent = slice.beatInBar <= 1.05)
                }

                // Play all events in slice (supports multiple simultaneous notes or single hits)
                val nonRestEvents = slice.events.filter { !it.isRest }
                // In REAL_HANDPAN mode, virtual handpan notes MUST be MUTED to prevent speaker feedback loop into the microphone
                val isVirtualAudioMutedForRealHandpan = (currentState.inputMode == PracticeInputMode.REAL_HANDPAN)
                if (nonRestEvents.isNotEmpty() && currentState.soundEnabled && !isVirtualAudioMutedForRealHandpan) {
                    // Practice mode check: in Challenge mode, mute audio so user tests muscle memory
                    if (currentState.mode != PracticeMode.CHALLENGE) {
                        for (ev in nonRestEvents) {
                            audioEngine.playNote(
                                noteNumber = ev.noteNumber,
                                accent = ev.accent,
                                velocity = ev.velocity
                            )
                        }
                    }
                }

                // Haptic feedback
                if (nonRestEvents.isNotEmpty()) {
                    triggerHaptic(isAccent = nonRestEvents.any { it.accent } || slice.isDownbeat)
                } else if (currentState.metronomeEnabled && slice.isDownbeat) {
                    triggerHaptic(isAccent = true)
                }

                val primaryEvent = slice.events.firstOrNull()
                val noteIdx = if (primaryEvent != null) pattern.events.indexOf(primaryEvent) else -1

                // Update UI state for visual tracking
                _uiState.update {
                    it.copy(
                        currentBar = slice.barIndex,
                        currentBeatInBar = slice.beatInBar,
                        currentBeatAbsolute = slice.beatPosition,
                        currentNoteIndex = noteIdx,
                        activeEvents = slice.events,
                        activeNoteEvent = primaryEvent,
                        activeNoteNumber = if (primaryEvent != null && !primaryEvent.isRest) primaryEvent.noteNumber else -1
                    )
                }

                // Notify Acoustic Live Evaluator with unified monotonic timestamp
                if (acousticEvaluator.state.value.isEnabled) {
                    acousticEvaluator.notifyExpectedSlice(slice.events, targetSliceNanos)
                }
            }

            currentLoopIteration++
            _uiState.update { it.copy(totalRoundsCompleted = currentLoopIteration) }
            onRoundCompleted?.invoke(
                pattern,
                currentState.effectiveBpm,
                ((clock.nowNanos() - sessionStartedNanos) / 1_000_000_000L).toInt().coerceAtLeast(0)
            )

            // Speed Ladder Progression
            if (currentState.speedLadderEnabled && currentLoopIteration > 0 &&
                currentLoopIteration % currentState.ladderRoundsPerStep == 0
            ) {
                val nextBpm = (currentState.bpm + currentState.ladderBpmIncrement)
                    .coerceAtMost(currentState.ladderTargetBpm)
                if (nextBpm != currentState.bpm) {
                    _uiState.update { it.copy(bpm = nextBpm) }
                }
            }

            if (!currentState.isLoopEnabled && endBar == pattern.bars) {
                stop()
                break
            }
        }
    }

    private fun triggerHaptic(isAccent: Boolean) {
        if (!_uiState.value.hapticEnabled) return
        hapticHelper?.performClick(isAccent = isAccent)
    }

    fun setBpm(bpm: Int) {
        _uiState.update { it.copy(bpm = bpm.coerceIn(40, 240)) }
    }

    fun setSpeedMultiplier(multiplier: Float) {
        _uiState.update { it.copy(speedMultiplier = multiplier) }
    }

    fun setPracticeMode(mode: PracticeMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    fun toggleMetronome() {
        _uiState.update { it.copy(metronomeEnabled = !it.metronomeEnabled) }
    }

    fun toggleSound() {
        _uiState.update { it.copy(soundEnabled = !it.soundEnabled) }
    }

    fun toggleCountIn() {
        _uiState.update { it.copy(countInEnabled = !it.countInEnabled) }
    }

    fun toggleLoop() {
        _uiState.update { it.copy(isLoopEnabled = !it.isLoopEnabled) }
    }

    fun toggleSpeedLadder() {
        _uiState.update { it.copy(speedLadderEnabled = !it.speedLadderEnabled) }
    }

    fun configureSpeedLadder(increment: Int, roundsPerStep: Int, targetBpm: Int) {
        _uiState.update {
            it.copy(
                ladderBpmIncrement = increment.coerceIn(1, 20),
                ladderRoundsPerStep = roundsPerStep.coerceIn(1, 10),
                ladderTargetBpm = targetBpm.coerceIn(50, 240)
            )
        }
    }

    fun toggleStandMode() {
        _uiState.update { it.copy(isStandModeFullscreen = !it.isStandModeFullscreen) }
    }

    fun toggleAcousticAssessment() {
        val next = !_uiState.value.acousticAssessmentEnabled
        _uiState.update { it.copy(acousticAssessmentEnabled = next) }
        acousticEvaluator.toggleEnabled()
        if (next && _uiState.value.isPlaying) {
            _uiState.value.pattern?.let {
                acousticEvaluator.startAssessment(it, audioEngine.getPitchConfig(), _uiState.value.effectiveBpm)
            }
        }
    }

    fun setAcousticAssessmentEnabled(enabled: Boolean) {
        _uiState.update { it.copy(acousticAssessmentEnabled = enabled) }
        if (enabled) {
            if (!_uiState.value.isPlaying) {
                acousticEvaluator.toggleEnabled()
            } else {
                _uiState.value.pattern?.let {
                acousticEvaluator.startAssessment(it, audioEngine.getPitchConfig(), _uiState.value.effectiveBpm)
                }
            }
        } else {
            acousticEvaluator.stopAssessment(showSummary = false)
        }
    }

    fun setInputMode(mode: PracticeInputMode) {
        _uiState.update { it.copy(inputMode = mode) }
        if (mode == PracticeInputMode.REAL_HANDPAN) {
            setAcousticAssessmentEnabled(true)
        } else {
            setAcousticAssessmentEnabled(false)
        }
    }

    fun setLoopRange(startBar: Int, endBar: Int) {
        val totalBars = _uiState.value.pattern?.bars ?: 1
        val s = startBar.coerceIn(1, totalBars)
        val e = endBar.coerceIn(s, totalBars)
        _uiState.update { it.copy(loopStartBar = s, loopEndBar = e) }
    }
}
