package com.example

import com.example.model.PracticeScoreCalculator
import com.example.model.ScoreCounters
import org.junit.Assert.assertEquals
import org.junit.Test

class PracticeScoreCalculatorTest {
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
}