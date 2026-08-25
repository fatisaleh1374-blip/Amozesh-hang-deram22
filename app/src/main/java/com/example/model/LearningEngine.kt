package com.example.model

enum class LearningSkill {
    NOTE_ACCURACY,
    PITCH_ACCURACY,
    TIMING,
    RHYTHM,
    CONSISTENCY,
    DYNAMICS,
    SPEED,
    TECHNIQUE
}

data class SkillEvidence(
    val performanceScore: Float,
    val confidence: Float,
    val sampleCount: Int = 1
) {
    init {
        require(performanceScore in 0f..100f)
        require(confidence in 0f..100f)
        require(sampleCount > 0)
    }
}

data class MasteredSkillState(
    val skill: LearningSkill,
    val masteryScore: Float = 0f,
    val confidence: Float = 0f,
    val recentPerformance: Float = 0f,
    val longTermPerformance: Float = 0f,
    val attemptCount: Int = 0,
    val lastPracticedEpochMs: Long? = null,
    val trend: Float = 0f
) {
    init {
        require(masteryScore in 0f..100f)
        require(confidence in 0f..1f)
        require(recentPerformance in 0f..100f)
        require(longTermPerformance in 0f..100f)
        require(attemptCount >= 0)
        require(trend in -100f..100f)
    }
}

object MasteredSkillUpdater {
    fun evidenceFrom(metrics: CanonicalAssessmentMetrics): Map<LearningSkill, SkillEvidence> = mapOf(
        LearningSkill.NOTE_ACCURACY to evidence(metrics.noteAccuracy, metrics.confidenceScore),
        LearningSkill.PITCH_ACCURACY to evidence(metrics.pitchScore, metrics.confidenceScore),
        LearningSkill.TIMING to evidence(metrics.timingScore, metrics.confidenceScore),
        LearningSkill.RHYTHM to evidence(metrics.completionRate, metrics.confidenceScore),
        LearningSkill.CONSISTENCY to evidence(metrics.consistencyScore, metrics.confidenceScore),
        LearningSkill.DYNAMICS to evidence(metrics.confidenceScore, metrics.confidenceScore),
        LearningSkill.SPEED to evidence(metrics.completionRate, metrics.confidenceScore),
        LearningSkill.TECHNIQUE to evidence(metrics.noteAccuracy, metrics.confidenceScore)
    )

    fun update(
        state: MasteredSkillState,
        evidence: SkillEvidence,
        learningRate: Float = 0.25f,
        practicedEpochMs: Long? = null
    ): MasteredSkillState {
        require(learningRate in 0f..1f)
        val weightedEvidence = evidence.performanceScore * (evidence.confidence / 100f)
        val nextRecent = evidence.performanceScore
        val nextLongTerm = if (state.attemptCount == 0) {
            evidence.performanceScore
        } else {
            state.longTermPerformance * 0.8f + evidence.performanceScore * 0.2f
        }
        val nextMastery = if (state.attemptCount == 0) {
            weightedEvidence * learningRate
        } else {
            state.masteryScore + (weightedEvidence - state.masteryScore) * learningRate
        }
        val nextAttempts = state.attemptCount + evidence.sampleCount
        return state.copy(
            masteryScore = nextMastery.coerceIn(0f, 100f),
            confidence = (1f - 1f / (nextAttempts + 1f)).coerceIn(0f, 1f),
            recentPerformance = nextRecent,
            longTermPerformance = nextLongTerm.coerceIn(0f, 100f),
            attemptCount = nextAttempts,
            lastPracticedEpochMs = practicedEpochMs,
            trend = (nextRecent - state.recentPerformance).coerceIn(-100f, 100f)
        )
    }

    private fun evidence(score: Float, confidence: Float): SkillEvidence = SkillEvidence(
        performanceScore = score.coerceIn(0f, 100f),
        confidence = confidence.coerceIn(0f, 100f)
    )
}

enum class PracticeRecommendation {
    FOCUS_WEAKNESS,
    REINFORCE,
    EXPLORE
}

data class LearningRecommendation(
    val skill: LearningSkill,
    val reason: PracticeRecommendation,
    val patternId: String
)

object PersonalizationEngine {
    fun recommend(
        states: List<MasteredSkillState>,
        recentPatternIds: Set<String> = emptySet()
    ): LearningRecommendation {
        val ordered = states.sortedWith(
            compareBy<MasteredSkillState> { it.masteryScore }
                .thenBy { it.skill.ordinal }
        )
        val selected = ordered.firstOrNull()
            ?: MasteredSkillState(LearningSkill.NOTE_ACCURACY)
        val patternId = "pattern-${selected.skill.name.lowercase().replace('_', '-')}"
        val fallbackPattern = if (patternId in recentPatternIds) {
            "review-${selected.skill.name.lowercase().replace('_', '-')}"
        } else {
            patternId
        }
        return LearningRecommendation(
            skill = selected.skill,
            reason = if (selected.masteryScore < 70f) {
                PracticeRecommendation.FOCUS_WEAKNESS
            } else {
                PracticeRecommendation.REINFORCE
            },
            patternId = fallbackPattern
        )
    }
}