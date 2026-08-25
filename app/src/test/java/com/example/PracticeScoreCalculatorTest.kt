package com.example

import com.example.model.PracticeScoreCalculator
import com.example.model.ScoreCounters
import com.example.model.AssessmentEventType
import com.example.model.AssessmentTimeline
import com.example.model.AssessmentTimelineEvent
import com.example.model.TimingResult
import com.example.model.TimingStatus
import com.example.model.AssessmentWeightProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeScoreCalculatorTest {
    @Test
    fun configurableTimingProfileClassifiesExcellentBoundary() {
        val policy = com.example.model.TimingToleranceProfile(
            perfectWindowNanos = 20_000_000L,
            excellentWindowNanos = 40_000_000L,
            goodWindowNanos = 80_000_000L,
            missWindowNanos = 160_000_000L
        ).toTimingPolicy()
        val decision = com.example.model.MusicalTargetMatcher().classify(
            candidate = com.example.model.MusicalTarget(
                com.example.model.MusicalTargetIdentity(
                    sessionId = "session",
                    patternId = "pattern",
                    loopId = "loop",
                    sequenceIndex = 0,
                    targetId = "target",
                    beatIndex = 0,
                    subdivisionIndex = 0,
                    expectedTimestampNanos = 1_000_000_000L,
                    expectedNotes = setOf(0),
                    chordId = "target"
                )
            ),
            event = com.example.model.DetectedStrikeEvent(
                id = "strike",
                sessionId = "session",
                monotonicTimestampNanos = 1_035_000_000L,
                detectedFrequencyHz = 146.83f,
                detectedNoteName = "D3",
                detectedCentsOffset = 0,
                detectedNote = 0,
                matchedPitchDiffHz = 0f,
                pitchConfidence = 1f,
                onsetStrength = 1f,
                energy = 1f,
                pitchValid = true
            ),
            policy = policy
        )

        assertEquals(TimingStatus.EXCELLENT, decision.timing?.status)
    }

    @Test
    fun canonicalMetricsPenalizeLowConfidenceWithoutChangingNoteCorrectness() {
        val high = event("high", AssessmentEventType.CORRECT).copy(confidence = 1f)
        val low = event("low", AssessmentEventType.CORRECT).copy(confidence = 0.2f)
        val highMetrics = PracticeScoreCalculator.calculateMetrics(
            listOf(event("expected-high", AssessmentEventType.EXPECTED), high),
            AssessmentWeightProfile(confidence = 1f, timing = 0f, noteAccuracy = 0f, completion = 0f, consistency = 0f)
        )
        val lowMetrics = PracticeScoreCalculator.calculateMetrics(
            listOf(event("expected-low", AssessmentEventType.EXPECTED), low),
            AssessmentWeightProfile(confidence = 1f, timing = 0f, noteAccuracy = 0f, completion = 0f, consistency = 0f)
        )

        assertEquals(100f, highMetrics.noteAccuracy, 0.001f)
        assertEquals(20f, lowMetrics.confidenceScore, 0.001f)
        assertTrue(highMetrics.overallPerformance > lowMetrics.overallPerformance)
    }
    @Test
    fun emptySessionStartsAtZero() {
        val score = PracticeScoreCalculator.calculate(ScoreCounters())

        assertEquals(0f, score.noteAccuracyPercentage, 0.001f)
        assertEquals(0f, score.timingAccuracyPercentage, 0.001f)
        assertEquals(0f, score.overallAccuracyPercentage, 0.001f)
    }

    @Test
    fun noteAndTimingAccuracyRemainIndependent() {
        val score = PracticeScoreCalculator.calculate(
            ScoreCounters(
                correctCount = 1,
                wrongCount = 1,
                perfectCount = 1,
                nonCorrectTimingPoints = 100
            )
        )

        assertEquals(50f, score.noteAccuracyPercentage, 0.001f)
        assertEquals(100f, score.timingAccuracyPercentage, 0.001f)
        assertEquals(75f, score.overallAccuracyPercentage, 0.001f)
    }

    @Test
    fun missedUnknownAndExtraAreReportedSeparately() {
        val score = PracticeScoreCalculator.calculate(
            ScoreCounters(unknownCount = 1, missedCount = 1, extraCount = 1)
        )

        assertEquals(0, score.correctCount)
        assertEquals(1, score.unknownCount)
        assertEquals(1, score.missedCount)
        assertEquals(1, score.extraCount)
        assertEquals(0f, score.noteAccuracyPercentage, 0.001f)
    }

    @Test
    fun timelineProjectionStartsAtZeroAndKeepsExtraOutOfNoteDenominator() {
        val timeline = AssessmentTimeline()
        timeline.append(event("extra", AssessmentEventType.EXTRA))

        val score = PracticeScoreCalculator.calculate(timeline)

        assertEquals(0f, score.noteAccuracyPercentage, 0.001f)
        assertEquals(0f, score.timingAccuracyPercentage, 0.001f)
        assertEquals(1, score.extraCount)
    }

    @Test
    fun timelineProjectionKeepsWrongNoteAndTimingAccuracyIndependent() {
        val timeline = AssessmentTimeline()
        timeline.append(event("correct", AssessmentEventType.CORRECT, TimingStatus.PERFECT))
        timeline.append(event("wrong", AssessmentEventType.WRONG, TimingStatus.PERFECT))

        val score = PracticeScoreCalculator.calculate(timeline)

        assertEquals(50f, score.noteAccuracyPercentage, 0.001f)
        assertEquals(100f, score.timingAccuracyPercentage, 0.001f)
    }

    @Test
    fun resultPreservesScoreComboAndTargetStatistics() {
        val events = listOf(
            event("expected-1", AssessmentEventType.EXPECTED),
            event("perfect-1", AssessmentEventType.CORRECT, TimingStatus.PERFECT),
            event("perfect-2", AssessmentEventType.CORRECT, TimingStatus.PERFECT),
            event("miss-1", AssessmentEventType.MISSED),
            event("perfect-3", AssessmentEventType.CORRECT, TimingStatus.PERFECT)
        )

        val result = PracticeScoreCalculator.calculateResult(events, durationMs = 1_250L)

        assertEquals(1, result.completedTargets)
        assertEquals(1, result.totalTargets)
        assertEquals(2, result.maxCombo)
        assertEquals(1, result.missCount)
        assertEquals(1_250L, result.durationMs)
        assertTrue(result.score > 0)
    }

    private fun event(
        id: String,
        type: AssessmentEventType,
        timing: TimingStatus? = null
    ) = AssessmentTimelineEvent(
        eventId = id,
        sessionId = "session",
        loopId = "loop-1",
        sequenceIndex = 0,
        expectedNote = 0,
        detectedNote = if (type == AssessmentEventType.EXTRA) null else 0,
        eventType = type,
        expectedTimestampNanos = 1_000_000_000L,
        detectedTimestampNanos = 1_000_000_000L,
        deviationNanos = 0L,
        timingResult = timing?.let { TimingResult(it, 0L) },
        confidence = 1f,
        targetId = "target",
        source = "test",
        durationNanos = null,
        isConsumed = type == AssessmentEventType.CORRECT
    )
}