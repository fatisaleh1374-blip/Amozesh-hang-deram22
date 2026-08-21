package com.example.model

data class ScoreCounters(
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val unknownCount: Int = 0,
    val missedCount: Int = 0,
    val extraCount: Int = 0,
    val perfectCount: Int = 0,
    val goodCount: Int = 0,
    val earlyCount: Int = 0,
    val lateCount: Int = 0,
    val nonCorrectTimingPoints: Int = 0
)

object PracticeScoreCalculator {
    fun calculate(counters: ScoreCounters): PracticeScore {
        val noteDenominator = counters.correctCount + counters.wrongCount +
            counters.unknownCount + counters.missedCount
        val timingDenominator = counters.correctCount + counters.wrongCount + counters.unknownCount
        val timingPoints = counters.perfectCount * 100 +
            counters.goodCount * 80 +
            counters.earlyCount * 50 +
            counters.lateCount * 50 +
            counters.nonCorrectTimingPoints

        val noteAccuracy = percentage(counters.correctCount, noteDenominator)
        val timingAccuracy = percentage(timingPoints, timingDenominator * 100)
        val overall = if (noteDenominator == 0 && timingDenominator == 0) {
            0f
        } else {
            ((noteAccuracy + timingAccuracy) / 2f).coerceIn(0f, 100f)
        }

        return PracticeScore(
            correctCount = counters.correctCount,
            wrongCount = counters.wrongCount,
            unknownCount = counters.unknownCount,
            missedCount = counters.missedCount,
            extraCount = counters.extraCount,
            noteAccuracyPercentage = noteAccuracy,
            timingAccuracyPercentage = timingAccuracy,
            overallAccuracyPercentage = overall
        )
    }

    private fun percentage(numerator: Int, denominator: Int): Float {
        if (denominator <= 0) return 0f
        return (numerator.toFloat() / denominator.toFloat() * 100f).coerceIn(0f, 100f)
    }
}