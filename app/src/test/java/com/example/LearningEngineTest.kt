package com.example

import com.example.model.CanonicalAssessmentMetrics
import com.example.model.LearningSkill
import com.example.model.MasteredSkillState
import com.example.model.MasteredSkillUpdater
import com.example.model.PersonalizationEngine
import com.example.model.PracticeRecommendation
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

        val updated = MasteredSkillUpdater.update(initial, evidence)

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
}