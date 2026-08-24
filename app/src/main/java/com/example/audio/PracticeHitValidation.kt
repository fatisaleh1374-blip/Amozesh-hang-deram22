package com.example.audio

import com.example.model.TimingPolicy
import com.example.model.TimingStatus
import kotlin.math.abs

data class HitCandidate(
    val timestampNanos: Long,
    val detectedNote: Int?,
    val confidence: Float,
    val source: String
)

data class ValidatedHit(
    val expectedNote: Int?,
    val detectedNote: Int?,
    val timingErrorMs: Long,
    val pitchCorrect: Boolean,
    val timingCorrect: Boolean,
    val scoreContribution: Int,
    val timingStatus: TimingStatus,
    val isMiss: Boolean
)

object PracticeHitValidator {
    fun validate(
        candidate: HitCandidate?,
        expectedTimestampNanos: Long,
        expectedNote: Int?,
        timingPolicy: TimingPolicy = TimingPolicy(),
        minimumConfidence: Float = 0.5f
    ): ValidatedHit {
        if (candidate == null) {
            return ValidatedHit(
                expectedNote = expectedNote,
                detectedNote = null,
                timingErrorMs = 0L,
                pitchCorrect = false,
                timingCorrect = false,
                scoreContribution = 0,
                timingStatus = TimingStatus.OUTSIDE_WINDOW,
                isMiss = true
            )
        }

        val errorNanos = candidate.timestampNanos - expectedTimestampNanos
        val timingStatus = when {
            abs(errorNanos) <= timingPolicy.perfectWindowNanos -> TimingStatus.PERFECT
            abs(errorNanos) <= timingPolicy.goodWindowNanos -> TimingStatus.GOOD
            errorNanos < 0 && abs(errorNanos) <= timingPolicy.earlyWindowNanos -> TimingStatus.EARLY
            errorNanos > 0 && errorNanos <= timingPolicy.lateWindowNanos -> TimingStatus.LATE
            else -> TimingStatus.OUTSIDE_WINDOW
        }
        val pitchCorrect = expectedNote != null &&
            candidate.detectedNote == expectedNote &&
            candidate.confidence >= minimumConfidence
        val timingCorrect = timingStatus != TimingStatus.OUTSIDE_WINDOW
        val score = when {
            !pitchCorrect || !timingCorrect -> 0
            timingStatus == TimingStatus.PERFECT -> 100
            timingStatus == TimingStatus.GOOD -> 80
            else -> 50
        }

        return ValidatedHit(
            expectedNote = expectedNote,
            detectedNote = candidate.detectedNote,
            timingErrorMs = errorNanos / 1_000_000L,
            pitchCorrect = pitchCorrect,
            timingCorrect = timingCorrect,
            scoreContribution = score,
            timingStatus = timingStatus,
            isMiss = false
        )
    }
}