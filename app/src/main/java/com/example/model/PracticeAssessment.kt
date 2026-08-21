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
)

enum class AssessmentEventType {
    EXPECTED,
    CORRECT,
    WRONG,
    UNKNOWN,
    MISSED,
    EXTRA
}

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
    val classification: StrikeClassification? = eventType.toStrikeClassification()
)

private fun AssessmentEventType.toStrikeClassification(): StrikeClassification = when (this) {
    AssessmentEventType.EXPECTED -> StrikeClassification.NO_STRIKE
    AssessmentEventType.CORRECT -> StrikeClassification.CORRECT_NOTE
    AssessmentEventType.WRONG -> StrikeClassification.WRONG_NOTE
    AssessmentEventType.UNKNOWN -> StrikeClassification.UNKNOWN_NOTE
    AssessmentEventType.MISSED -> StrikeClassification.MISSED_NOTE
    AssessmentEventType.EXTRA -> StrikeClassification.EXTRA_STRIKE
}

class AssessmentTimeline {
    private val events = mutableListOf<AssessmentTimelineEvent>()
    private val listeners = mutableListOf<(AssessmentTimelineEvent) -> Unit>()

    @Synchronized
    fun append(event: AssessmentTimelineEvent): AssessmentTimelineEvent {
        require(event.eventId.isNotBlank()) { "Timeline event ID must not be blank" }
        require(event.sessionId.isNotBlank()) { "Timeline session ID must not be blank" }
        require(event.confidence in 0f..1f) { "Timeline confidence must be between 0 and 1" }
        require(events.none { it.eventId == event.eventId }) {
            "Duplicate timeline event ID: ${event.eventId}"
        }
        events += event
        listeners.toList().forEach { it(event) }
        return event
    }

    @Synchronized
    fun snapshot(): List<AssessmentTimelineEvent> = events.toList()

    @Synchronized
    fun clear() {
        events.clear()
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
    val source: String = "microphone",
    val durationNanos: Long? = null
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
    val overallAccuracyPercentage: Float
)