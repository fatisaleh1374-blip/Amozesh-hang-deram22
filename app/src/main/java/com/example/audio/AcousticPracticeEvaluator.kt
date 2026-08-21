package com.example.audio

import com.example.model.HandpanPattern
import com.example.model.NoteEvent
import com.example.model.NotePitchConfig
import com.example.model.DetectedStrikeEvent
import com.example.model.StrikeClassification
import com.example.model.TimingResult
import com.example.model.TimingStatus
import com.example.model.PracticeScoreCalculator
import com.example.model.ScoreCounters
import com.example.model.AssessmentEventType
import com.example.model.AssessmentTimeline
import com.example.model.AssessmentTimelineEvent
import com.example.model.MusicalTarget
import com.example.model.MusicalTargetIdentity
import com.example.model.MusicalTargetMatcher
import com.example.model.TargetMatchType
import com.example.model.MusicalTarget
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
    UNKNOWN_NOTE,
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
    val noteCorrect: Boolean,
    val classification: StrikeClassification = when (status) {
        StrikeAccuracyStatus.PERFECT,
        StrikeAccuracyStatus.GOOD,
        StrikeAccuracyStatus.EARLY,
        StrikeAccuracyStatus.LATE -> StrikeClassification.CORRECT_NOTE
        StrikeAccuracyStatus.WRONG_NOTE -> StrikeClassification.WRONG_NOTE
        StrikeAccuracyStatus.UNKNOWN_NOTE -> StrikeClassification.UNKNOWN_NOTE
        StrikeAccuracyStatus.EXTRA_STRIKE -> StrikeClassification.EXTRA_STRIKE
        StrikeAccuracyStatus.MISSED -> StrikeClassification.MISSED_NOTE
    },
    val timingResult: TimingResult? = if (status == StrikeAccuracyStatus.EXTRA_STRIKE || status == StrikeAccuracyStatus.MISSED) {
        null
    } else {
        TimingResult(
            status = when (timingStatus) {
                TimingAccuracyStatus.PERFECT -> TimingStatus.PERFECT
                TimingAccuracyStatus.GOOD -> TimingStatus.GOOD
                TimingAccuracyStatus.EARLY -> TimingStatus.EARLY
                TimingAccuracyStatus.LATE -> TimingStatus.LATE
                TimingAccuracyStatus.MISSED,
                TimingAccuracyStatus.UNKNOWN -> TimingStatus.GOOD
            },
            deviationNanos = deviationMs * 1_000_000L
        )
    }
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
    val unknownNoteCount: Int = 0,
    val wrongNoteTimingPoints: Int = 0,
    val extraStrikeCount: Int = 0,
    val missedCount: Int = 0,
    // Separate Metrics
    val timingAccuracyPercentage: Float = 0f,
    val noteAccuracyPercentage: Float = 0f,
    val accuracyPercentage: Float = 0f, // Combined overall
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
    private val analysisSession: AudioAnalysisSession = AudioAnalysisSession(),
    private val ownsAnalysisSession: Boolean = true,
    val timeline: AssessmentTimeline = AssessmentTimeline()
) {
    private val _state = MutableStateFlow(AcousticAssessmentState())
    val state: StateFlow<AcousticAssessmentState> = _state.asStateFlow()

    private var activePattern: HandpanPattern? = null
    private var scaleConfig: NotePitchConfig = NotePitchConfig()
    private val timingWindowList = mutableListOf<Long>()
    private var assessmentStartTimestampNanos: Long = 0L
    private var beatDurationNanos: Long = 500_000_000L
    private var timingProfile = TimingProfile()
    private var analysisSubscription: AudioAnalysisSession.Subscription? = null
    private var assessmentSessionId: String = ""
    private var currentLoopId: String = "loop-0"
    private val targetMatcher = MusicalTargetMatcher()

    // Current expected target note events and monotonic window timestamp in nanoseconds
    @Volatile
    private var expectedNoteEvents: List<NoteEvent> = emptyList()
    @Volatile
    private var expectedBeatTargetTimestampNanos: Long = 0L
    @Volatile
    private var strikeProcessedForCurrentBeat: Boolean = false
    private val processedStrikeIds = mutableSetOf<String>()

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
        assessmentSessionId = "assessment-${pattern.id}-${clock.nowNanos()}"
        currentLoopId = "loop-0"
        targetMatcher.clear()
        timeline.clear()
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

        analysisSubscription?.close()
        analysisSubscription = analysisSession.acquire(
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
            onStrike = { event ->
                handleStrikeDetected(event)
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
        val matcher = OnsetAndPitchMatcher(22050)
        val (matchedNote, centsDev) = matcher.matchToScaleByCents(frequencyHz, scaleConfig)
        val (noteName, cents) = YinPitchDetector.frequencyToNoteAndCents(frequencyHz)

        handleStrikeDetected(
            DetectedStrikeEvent(
                id = "manual-$timestampNanos-$frequencyHz-$confidence",
                sessionId = "manual",
                monotonicTimestampNanos = timestampNanos,
                detectedFrequencyHz = frequencyHz,
                detectedNoteName = noteName,
                detectedCentsOffset = cents,
                detectedNote = matchedNote,
                matchedPitchDiffHz = centsDev,
                pitchConfidence = confidence,
                onsetStrength = 0.85f,
                energy = 0.85f,
                pitchValid = matchedNote != null && confidence >= 0.5f,
                source = "manual"
            )
        )
        return _state.value.lastFeedback
    }

    fun stopAssessment(showSummary: Boolean = true) {
        // Ending a session closes every still-pending target; there will be no later strike
        // or expected slice to advance the evaluator past its miss window.
        finalizePendingEvents()
        analysisSubscription?.close()
        analysisSubscription = null
        if (ownsAnalysisSession) analysisSession.close()
        _state.update {
            it.copy(
                isEnabled = false,
                isListening = false,
                isSummaryDialogVisible = showSummary && it.totalStrikesEvaluated > 0
            )
        }
    }

    fun release() {
        analysisSubscription?.close()
        analysisSubscription = null
        if (ownsAnalysisSession) analysisSession.close()
    }

    fun toggleEnabled() {
        val willEnable = !_state.value.isEnabled
        _state.update { it.copy(isEnabled = willEnable) }
        if (!willEnable) {
            analysisSubscription?.close()
            analysisSubscription = null
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
        processedStrikeIds.clear()
        targetMatcher.clear()

        _state.update {
            it.copy(
                totalExpectedNotes = 0,
                totalStrikesEvaluated = 0,
                perfectCount = 0,
                goodCount = 0,
                earlyCount = 0,
                lateCount = 0,
                wrongNoteCount = 0,
                unknownNoteCount = 0,
                wrongNoteTimingPoints = 0,
                extraStrikeCount = 0,
                missedCount = 0,
                timingAccuracyPercentage = 0f,
                noteAccuracyPercentage = 0f,
                accuracyPercentage = 0f,
                averageTimingDeviationMs = 0f,
                lastFeedback = null,
                isSummaryDialogVisible = false
            )
        }
    }

    /**
     * Called by PracticeEngine on each slice with monotonic timestamp in nanoseconds.
     */
    @Synchronized
    fun notifyExpectedTarget(target: MusicalTarget) {
        if (!_state.value.isEnabled || !_state.value.isListening) return
        currentLoopId = target.identity.loopId
        targetMatcher.addTarget(target)
        target.identity.expectedNotes.forEachIndexed { index, noteNumber ->
            timeline.append(
                AssessmentTimelineEvent(
                    eventId = "${target.identity.targetId}-expected-$index",
                    sessionId = target.identity.sessionId,
                    loopId = target.identity.loopId,
                    sequenceIndex = target.identity.sequenceIndex,
                    expectedNote = noteNumber,
                    detectedNote = null,
                    eventType = AssessmentEventType.EXPECTED,
                    expectedTimestampNanos = target.identity.expectedTimestampNanos,
                    detectedTimestampNanos = null,
                    deviationNanos = null,
                    timingResult = null,
                    confidence = 0f,
                    targetId = target.identity.targetId,
                    source = "pattern-scheduler",
                    durationNanos = null,
                    isConsumed = false
                )
            )
        }
        _state.update { it.copy(totalExpectedNotes = it.totalExpectedNotes + target.identity.expectedNotes.size) }
    }

    @Deprecated("Use notifyExpectedTarget; target identity belongs to PatternScheduler")
    fun notifyExpectedSlice(
        events: List<NoteEvent>,
        targetTimestampNanos: Long,
        loopId: String = currentLoopId
    ) {
        if (!_state.value.isEnabled || !_state.value.isListening) return

        val activeEvents = events.filter { !it.isRest }
        if (activeEvents.isEmpty()) return
        val target = PatternScheduler.buildSchedule(
            events = activeEvents,
            beatsPerBar = activePattern?.timeSignature?.beatsPerBar ?: 4,
            totalBars = 1,
            timeSignature = activePattern?.timeSignature ?: com.example.model.TimeSignature.Common44,
            assessmentSessionId = assessmentSessionId,
            patternId = activePattern?.id ?: "unknown",
            loopIndex = loopId.removePrefix("loop-").toIntOrNull() ?: 0,
            scheduleStartTimestampNanos = targetTimestampNanos,
            bpm = 60
        ).firstOrNull()?.target ?: return
        notifyExpectedTarget(target)
    }

    @Synchronized
    private fun handleStrikeDetected(event: DetectedStrikeEvent) {
        if (!_state.value.isEnabled || !_state.value.isListening) return

        if (!processedStrikeIds.add(event.id)) return

        val pitch = event.toPitchResult()
        val strikeTimestampNanos = event.monotonicTimestampNanos

        val decision = targetMatcher.match(event, currentLoopId, assessmentSessionId)
        if (decision.type == TargetMatchType.EXTRA || decision.target == null) {
            registerExtraStrike(pitch, strikeTimestampNanos)
            return
        }

        val targetNanos = decision.target.identity.expectedTimestampNanos
        val currentExpected = decision.target.remainingNotes
        val isWithinTargetWindow = true
        val deviationNanos = strikeTimestampNanos - targetNanos
        val deviationMs = deviationNanos / 1_000_000L

        // If strike occurred way outside reasonable beat window (> 250ms), ignore as noise or stray tap
        if (abs(deviationMs) > timingProfile.ignoreAfterMs) return

        val expectedNoteNumbers = currentExpected.toList()
        val isPitchMatch = decision.type == TargetMatchType.CORRECT

        val status = when {
            decision.type == TargetMatchType.UNKNOWN -> StrikeAccuracyStatus.UNKNOWN_NOTE
            decision.type == TargetMatchType.WRONG -> StrikeAccuracyStatus.WRONG_NOTE
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

        val eventType = when (status) {
            StrikeAccuracyStatus.PERFECT,
            StrikeAccuracyStatus.GOOD,
            StrikeAccuracyStatus.EARLY,
            StrikeAccuracyStatus.LATE -> AssessmentEventType.CORRECT
            StrikeAccuracyStatus.WRONG_NOTE -> AssessmentEventType.WRONG
            StrikeAccuracyStatus.UNKNOWN_NOTE -> AssessmentEventType.UNKNOWN
            StrikeAccuracyStatus.EXTRA_STRIKE -> AssessmentEventType.EXTRA
            StrikeAccuracyStatus.MISSED -> AssessmentEventType.MISSED
        }
        timeline.append(
            AssessmentTimelineEvent(
                eventId = "${event.id}-assessment",
                sessionId = event.sessionId,
                loopId = decision.target.identity.loopId,
                sequenceIndex = decision.target.identity.sequenceIndex,
                expectedNote = expectedNoteNumbers.firstOrNull(),
                detectedNote = event.detectedNote,
                eventType = eventType,
                expectedTimestampNanos = if (isWithinTargetWindow) targetNanos else null,
                detectedTimestampNanos = strikeTimestampNanos,
                deviationNanos = if (isWithinTargetWindow) deviationNanos else null,
                timingResult = feedback.timingResult,
                confidence = event.pitchConfidence,
                targetId = decision.target.identity.targetId,
                source = event.source,
                durationNanos = event.durationNanos,
                isConsumed = isPitchMatch
            )
        )

        updateStatsWithFeedback(feedback, isPitchMatch, abs(deviationMs))
    }

    private fun expirePendingEvents(nowNanos: Long) {
        val finalized = targetMatcher.finalize(nowNanos)
        finalized.forEach { decision ->
            val target = decision.target ?: return@forEach
            registerMissedNote(
                expectedNotes = target.remainingNotes.toList(),
                expectedTimestampNanos = target.identity.expectedTimestampNanos,
                targetId = target.identity.targetId,
                sequenceIndex = target.identity.sequenceIndex,
                loopId = target.identity.loopId
            )
        }
    }

    @Synchronized
    private fun finalizePendingEvents() {
        val finalized = targetMatcher.finalize(Long.MAX_VALUE)
        finalized.forEach { decision ->
            val target = decision.target ?: return@forEach
            registerMissedNote(
                expectedNotes = target.remainingNotes.toList(),
                expectedTimestampNanos = target.identity.expectedTimestampNanos,
                targetId = target.identity.targetId,
                sequenceIndex = target.identity.sequenceIndex,
                loopId = target.identity.loopId
            )
        }
        expectedNoteEvents = emptyList()
        expectedBeatTargetTimestampNanos = 0L
    }

    private fun registerMissedNote(
        expectedNotes: List<Int>,
        expectedTimestampNanos: Long,
        targetId: String? = null,
        sequenceIndex: Int = -1,
        loopId: String = currentLoopId
    ) {
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
        expectedNotes.forEachIndexed { index, noteNumber ->
            timeline.append(
                AssessmentTimelineEvent(
                    eventId = "$assessmentSessionId-missed-${expectedTimestampNanos}-$index",
                    sessionId = assessmentSessionId,
                    loopId = loopId,
                    sequenceIndex = sequenceIndex,
                    expectedNote = noteNumber,
                    detectedNote = null,
                    eventType = AssessmentEventType.MISSED,
                    expectedTimestampNanos = expectedTimestampNanos,
                    detectedTimestampNanos = clock.nowNanos(),
                    deviationNanos = null,
                    timingResult = null,
                    confidence = 0f,
                    targetId = targetId,
                    source = "evaluator",
                    durationNanos = null,
                    isConsumed = false
                )
            )
        }
        _state.update {
            val newMissed = it.missedCount + expectedNotes.size
            val newTotal = it.totalStrikesEvaluated + expectedNotes.size

            val timingScore = (it.perfectCount * 100) + (it.goodCount * 80) +
                (it.earlyCount * 50) + (it.lateCount * 50) + it.wrongNoteTimingPoints
            val score = PracticeScoreCalculator.calculate(
                ScoreCounters(
                    correctCount = it.perfectCount + it.goodCount + it.earlyCount + it.lateCount,
                    wrongCount = it.wrongNoteCount,
                    unknownCount = it.unknownNoteCount,
                    missedCount = newMissed,
                    extraCount = it.extraStrikeCount,
                    perfectCount = it.perfectCount,
                    goodCount = it.goodCount,
                    earlyCount = it.earlyCount,
                    lateCount = it.lateCount,
                    nonCorrectTimingPoints = it.wrongNoteTimingPoints
                )
            )

            it.copy(
                missedCount = newMissed,
                totalStrikesEvaluated = newTotal,
                timingAccuracyPercentage = score.timingAccuracyPercentage,
                noteAccuracyPercentage = score.noteAccuracyPercentage,
                accuracyPercentage = score.overallAccuracyPercentage,
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
        timeline.append(
            AssessmentTimelineEvent(
                eventId = "extra-$timestampNanos-${pitch.frequencyHz}",
                sessionId = assessmentSessionId,
                loopId = currentLoopId,
                sequenceIndex = -1,
                expectedNote = null,
                detectedNote = pitch.matchedNoteNumber,
                eventType = AssessmentEventType.EXTRA,
                expectedTimestampNanos = null,
                detectedTimestampNanos = timestampNanos,
                deviationNanos = null,
                timingResult = null,
                confidence = pitch.confidence,
                targetId = null,
                source = "evaluator",
                durationNanos = null,
                isConsumed = false
            )
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
            var unknown = current.unknownNoteCount
            var wrongTimingPoints = current.wrongNoteTimingPoints

            when (feedback.status) {
                StrikeAccuracyStatus.PERFECT -> perfect++
                StrikeAccuracyStatus.GOOD -> good++
                StrikeAccuracyStatus.EARLY -> early++
                StrikeAccuracyStatus.LATE -> late++
                StrikeAccuracyStatus.WRONG_NOTE -> wrong++
                StrikeAccuracyStatus.UNKNOWN_NOTE -> unknown++
                StrikeAccuracyStatus.MISSED -> {}
            }

            val totalEvaluated = perfect + good + early + late + wrong + unknown + current.missedCount + current.extraStrikeCount
            if (feedback.status == StrikeAccuracyStatus.WRONG_NOTE || feedback.status == StrikeAccuracyStatus.UNKNOWN_NOTE) {
                wrongTimingPoints += timingPoints(feedback.timingStatus)
            }
            val score = PracticeScoreCalculator.calculate(
                ScoreCounters(
                    correctCount = perfect + good + early + late,
                    wrongCount = wrong,
                    unknownCount = unknown,
                    missedCount = current.missedCount,
                    extraCount = current.extraStrikeCount,
                    perfectCount = perfect,
                    goodCount = good,
                    earlyCount = early,
                    lateCount = late,
                    nonCorrectTimingPoints = wrongTimingPoints
                )
            )

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
                unknownNoteCount = unknown,
                wrongNoteTimingPoints = wrongTimingPoints,
                timingAccuracyPercentage = score.timingAccuracyPercentage,
                noteAccuracyPercentage = score.noteAccuracyPercentage,
                accuracyPercentage = score.overallAccuracyPercentage,
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
