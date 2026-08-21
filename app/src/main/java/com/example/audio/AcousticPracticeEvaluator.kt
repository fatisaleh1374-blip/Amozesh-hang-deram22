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
    EXTRA_STRIKE,
    MISSED      // no strike detected within window
}

enum class TimingAccuracyStatus {
    PERFECT,
    GOOD,
    EARLY,
    LATE,
    MISSED,
    UNKNOWN
}

enum class ExpectedEventLifecycle {
    EXPECTED,
    WAITING,
    MATCHED,
    MISSED,
    EXPIRED
}

data class TimingProfile(
    val perfectMs: Long = 45L,
    val goodMs: Long = 90L,
    val missMs: Long = 160L,
    val ignoreAfterMs: Long = 250L
)

data class StrikeFeedback(
    val status: StrikeAccuracyStatus,
    val timingStatus: TimingAccuracyStatus,
    val deviationMs: Long,
    val expectedNotes: List<Int>,
    val detectedNote: Int?,
    val detectedFreqHz: Float,
    val detectedCentsOffset: Int,
    val confidence: Float,
    val expectedTimestampNanos: Long,
    val monotonicTimestampNanos: Long,
    val noteCorrect: Boolean
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
    val wrongNoteTimingPoints: Int = 0,
    val extraStrikeCount: Int = 0,
    val missedCount: Int = 0,
    // Separate Metrics
    val timingAccuracyPercentage: Float = 100f,
    val noteAccuracyPercentage: Float = 100f,
    val accuracyPercentage: Float = 100f, // Combined overall
    val averageTimingDeviationMs: Float = 0f,
    val consistencyPercentage: Float = 100f,
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
    private var beatDurationNanos: Long = 500_000_000L
    private var timingProfile = TimingProfile()

    // Current expected target note events and monotonic window timestamp in nanoseconds
    @Volatile
    private var expectedNoteEvents: List<NoteEvent> = emptyList()
    @Volatile
    private var expectedBeatTargetTimestampNanos: Long = 0L
    @Volatile
    private var strikeProcessedForCurrentBeat: Boolean = false
    private data class PendingExpectedEvent(
        val events: List<NoteEvent>,
        val targetTimestampNanos: Long,
        val remainingEvents: MutableList<NoteEvent> = events.toMutableList(),
        var lifecycle: ExpectedEventLifecycle = ExpectedEventLifecycle.EXPECTED
    )
    private val pendingExpectedEvents = mutableListOf<PendingExpectedEvent>()

    var onStrikeDetected: ((DetectedPitchResult, Long) -> Unit)? = null

    fun setScaleConfig(config: NotePitchConfig) {
        this.scaleConfig = config
    }

    fun setTimingProfile(profile: TimingProfile) {
        require(profile.perfectMs >= 0L)
        require(profile.goodMs >= profile.perfectMs)
        require(profile.missMs >= profile.goodMs)
        require(profile.ignoreAfterMs >= profile.missMs)
        timingProfile = profile
    }

    fun startAssessment(pattern: HandpanPattern, scaleConfig: NotePitchConfig, bpm: Int = pattern.bpm) {
        this.activePattern = pattern
        this.scaleConfig = scaleConfig
        beatDurationNanos = MusicalTiming.beatDurationNanos(bpm)
        resetStats()

        assessmentStartTimestampNanos = clock.nowNanos()
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
        // Ending a session closes every still-pending target; there will be no later strike
        // or expected slice to advance the evaluator past its miss window.
        finalizePendingEvents()
        pitchDetector.stopListening()
        _state.update {
            it.copy(
                isEnabled = false,
                isListening = false,
                isSummaryDialogVisible = showSummary && it.totalStrikesEvaluated > 0
            )
        }
    }

    fun release() {
        pitchDetector.release()
        onStrikeDetected = null
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
        pendingExpectedEvents.clear()

        _state.update {
            it.copy(
                totalExpectedNotes = 0,
                totalStrikesEvaluated = 0,
                perfectCount = 0,
                goodCount = 0,
                earlyCount = 0,
                lateCount = 0,
                wrongNoteCount = 0,
                wrongNoteTimingPoints = 0,
                extraStrikeCount = 0,
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

        expirePendingEvents(targetTimestampNanos)
        if (activeEvents.isNotEmpty()) {
            pendingExpectedEvents += PendingExpectedEvent(activeEvents, targetTimestampNanos)
            expectedNoteEvents = activeEvents
            expectedBeatTargetTimestampNanos = targetTimestampNanos
            strikeProcessedForCurrentBeat = false
        }

        if (activeEvents.isNotEmpty()) {
            _state.update { it.copy(totalExpectedNotes = it.totalExpectedNotes + activeEvents.size) }
        }
    }

    private fun handleStrikeDetected(pitch: DetectedPitchResult, strikeTimestampNanos: Long) {
        if (!_state.value.isEnabled || !_state.value.isListening) return

        onStrikeDetected?.invoke(pitch, strikeTimestampNanos)

        var targetNanos = expectedBeatTargetTimestampNanos
        var currentExpected = expectedNoteEvents
        val pendingMatch = pendingExpectedEvents
            .asSequence()
            .filter {
                (it.lifecycle == ExpectedEventLifecycle.EXPECTED || it.lifecycle == ExpectedEventLifecycle.WAITING) &&
                    it.remainingEvents.isNotEmpty()
            }
            .filter {
                abs(strikeTimestampNanos - it.targetTimestampNanos) <= timingProfile.ignoreAfterMs * 1_000_000L
            }
            .sortedWith(compareBy<PendingExpectedEvent> {
                abs(strikeTimestampNanos - it.targetTimestampNanos)
            }.thenBy { it.targetTimestampNanos })
            .firstOrNull()

        val isWithinTargetWindow = pendingMatch != null &&
            abs(strikeTimestampNanos - pendingMatch.targetTimestampNanos) <= timingProfile.ignoreAfterMs * 1_000_000L

        if (isWithinTargetWindow) {
            pendingMatch.lifecycle = ExpectedEventLifecycle.WAITING
            targetNanos = pendingMatch.targetTimestampNanos
            currentExpected = pendingMatch.remainingEvents
        }

        if (!isWithinTargetWindow || currentExpected.isEmpty()) {
            registerExtraStrike(pitch, strikeTimestampNanos)
            return
        }

        val deviationNanos = strikeTimestampNanos - targetNanos
        val deviationMs = deviationNanos / 1_000_000L

        // If strike occurred way outside reasonable beat window (> 250ms), ignore as noise or stray tap
        if (abs(deviationMs) > timingProfile.ignoreAfterMs) return

        val expectedNoteNumbers = currentExpected.map { it.noteNumber }
        val isPitchMatch = pitch.matchedNoteNumber != null &&
            expectedNoteNumbers.contains(pitch.matchedNoteNumber)

        val status = when {
            !isPitchMatch -> StrikeAccuracyStatus.WRONG_NOTE
            abs(deviationMs) <= timingProfile.perfectMs -> StrikeAccuracyStatus.PERFECT
            abs(deviationMs) <= timingProfile.goodMs -> StrikeAccuracyStatus.GOOD
            deviationMs < -timingProfile.goodMs -> StrikeAccuracyStatus.EARLY
            else -> StrikeAccuracyStatus.LATE
        }
        val timingStatus = when {
            abs(deviationMs) <= timingProfile.perfectMs -> TimingAccuracyStatus.PERFECT
            abs(deviationMs) <= timingProfile.goodMs -> TimingAccuracyStatus.GOOD
            deviationMs < -timingProfile.goodMs -> TimingAccuracyStatus.EARLY
            else -> TimingAccuracyStatus.LATE
        }

        strikeProcessedForCurrentBeat = true
        pendingMatch?.let {
            val matchedEvent = if (isPitchMatch) {
                it.remainingEvents.firstOrNull { event -> event.noteNumber == pitch.matchedNoteNumber }
            } else {
                it.remainingEvents.firstOrNull()
            }
            matchedEvent?.let(it.remainingEvents::remove)
            if (it.remainingEvents.isEmpty()) {
                it.lifecycle = ExpectedEventLifecycle.MATCHED
                pendingExpectedEvents.remove(it)
            }
            if (it.targetTimestampNanos == expectedBeatTargetTimestampNanos && it.remainingEvents.isEmpty()) {
                expectedNoteEvents = emptyList()
                expectedBeatTargetTimestampNanos = 0L
            } else if (it.targetTimestampNanos == expectedBeatTargetTimestampNanos) {
                expectedNoteEvents = it.remainingEvents.toList()
            }
        }
        timingWindowList.add(abs(deviationMs))

        val feedback = StrikeFeedback(
            status = status,
            timingStatus = timingStatus,
            deviationMs = deviationMs,
            expectedNotes = expectedNoteNumbers,
            detectedNote = pitch.matchedNoteNumber,
            detectedFreqHz = pitch.frequencyHz,
            detectedCentsOffset = pitch.centsOffset,
            confidence = pitch.confidence,
            expectedTimestampNanos = targetNanos,
            monotonicTimestampNanos = strikeTimestampNanos,
            noteCorrect = isPitchMatch
        )

        updateStatsWithFeedback(feedback, isPitchMatch, abs(deviationMs))
    }

    private fun expirePendingEvents(nowNanos: Long) {
        val missWindowNanos = timingProfile.missMs * 1_000_000L
        val expired = pendingExpectedEvents.filter {
            it.lifecycle != ExpectedEventLifecycle.MATCHED &&
                nowNanos - it.targetTimestampNanos > missWindowNanos
        }
        expired.forEach {
            it.lifecycle = ExpectedEventLifecycle.MISSED
            registerMissedNote(it.remainingEvents.map(NoteEvent::noteNumber), it.targetTimestampNanos)
        }
        pendingExpectedEvents.removeAll(expired.toSet())
    }

    private fun finalizePendingEvents() {
        val pending = pendingExpectedEvents.toList()
        pending.forEach { event ->
            event.lifecycle = ExpectedEventLifecycle.MISSED
            registerMissedNote(event.remainingEvents.map(NoteEvent::noteNumber), event.targetTimestampNanos)
        }
        pendingExpectedEvents.clear()
        expectedNoteEvents = emptyList()
        expectedBeatTargetTimestampNanos = 0L
    }

    private fun registerMissedNote(expectedNotes: List<Int>, expectedTimestampNanos: Long) {
        val feedback = StrikeFeedback(
            status = StrikeAccuracyStatus.MISSED,
            timingStatus = TimingAccuracyStatus.MISSED,
            deviationMs = 0L,
            expectedNotes = expectedNotes,
            detectedNote = null,
            detectedFreqHz = 0f,
            detectedCentsOffset = 0,
            confidence = 0f,
            expectedTimestampNanos = expectedTimestampNanos,
            monotonicTimestampNanos = clock.nowNanos(),
            noteCorrect = false
        )
        _state.update {
            val newMissed = it.missedCount + expectedNotes.size
            val newTotal = it.totalStrikesEvaluated + expectedNotes.size

            val timingScore = (it.perfectCount * 100) + (it.goodCount * 80) +
                (it.earlyCount * 50) + (it.lateCount * 50) + it.wrongNoteTimingPoints
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
                consistencyPercentage = calculateConsistency(),
                lastFeedback = feedback
            )
        }
    }

    private fun registerExtraStrike(pitch: DetectedPitchResult, timestampNanos: Long) {
        val feedback = StrikeFeedback(
            status = StrikeAccuracyStatus.EXTRA_STRIKE,
            timingStatus = TimingAccuracyStatus.UNKNOWN,
            deviationMs = 0L,
            expectedNotes = emptyList(),
            detectedNote = pitch.matchedNoteNumber,
            detectedFreqHz = pitch.frequencyHz,
            detectedCentsOffset = pitch.centsOffset,
            confidence = pitch.confidence,
            expectedTimestampNanos = 0L,
            monotonicTimestampNanos = timestampNanos,
            noteCorrect = false
        )
        _state.update {
            it.copy(
                totalStrikesEvaluated = it.totalStrikesEvaluated + 1,
                extraStrikeCount = it.extraStrikeCount + 1,
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
            var wrongTimingPoints = current.wrongNoteTimingPoints

            when (feedback.status) {
                StrikeAccuracyStatus.PERFECT -> perfect++
                StrikeAccuracyStatus.GOOD -> good++
                StrikeAccuracyStatus.EARLY -> early++
                StrikeAccuracyStatus.LATE -> late++
                StrikeAccuracyStatus.WRONG_NOTE -> wrong++
                StrikeAccuracyStatus.MISSED -> {}
            }

            val totalEvaluated = perfect + good + early + late + wrong + current.missedCount + current.extraStrikeCount
            if (feedback.status == StrikeAccuracyStatus.WRONG_NOTE) {
                wrongTimingPoints += timingPoints(feedback.timingStatus)
            }
            val timingScore = (perfect * 100) + (good * 80) + (early * 50) + (late * 50) + wrongTimingPoints
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
                wrongNoteTimingPoints = wrongTimingPoints,
                timingAccuracyPercentage = timingAcc.coerceIn(0f, 100f),
                noteAccuracyPercentage = noteAcc.coerceIn(0f, 100f),
                accuracyPercentage = overall,
                consistencyPercentage = calculateConsistency(absDeviation),
                averageTimingDeviationMs = avgDeviation,
                lastFeedback = feedback
            )
        }
    }

    private fun calculateConsistency(latestDeviation: Long? = null): Float {
        val deviations = timingWindowList.toMutableList()
        latestDeviation?.let { deviations += it }
        if (deviations.size < 2) return 100f
        val mean = deviations.average()
        val standardDeviation = kotlin.math.sqrt(
            deviations.map { (it - mean) * (it - mean) }.average()
        )
        return (100.0 - standardDeviation.coerceAtMost(100.0))
            .toFloat()
            .coerceIn(0f, 100f)
    }

    private fun timingPoints(status: TimingAccuracyStatus): Int = when (status) {
        TimingAccuracyStatus.PERFECT -> 100
        TimingAccuracyStatus.GOOD -> 80
        TimingAccuracyStatus.EARLY, TimingAccuracyStatus.LATE -> 50
        TimingAccuracyStatus.MISSED, TimingAccuracyStatus.UNKNOWN -> 0
    }
}
