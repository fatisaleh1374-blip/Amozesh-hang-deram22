package com.example.audio

import com.example.model.HandpanPattern
import com.example.model.NoteEvent
import com.example.model.NotePitchConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

enum class StrikeAccuracyStatus {
    PERFECT,    // ±45ms + correct pitch
    GOOD,       // ±90ms + correct pitch
    EARLY,      // -160ms to -90ms
    LATE,       // +90ms to +160ms
    WRONG_NOTE, // correct timing window but incorrect note
    MISSED      // no strike detected within window
}

data class StrikeFeedback(
    val status: StrikeAccuracyStatus,
    val deviationMs: Long,
    val expectedNotes: List<Int>,
    val detectedNote: Int?,
    val detectedFreqHz: Float,
    val monotonicTimestampNanos: Long
)

data class AcousticAssessmentState(
    val isEnabled: Boolean = false,
    val isListening: Boolean = false,
    val liveFrequencyHz: Float = 0f,
    val liveNoteName: String = "--",
    val liveCentsOffset: Int = 0,
    val liveDetectedScaleNote: Int? = null,
    val lastFeedback: StrikeFeedback? = null,
    val totalExpectedNotes: Int = 0,
    val totalStrikesEvaluated: Int = 0,
    val perfectCount: Int = 0,
    val goodCount: Int = 0,
    val earlyCount: Int = 0,
    val lateCount: Int = 0,
    val wrongNoteCount: Int = 0,
    val missedCount: Int = 0,
    // Separate Metrics
    val timingAccuracyPercentage: Float = 100f,
    val noteAccuracyPercentage: Float = 100f,
    val accuracyPercentage: Float = 100f, // Combined overall
    val averageTimingDeviationMs: Float = 0f,
    val isSummaryDialogVisible: Boolean = false
) {
    val isActive: Boolean
        get() = isEnabled && isListening

    val totalHits: Int
        get() = perfectCount + goodCount + earlyCount + lateCount + wrongNoteCount

    val starRating: Int
        get() = when {
            accuracyPercentage >= 90f -> 3
            accuracyPercentage >= 70f -> 2
            accuracyPercentage >= 50f -> 1
            else -> 0
        }
}

/**
 * Real-time evaluator that listens to the user's acoustic handpan playing via the microphone,
 * compares strike timing and pitch against the practice pattern using a unified monotonic PracticeClock,
 * and computes separate timing vs note accuracy metrics.
 */
class AcousticPracticeEvaluator(
    private val clock: PracticeClock = PracticeClock.Default,
    private val pitchDetector: PitchDetector = PitchDetector()
) {
    private val _state = MutableStateFlow(AcousticAssessmentState())
    val state: StateFlow<AcousticAssessmentState> = _state.asStateFlow()

    private var activePattern: HandpanPattern? = null
    private var scaleConfig: NotePitchConfig = NotePitchConfig()
    private val timingWindowList = mutableListOf<Long>()
    private var assessmentStartTimestampNanos: Long = 0L

    // Current expected target note events and monotonic window timestamp in nanoseconds
    @Volatile
    private var expectedNoteEvents: List<NoteEvent> = emptyList()
    @Volatile
    private var expectedBeatTargetTimestampNanos: Long = 0L
    @Volatile
    private var strikeProcessedForCurrentBeat: Boolean = false

    fun setScaleConfig(config: NotePitchConfig) {
        this.scaleConfig = config
    }

    fun startAssessment(pattern: HandpanPattern, scaleConfig: NotePitchConfig) {
        this.activePattern = pattern
        this.scaleConfig = scaleConfig
        resetStats()

        assessmentStartTimestampNanos = clock.nowNanos()
        val firstEvents = pattern.events.filter { !it.isRest }
        if (firstEvents.isNotEmpty()) {
            val firstBeat = firstEvents.first().beatPosition
            expectedNoteEvents = firstEvents.filter { it.beatPosition == firstBeat }
            expectedBeatTargetTimestampNanos = assessmentStartTimestampNanos
        }

        _state.update {
            it.copy(
                isEnabled = true,
                isListening = true,
                isSummaryDialogVisible = false
            )
        }

        pitchDetector.startListening(
            scaleConfig = scaleConfig,
            onContinuousPitch = { pitch ->
                _state.update {
                    it.copy(
                        liveFrequencyHz = pitch.frequencyHz,
                        liveNoteName = pitch.noteName,
                        liveCentsOffset = pitch.centsOffset,
                        liveDetectedScaleNote = pitch.matchedNoteNumber
                    )
                }
            },
            onStrikeDetected = { pitch, monotonicTimestampNanos ->
                handleStrikeDetected(pitch, monotonicTimestampNanos)
            }
        )
    }

    /**
     * Directly evaluates a detected pitch against the active expected pattern notes.
     * Used for real-time DSP evaluation pipelines and automated verification test suites.
     */
    fun evaluateDetectedPitch(
        frequencyHz: Float,
        confidence: Float = 0.9f,
        timestampNanos: Long = clock.nowNanos()
    ): StrikeFeedback? {
        if (confidence < 0.5f) {
            return null
        }

        val matcher = OnsetAndPitchMatcher(22050)
        val (matchedNote, centsDev) = matcher.matchToScaleByCents(frequencyHz, scaleConfig)
        val (noteName, cents) = YinPitchDetector.frequencyToNoteAndCents(frequencyHz)

        val pitchResult = DetectedPitchResult(
            frequencyHz = frequencyHz,
            noteName = noteName,
            centsOffset = cents,
            amplitude = 0.85f,
            matchedNoteNumber = matchedNote,
            matchedPitchDiffHz = centsDev,
            confidence = confidence
        )

        handleStrikeDetected(pitchResult, timestampNanos)
        return _state.value.lastFeedback
    }

    fun stopAssessment(showSummary: Boolean = true) {
        pitchDetector.stopListening()
        _state.update {
            it.copy(
                isEnabled = false,
                isListening = false,
                isSummaryDialogVisible = showSummary && it.totalStrikesEvaluated > 0
            )
        }
    }

    fun toggleEnabled() {
        val willEnable = !_state.value.isEnabled
        _state.update { it.copy(isEnabled = willEnable) }
        if (!willEnable) {
            pitchDetector.stopListening()
            _state.update { it.copy(isListening = false) }
        }
    }

    fun dismissSummary() {
        _state.update { it.copy(isSummaryDialogVisible = false) }
    }

    fun resetStats() {
        timingWindowList.clear()
        expectedNoteEvents = emptyList()
        expectedBeatTargetTimestampNanos = 0L
        strikeProcessedForCurrentBeat = false

        _state.update {
            it.copy(
                totalExpectedNotes = 0,
                totalStrikesEvaluated = 0,
                perfectCount = 0,
                goodCount = 0,
                earlyCount = 0,
                lateCount = 0,
                wrongNoteCount = 0,
                missedCount = 0,
                timingAccuracyPercentage = 100f,
                noteAccuracyPercentage = 100f,
                accuracyPercentage = 100f,
                averageTimingDeviationMs = 0f,
                lastFeedback = null,
                isSummaryDialogVisible = false
            )
        }
    }

    /**
     * Called by PracticeEngine on each slice with monotonic timestamp in nanoseconds.
     */
    fun notifyExpectedSlice(events: List<NoteEvent>, targetTimestampNanos: Long) {
        if (!_state.value.isEnabled || !_state.value.isListening) return

        val activeEvents = events.filter { !it.isRest }

        // Check if previous note(s) were missed
        if (expectedNoteEvents.isNotEmpty() && !strikeProcessedForCurrentBeat) {
            registerMissedNote(expectedNoteEvents.map { it.noteNumber })
        }

        expectedNoteEvents = activeEvents
        expectedBeatTargetTimestampNanos = targetTimestampNanos
        strikeProcessedForCurrentBeat = false

        if (activeEvents.isNotEmpty()) {
            _state.update { it.copy(totalExpectedNotes = it.totalExpectedNotes + activeEvents.size) }
        }
    }

    private fun handleStrikeDetected(pitch: DetectedPitchResult, strikeTimestampNanos: Long) {
        if (!_state.value.isEnabled && !_state.value.isListening) return

        var targetNanos = expectedBeatTargetTimestampNanos
        var currentExpected = expectedNoteEvents

        val pattern = activePattern
        if (pattern != null && pattern.events.isNotEmpty()) {
            val beatNanos = 500_000_000L // 500ms nominal beat interval
            val activeEvents = pattern.events.filter { !it.isRest }

            var bestEvent: NoteEvent? = null
            var minDeltaNanos = Long.MAX_VALUE
            var bestTargetNanos = targetNanos

            for (evt in activeEvents) {
                val candidateTargetNanos = assessmentStartTimestampNanos + (evt.beatPosition * beatNanos).toLong()
                val delta = abs(strikeTimestampNanos - candidateTargetNanos)
                if (delta < minDeltaNanos) {
                    minDeltaNanos = delta
                    bestEvent = evt
                    bestTargetNanos = candidateTargetNanos
                }
            }

            if (bestEvent != null && minDeltaNanos <= 250_000_000L) {
                targetNanos = bestTargetNanos
                currentExpected = listOf(bestEvent)
            }
        }

        if (currentExpected.isEmpty()) return

        val deviationNanos = strikeTimestampNanos - targetNanos
        val deviationMs = deviationNanos / 1_000_000L

        // If strike occurred way outside reasonable beat window (> 250ms), ignore as noise or stray tap
        if (abs(deviationMs) > 250L) return

        val expectedNoteNumbers = currentExpected.map { it.noteNumber }
        val isPitchMatch = pitch.matchedNoteNumber != null && expectedNoteNumbers.contains(pitch.matchedNoteNumber)

        val status = when {
            !isPitchMatch -> StrikeAccuracyStatus.WRONG_NOTE
            abs(deviationMs) <= 45L -> StrikeAccuracyStatus.PERFECT
            abs(deviationMs) <= 95L -> StrikeAccuracyStatus.GOOD
            deviationMs < -95L -> StrikeAccuracyStatus.EARLY
            else -> StrikeAccuracyStatus.LATE
        }

        strikeProcessedForCurrentBeat = true
        timingWindowList.add(abs(deviationMs))

        val feedback = StrikeFeedback(
            status = status,
            deviationMs = deviationMs,
            expectedNotes = expectedNoteNumbers,
            detectedNote = pitch.matchedNoteNumber,
            detectedFreqHz = pitch.frequencyHz,
            monotonicTimestampNanos = strikeTimestampNanos
        )

        updateStatsWithFeedback(feedback, isPitchMatch, abs(deviationMs))
    }

    private fun registerMissedNote(expectedNotes: List<Int>) {
        val feedback = StrikeFeedback(
            status = StrikeAccuracyStatus.MISSED,
            deviationMs = 0L,
            expectedNotes = expectedNotes,
            detectedNote = null,
            detectedFreqHz = 0f,
            monotonicTimestampNanos = clock.nowNanos()
        )
        _state.update {
            val newMissed = it.missedCount + expectedNotes.size
            val newTotal = it.totalStrikesEvaluated + expectedNotes.size

            val timingScore = (it.perfectCount * 100) + (it.goodCount * 80) + (it.earlyCount * 50) + (it.lateCount * 50)
            val timingAcc = (timingScore.toFloat() / (newTotal * 100f).coerceAtLeast(1f)) * 100f

            val noteHits = it.perfectCount + it.goodCount + it.earlyCount + it.lateCount
            val noteAcc = (noteHits.toFloat() / (newTotal).coerceAtLeast(1).toFloat()) * 100f

            val overall = (timingAcc * 0.5f + noteAcc * 0.5f).coerceIn(0f, 100f)

            it.copy(
                missedCount = newMissed,
                totalStrikesEvaluated = newTotal,
                timingAccuracyPercentage = timingAcc.coerceIn(0f, 100f),
                noteAccuracyPercentage = noteAcc.coerceIn(0f, 100f),
                accuracyPercentage = overall,
                lastFeedback = feedback
            )
        }
    }

    private fun updateStatsWithFeedback(feedback: StrikeFeedback, isPitchMatch: Boolean, absDeviation: Long) {
        _state.update { current ->
            var perfect = current.perfectCount
            var good = current.goodCount
            var early = current.earlyCount
            var late = current.lateCount
            var wrong = current.wrongNoteCount

            when (feedback.status) {
                StrikeAccuracyStatus.PERFECT -> perfect++
                StrikeAccuracyStatus.GOOD -> good++
                StrikeAccuracyStatus.EARLY -> early++
                StrikeAccuracyStatus.LATE -> late++
                StrikeAccuracyStatus.WRONG_NOTE -> wrong++
                StrikeAccuracyStatus.MISSED -> {}
            }

            val totalEvaluated = perfect + good + early + late + wrong + current.missedCount
            val timingScore = (perfect * 100) + (good * 80) + (early * 50) + (late * 50)
            val timingAcc = (timingScore.toFloat() / (totalEvaluated * 100f).coerceAtLeast(1f)) * 100f

            val noteHits = perfect + good + early + late
            val noteAcc = (noteHits.toFloat() / totalEvaluated.coerceAtLeast(1).toFloat()) * 100f
            val overall = (timingAcc * 0.5f + noteAcc * 0.5f).coerceIn(0f, 100f)

            val avgDeviation = if (timingWindowList.isNotEmpty()) {
                timingWindowList.average().toFloat()
            } else 0f

            current.copy(
                totalStrikesEvaluated = totalEvaluated,
                perfectCount = perfect,
                goodCount = good,
                earlyCount = early,
                lateCount = late,
                wrongNoteCount = wrong,
                timingAccuracyPercentage = timingAcc.coerceIn(0f, 100f),
                noteAccuracyPercentage = noteAcc.coerceIn(0f, 100f),
                accuracyPercentage = overall,
                averageTimingDeviationMs = avgDeviation,
                lastFeedback = feedback
            )
        }
    }
}
