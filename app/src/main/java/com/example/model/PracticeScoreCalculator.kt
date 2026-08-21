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
    fun calculate(timeline: AssessmentTimeline): PracticeScore =
        calculate(timeline.snapshot())

    fun calculate(events: List<AssessmentTimelineEvent>): PracticeScore {
        val results = events.filter { it.eventType != AssessmentEventType.EXPECTED }
        val correct = results.count { it.eventType == AssessmentEventType.CORRECT }
        val wrong = results.count { it.eventType == AssessmentEventType.WRONG }
        val unknown = results.count { it.eventType == AssessmentEventType.UNKNOWN }
        val missed = results.count { it.eventType == AssessmentEventType.MISSED }
        val extra = results.count { it.eventType == AssessmentEventType.EXTRA }
        fun timingPoints(event: AssessmentTimelineEvent): Int = when (event.timingResult?.status) {
            TimingStatus.PERFECT -> 100
            TimingStatus.GOOD -> 80
            TimingStatus.EARLY, TimingStatus.LATE -> 50
            TimingStatus.OUTSIDE_WINDOW, null -> 0
        }
        val nonCorrectTimingPoints = results
            .filter { it.eventType == AssessmentEventType.WRONG || it.eventType == AssessmentEventType.UNKNOWN }
            .sumOf(::timingPoints)
        return calculate(
            ScoreCounters(
                correctCount = correct,
                wrongCount = wrong,
                unknownCount = unknown,
                missedCount = missed,
                extraCount = extra,
                perfectCount = results.count { it.timingResult?.status == TimingStatus.PERFECT },
                goodCount = results.count { it.timingResult?.status == TimingStatus.GOOD },
                earlyCount = results.count { it.timingResult?.status == TimingStatus.EARLY },
                lateCount = results.count { it.timingResult?.status == TimingStatus.LATE },
                nonCorrectTimingPoints = nonCorrectTimingPoints
            )
        )
    }

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