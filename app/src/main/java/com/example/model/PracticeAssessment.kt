package com.example.model

/** Product-level classification of a detected or expected practice event. */
enum class StrikeClassification {
    NO_STRIKE,
    CORRECT_NOTE,
    WRONG_NOTE,
    UNKNOWN_NOTE,
    EXTRA_STRIKE,
    MISSED_NOTE
}

/** Timing is intentionally independent from note classification. */
enum class TimingStatus {
    EARLY,
    EXCELLENT,
    GOOD,
    LATE,
    PERFECT,
    OUTSIDE_WINDOW
}

data class TimingResult(
    val status: TimingStatus,
    val deviationNanos: Long
) {
    val deviationMs: Long
        get() = deviationNanos / 1_000_000L
}

data class TimingToleranceProfile(
    val perfectWindowNanos: Long,
    val excellentWindowNanos: Long,
    val goodWindowNanos: Long,
    val missWindowNanos: Long
) {
    init {
        require(perfectWindowNanos >= 0L)
        require(perfectWindowNanos <= excellentWindowNanos)
        require(excellentWindowNanos <= goodWindowNanos)
        require(goodWindowNanos <= missWindowNanos)
    }

    fun toTimingPolicy(): TimingPolicy = TimingPolicy(
        earlyWindowNanos = missWindowNanos,
        lateWindowNanos = missWindowNanos,
        perfectWindowNanos = perfectWindowNanos,
        excellentWindowNanos = excellentWindowNanos,
        goodWindowNanos = goodWindowNanos
    )
}

enum class AssessmentEventType {
    EXPECTED,
    CORRECT,
    WRONG,
    UNKNOWN,
    MISSED,
    EXTRA
}

enum class AssessmentSessionValidity {
    VALID,
    INVALID_SIGNAL,
    INVALID_DURATION,
    INVALID_RESTART,
    INVALID_TARGET_CONTEXT,
    INVALID_NOT_FINALIZED
}

enum class PracticeSessionLifecycle {
    ACTIVE,
    PAUSED,
    FINALIZED
}

class PracticeSessionContext private constructor(
    val sessionId: String,
    val patternId: String,
    val startTimestampNanos: Long,
    val restartCount: Int = 0
) {
    var endTimestampNanos: Long? = null
        private set
    var lifecycle: PracticeSessionLifecycle = PracticeSessionLifecycle.ACTIVE
        private set
    var accumulatedPausedDurationNanos: Long = 0L
        private set
    private var pauseStartedTimestampNanos: Long? = null

    val finalized: Boolean
        get() = lifecycle == PracticeSessionLifecycle.FINALIZED

    val elapsedDurationNanos: Long
        get() = ((endTimestampNanos ?: startTimestampNanos) - startTimestampNanos).coerceAtLeast(0L)

    val activeDurationNanos: Long
        get() = (elapsedDurationNanos - accumulatedPausedDurationNanos - openPauseDuration()).coerceAtLeast(0L)

    val activeDurationMs: Long
        get() = activeDurationNanos / 1_000_000L

    fun pause(timestampNanos: Long) {
        if (lifecycle != PracticeSessionLifecycle.ACTIVE) return
        pauseStartedTimestampNanos = timestampNanos.coerceAtLeast(startTimestampNanos)
        lifecycle = PracticeSessionLifecycle.PAUSED
    }

    fun resume(timestampNanos: Long) {
        if (lifecycle != PracticeSessionLifecycle.PAUSED) return
        val pauseStarted = pauseStartedTimestampNanos ?: timestampNanos
        accumulatedPausedDurationNanos += (timestampNanos - pauseStarted).coerceAtLeast(0L)
        pauseStartedTimestampNanos = null
        lifecycle = PracticeSessionLifecycle.ACTIVE
    }

    fun finalize(timestampNanos: Long) {
        if (finalized) return
        val end = timestampNanos.coerceAtLeast(startTimestampNanos)
        if (lifecycle == PracticeSessionLifecycle.PAUSED) {
            val pauseStarted = pauseStartedTimestampNanos ?: end
            accumulatedPausedDurationNanos += (end - pauseStarted).coerceAtLeast(0L)
            pauseStartedTimestampNanos = null
        }
        endTimestampNanos = end
        lifecycle = PracticeSessionLifecycle.FINALIZED
    }

    private fun openPauseDuration(): Long {
        val pauseStarted = pauseStartedTimestampNanos ?: return 0L
        val end = endTimestampNanos ?: startTimestampNanos
        return (end - pauseStarted).coerceAtLeast(0L)
    }

    companion object {
        fun start(
            patternId: String,
            startTimestampNanos: Long,
            restartCount: Int = 0,
            sessionId: String = "practice-$patternId-$startTimestampNanos"
        ): PracticeSessionContext {
            require(patternId.isNotBlank())
            require(sessionId.isNotBlank())
            require(startTimestampNanos >= 0L)
            require(restartCount >= 0)
            return PracticeSessionContext(sessionId, patternId, startTimestampNanos, restartCount)
        }
    }
}

data class AssessmentSessionSummary(
    val session: PracticeSessionContext,
    val quality: AssessmentSessionQuality
)

data class AssessmentTimelineEvent(
    val eventId: String,
    val sessionId: String,
    val loopId: String?,
    val sequenceIndex: Int,
    val expectedNote: Int?,
    val detectedNote: Int?,
    val eventType: AssessmentEventType,
    val expectedTimestampNanos: Long?,
    val detectedTimestampNanos: Long?,
    val deviationNanos: Long?,
    val timingResult: TimingResult?,
    val confidence: Float,
    val targetId: String?,
    val source: String,
    val durationNanos: Long?,
    val isConsumed: Boolean,
    val assessmentSessionId: String = sessionId,
    val patternId: String? = null,
    val obligationId: String? = null,
    val expectedNotes: Set<Int> = expectedNote?.let { setOf(it) } ?: emptySet(),
    val classification: StrikeClassification? = eventType.toStrikeClassification(),
    val measuredAmplitude: Float? = null,
    val measuredVelocity: Float? = null,
    val accentStrength: Float? = null,
    val expectedTechnique: HandpanTechnique? = null,
    val detectedTechnique: HandpanTechnique? = null,
    val targetBpm: Int? = null,
    val targetNoteId: String? = null,
    val subdivision: Subdivision? = null,
    val beatPosition: Double? = null,
    val expectedTimingWindow: TimingToleranceProfile? = null,
    val sessionValidity: AssessmentSessionValidity = AssessmentSessionValidity.INVALID_TARGET_CONTEXT,
    val signalQuality: Float? = null
)

private fun AssessmentEventType.toStrikeClassification(): StrikeClassification = when (this) {
    AssessmentEventType.EXPECTED -> StrikeClassification.NO_STRIKE
    AssessmentEventType.CORRECT -> StrikeClassification.CORRECT_NOTE
    AssessmentEventType.WRONG -> StrikeClassification.WRONG_NOTE
    AssessmentEventType.UNKNOWN -> StrikeClassification.UNKNOWN_NOTE
    AssessmentEventType.MISSED -> StrikeClassification.MISSED_NOTE
    AssessmentEventType.EXTRA -> StrikeClassification.EXTRA_STRIKE
}

class AssessmentTimeline(
    private var canonicalSessionId: String? = null
) {
    private val events = mutableListOf<AssessmentTimelineEvent>()
    private val listeners = mutableListOf<(AssessmentTimelineEvent) -> Unit>()

    @Synchronized
    fun append(event: AssessmentTimelineEvent): AssessmentTimelineEvent {
        require(event.eventId.isNotBlank()) { "Timeline event ID must not be blank" }
        require(event.sessionId.isNotBlank()) { "Timeline session ID must not be blank" }
        require(event.assessmentSessionId == event.sessionId) {
            "Timeline assessment session ID must match event session ID"
        }
        require(event.confidence in 0f..1f) { "Timeline confidence must be between 0 and 1" }
        canonicalSessionId?.let { require(event.sessionId == it) { "Timeline event belongs to another session" } }
        require(events.none { it.eventId == event.eventId }) {
            "Duplicate timeline event ID: ${event.eventId}"
        }
        events += event
        listeners.toList().forEach { it(event) }
        return event
    }

    @Synchronized
    fun bindToSession(sessionId: String) {
        require(sessionId.isNotBlank())
        require(events.all { it.sessionId == sessionId }) { "Timeline already contains another session" }
        canonicalSessionId = sessionId
    }

    @Synchronized
    fun snapshot(): List<AssessmentTimelineEvent> = events.toList()

    @Synchronized
    fun clear() {
        events.clear()
        canonicalSessionId = null
    }

    @Synchronized
    fun subscribe(listener: (AssessmentTimelineEvent) -> Unit): Subscription {
        listeners += listener
        return Subscription { unsubscribe(listener) }
    }

    @Synchronized
    private fun unsubscribe(listener: (AssessmentTimelineEvent) -> Unit) {
        listeners -= listener
    }

    class Subscription internal constructor(
        private val onClose: () -> Unit
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (!closed) {
                closed = true
                onClose()
            }
        }
    }
}

data class DetectedStrikeEvent(
    val id: String,
    val sessionId: String,
    val monotonicTimestampNanos: Long,
    val detectedFrequencyHz: Float,
    val detectedNoteName: String,
    val detectedCentsOffset: Int,
    val detectedNote: Int?,
    val matchedPitchDiffHz: Float,
    val pitchConfidence: Float,
    val onsetStrength: Float,
    val energy: Float,
    val pitchValid: Boolean,
    val onsetConfidence: Float = 0f,
    val signalQuality: Float = 0f,
    val source: String = "microphone",
    val durationNanos: Long? = null,
    val audioQuality: AudioFrameQuality? = null
)

data class ExpectedNoteEvent(
    val id: String,
    val sessionId: String,
    val noteNumber: Int,
    val targetTimestampNanos: Long,
    val patternPosition: Double,
    val loopIteration: Int = 0
)

data class NoteMatchResult(
    val classification: StrikeClassification,
    val expectedNote: Int?,
    val detectedNote: Int?
)

data class PracticeSession(
    val id: String,
    val startedAtMonotonicNanos: Long,
    val patternId: String,
    val bpm: Int
)

data class RecordingEvent(
    val timestampMs: Long,
    val expectedNote: Int?,
    val detectedNote: Int?,
    val classification: StrikeClassification,
    val timing: TimingResult?,
    val confidence: Float
)

data class PracticeScore(
    val correctCount: Int,
    val wrongCount: Int,
    val unknownCount: Int,
    val missedCount: Int,
    val extraCount: Int,
    val noteAccuracyPercentage: Float,
    val timingAccuracyPercentage: Float,
    val overallAccuracyPercentage: Float,
    val maxCombo: Int = 0,
    val score: Int = 0
)

data class PracticeResult(
    val score: Int,
    val accuracy: Float,
    val timingAccuracy: Float,
    val noteAccuracy: Float,
    val perfectCount: Int,
    val greatCount: Int,
    val goodCount: Int,
    val earlyCount: Int,
    val lateCount: Int,
    val missCount: Int,
    val wrongNoteCount: Int,
    val maxCombo: Int,
    val totalTargets: Int,
    val completedTargets: Int,
    val durationMs: Long
)