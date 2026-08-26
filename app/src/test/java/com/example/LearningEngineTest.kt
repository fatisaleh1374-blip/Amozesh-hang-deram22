package com.example

import com.example.model.CanonicalAssessmentMetrics
import com.example.model.LearningSkill
import com.example.model.MasteredSkillState
import com.example.model.MasteredSkillUpdater
import com.example.model.PersonalizationEngine
import com.example.model.PracticeRecommendation
import com.example.model.SkillEvidenceMapper
import com.example.model.AssessmentEventType
import com.example.model.AssessmentTimeline
import com.example.model.AssessmentTimelineEvent
import com.example.model.DynamicsEvidenceSample
import com.example.model.HandpanTechnique
import com.example.model.SkillEvidenceCalculator
import com.example.model.SkillEvidenceRawMetrics
import com.example.model.TechniqueEvidenceEvent
import com.example.model.TimingResult
import com.example.model.TimingStatus
import com.example.model.AssessmentSessionValidity
import com.example.model.AssessmentSessionValidator
import com.example.model.SessionHistoryProvider
import com.example.model.Subdivision
import com.example.model.TimingToleranceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningEngineTest {
    @Test
    fun masteryMovesGraduallyAndConfidenceReflectsEvidence() {
        val initial = MasteredSkillState(skill = LearningSkill.TIMING)
        val evidence = MasteredSkillUpdater.evidenceFrom(
            CanonicalAssessmentMetrics(
                overallPerformance = 90f,
                timingScore = 92f,
                pitchScore = 80f,
                noteAccuracy = 85f,
                completionRate = 90f,
                missRate = 10f,
                falseStrikeRate = 0f,
                consistencyScore = 88f,
                confidenceScore = 95f
            )
        )

        val updated = MasteredSkillUpdater.update(
            initial,
            evidence[LearningSkill.TIMING]!!
        )

        assertTrue(updated.masteryScore in 0f..100f)
        assertTrue(updated.masteryScore < 90f)
        assertTrue(updated.attemptCount == 1)
        assertTrue(updated.confidence < 1f)
    }

    @Test
    fun personalizationSelectsWeakestSkillDeterministically() {
        val states = listOf(
            MasteredSkillState(LearningSkill.TIMING, masteryScore = 62f, attemptCount = 4),
            MasteredSkillState(LearningSkill.PITCH_ACCURACY, masteryScore = 48f, attemptCount = 4),
            MasteredSkillState(LearningSkill.CONSISTENCY, masteryScore = 75f, attemptCount = 4)
        )

        val recommendation = PersonalizationEngine.recommend(states, recentPatternIds = setOf("pattern-a"))

        assertEquals(LearningSkill.PITCH_ACCURACY, recommendation.skill)
        assertEquals(PracticeRecommendation.FOCUS_WEAKNESS, recommendation.reason)
        assertEquals("pattern-pitch-accuracy", recommendation.patternId)
    }

    @Test
    fun skillEvidenceMustBeSkillSpecific() {
        val timeline = AssessmentTimeline()
        timeline.append(
            AssessmentTimelineEvent(
                eventId = "timing-1",
                sessionId = "session",
                loopId = null,
                sequenceIndex = 0,
                expectedNote = 0,
                detectedNote = 0,
                eventType = AssessmentEventType.CORRECT,
                expectedTimestampNanos = 1_000_000_000L,
                detectedTimestampNanos = 1_020_000_000L,
                deviationNanos = 20_000_000L,
                timingResult = TimingResult(TimingStatus.EXCELLENT, 20_000_000L),
                confidence = 0.9f,
                targetId = "target",
                source = "test",
                durationNanos = null,
                isConsumed = true,
                measuredAmplitude = 0.7f,
                measuredVelocity = 0.75f,
                accentStrength = 0.8f,
                expectedTechnique = HandpanTechnique.DING,
                detectedTechnique = HandpanTechnique.DING,
                targetBpm = 100
            )
        )
        val event = timeline.snapshot().single()
        assertEquals(0.7f, event.measuredAmplitude!!, 0.001f)
        assertEquals(HandpanTechnique.DING, event.detectedTechnique)
        assertEquals(100, event.targetBpm)
        val metrics = SkillEvidenceCalculator.calculate(
            timeline = timeline,
            rawMetrics = SkillEvidenceRawMetrics(
                subdivisionAccuracy = 61f,
                beatStability = 71f,
                targetBpm = 100,
                successfulBpm = 80,
                performanceAccuracy = 90f,
                dynamicsSamples = listOf(
                    DynamicsEvidenceSample(0.8f, 0.7f, 0.8f, 0.75f, true, 0.9f)
                ),
                techniqueEvents = listOf(
                    TechniqueEvidenceEvent(HandpanTechnique.DING, HandpanTechnique.DING, 0.8f)
                )
            )
        )

        val evidence = SkillEvidenceMapper.from(metrics)

        assertTrue(evidence[LearningSkill.RHYTHM]!!.performanceScore > 0f)
        assertTrue(evidence[LearningSkill.RHYTHM]!!.performanceScore != metrics.completionRate)
        assertTrue(evidence[LearningSkill.DYNAMICS]!!.performanceScore > 0f)
        assertTrue(evidence[LearningSkill.SPEED]!!.performanceScore > 0f)
        assertTrue(evidence[LearningSkill.TECHNIQUE]!!.performanceScore > 0f)
        assertEquals(90f, evidence[LearningSkill.RHYTHM]!!.confidence, 0.001f)
    }

    @Test
    fun assessmentEventMustContainTargetContext() {
        val event = contextualEvent()

        assertEquals("pattern-1", event.patternId)
        assertEquals("target-1", event.targetNoteId)
        assertEquals(90, event.targetBpm)
        assertEquals(Subdivision.SIXTEENTH, event.subdivision)
        assertEquals(1.25, event.beatPosition!!, 0.001)
        assertEquals(HandpanTechnique.TONE, event.expectedTechnique)
        assertEquals(AssessmentSessionValidity.VALID, event.sessionValidity)
    }

    @Test
    fun invalidSessionMustNotCreateSkillEvidence() {
        val timeline = AssessmentTimeline()
        timeline.append(contextualEvent(sessionValidity = AssessmentSessionValidity.INVALID_SIGNAL))

        val quality = AssessmentSessionValidator.validate(timeline, 2_000L, 0.9f, 0)
        assertEquals(null, SkillEvidenceCalculator.calculateValidEvidence(timeline, quality))
    }

    @Test
    fun rhythmEvidenceRequiresBeatContext() {
        val complete = contextualTimeline(eventCount = 2)
        val incomplete = AssessmentTimeline()
        incomplete.append(
            contextualEvent(
                subdivision = null,
                beatPosition = null,
                expectedTimingWindow = null
            )
        )

        val completeQuality = AssessmentSessionValidator.validate(complete, 2_000L, 0.9f, 0)
        val incompleteQuality = AssessmentSessionValidator.validate(incomplete, 2_000L, 0.9f, 0)
        val completeRhythm = SkillEvidenceCalculator.calculateValidEvidence(complete, completeQuality)!!.rhythmScore
        val incompleteEvidence = SkillEvidenceCalculator.calculateValidEvidence(incomplete, incompleteQuality)

        assertTrue(completeRhythm > 0f)
        assertEquals(null, incompleteEvidence)
    }

    @Test
    fun invalidAssessmentSessionMustNotAffectMastery() {
        val quality = AssessmentSessionValidator.validate(
            timeline = contextualTimeline(eventCount = 2),
            durationMs = 100L,
            signalQuality = 0.9f,
            restartCount = 0
        )

        assertEquals(AssessmentSessionValidity.INVALID_DURATION, quality.validity)
        assertEquals(null, SkillEvidenceCalculator.calculateValidEvidence(
            contextualTimeline(eventCount = 2),
            quality
        ))
    }

    @Test
    fun masteryMustUseOnlyValidSessions() {
        val validTimeline = contextualTimeline(eventCount = 2)
        val invalidTimeline = contextualTimeline(
            eventCount = 2,
            sessionValidity = AssessmentSessionValidity.INVALID_SIGNAL
        )
        val validQuality = AssessmentSessionValidator.validate(validTimeline, 2_000L, 0.9f, 0)
        val invalidQuality = AssessmentSessionValidator.validate(invalidTimeline, 2_000L, 0.9f, 0)

        assertTrue(SkillEvidenceCalculator.calculateValidEvidence(validTimeline, validQuality) != null)
        assertEquals(null, SkillEvidenceCalculator.calculateValidEvidence(invalidTimeline, invalidQuality))
    }

    @Test
    fun consistencyRequiresMultipleValidSessions() {
        val provider = object : SessionHistoryProvider {
            override fun recentSessions(): List<AssessmentTimeline> =
                listOf(contextualTimeline(eventCount = 2))
        }

        assertEquals(null, SkillEvidenceCalculator.calculateConsistency(provider))
    }

    private fun contextualTimeline(
        eventCount: Int,
        sessionValidity: AssessmentSessionValidity = AssessmentSessionValidity.VALID
    ): AssessmentTimeline {
        val timeline = AssessmentTimeline()
        repeat(eventCount) { index ->
            timeline.append(
                contextualEvent(
                    eventId = "context-event-$index",
                    sessionValidity = sessionValidity
                )
            )
        }
        return timeline
    }

    private fun contextualEvent(
        eventId: String = "context-event",
        sessionValidity: AssessmentSessionValidity = AssessmentSessionValidity.VALID,
        subdivision: Subdivision? = Subdivision.SIXTEENTH,
        beatPosition: Double? = 1.25,
        expectedTimingWindow: TimingToleranceProfile? = TimingToleranceProfile(
            perfectWindowNanos = 20_000_000L,
            excellentWindowNanos = 40_000_000L,
            goodWindowNanos = 80_000_000L,
            missWindowNanos = 160_000_000L
        )
    ) = AssessmentTimelineEvent(
        eventId = eventId,
        sessionId = "session",
        loopId = "loop-1",
        sequenceIndex = 0,
        expectedNote = 1,
        detectedNote = 1,
        eventType = AssessmentEventType.CORRECT,
        expectedTimestampNanos = 1_000_000_000L,
        detectedTimestampNanos = 1_020_000_000L,
        deviationNanos = 20_000_000L,
        timingResult = TimingResult(TimingStatus.EXCELLENT, 20_000_000L),
        confidence = 0.9f,
        targetId = "target-1",
        source = "microphone",
        durationNanos = null,
        isConsumed = true,
        patternId = "pattern-1",
        targetNoteId = "target-1",
        targetBpm = 90,
        subdivision = subdivision,
        beatPosition = beatPosition,
        expectedTimingWindow = expectedTimingWindow,
        expectedTechnique = HandpanTechnique.TONE,
        detectedTechnique = HandpanTechnique.TONE,
        sessionValidity = sessionValidity
    )
}