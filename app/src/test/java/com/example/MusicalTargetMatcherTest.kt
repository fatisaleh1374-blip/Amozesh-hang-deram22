package com.example

import com.example.model.DetectedStrikeEvent
import com.example.model.MusicalTarget
import com.example.model.MusicalTargetIdentity
import com.example.model.MusicalTargetMatcher
import com.example.model.TargetMatchType
import com.example.model.TimingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicalTargetMatcherTest {
    @Test
    fun wrongThenCorrectKeepsTargetAvailable() {
        val matcher = matcherWith(target("target-1", setOf(0), 1_000_000_000L))

        val wrong = matcher.match(strike("wrong", 1, 1_000_000_000L), "loop-1")
        val correct = matcher.match(strike("correct", 0, 1_000_000_000L), "loop-1")

        assertEquals(TargetMatchType.WRONG, wrong.type)
        assertEquals(TargetMatchType.CORRECT, correct.type)
        assertTrue(matcher.targets().single().isConsumed)
    }

    @Test
    fun earlyStrikeSelectsFutureTargetAndIsEarly() {
        val matcher = matcherWith(target("future", setOf(0), 1_000_000_000L))

        val result = matcher.match(strike("early", 0, 900_000_000L), "loop-1")

        assertEquals(TargetMatchType.CORRECT, result.type)
        assertEquals(TimingStatus.EARLY, result.timing?.status)
    }

    @Test
    fun outsideWindowIsExtraAndDoesNotConsumeTarget() {
        val matcher = matcherWith(target("target-1", setOf(0), 1_000_000_000L))

        val result = matcher.match(strike("extra", 0, 1_300_000_000L), "loop-1")

        assertEquals(TargetMatchType.EXTRA, result.type)
        assertFalse(matcher.targets().single().isConsumed)
    }

    @Test
    fun duplicateEventIdIsIgnoredAfterFirstDecision() {
        val matcher = matcherWith(target("target-1", setOf(0), 1_000_000_000L))
        val event = strike("same-event", 0, 1_000_000_000L)

        val first = matcher.match(event, "loop-1")
        val duplicate = matcher.match(event, "loop-1")

        assertEquals(TargetMatchType.CORRECT, first.type)
        assertTrue(duplicate.duplicate)
        assertEquals(1, matcher.targets().single().consumedNotes.size)
    }

    @Test
    fun chordConsumesNotesIndependentlyAndFinalizesRemainderAsMissed() {
        val matcher = matcherWith(target("chord", setOf(0, 1), 1_000_000_000L))

        val first = matcher.match(strike("note-0", 0, 1_000_000_000L), "loop-1")
        val missed = matcher.finalize(1_200_000_000L)

        assertEquals(TargetMatchType.CORRECT, first.type)
        assertEquals(1, missed.size)
        assertEquals(TargetMatchType.MISSED, missed.single().type)
        assertEquals(setOf(1), matcher.targets().single().remainingNotes)
    }

    @Test
    fun loopIdentityPreventsCrossLoopMatching() {
        val matcher = matcherWith(
            target("loop-1-target", setOf(0), 1_000_000_000L, loopId = "loop-1"),
            target("loop-2-target", setOf(0), 1_000_000_000L, loopId = "loop-2")
        )

        val result = matcher.match(strike("loop-2-strike", 0, 1_000_000_000L), "loop-2")

        assertEquals("loop-2-target", result.target?.identity?.targetId)
        assertFalse(matcher.targets().first { it.identity.loopId == "loop-1" }.isConsumed)
    }

    @Test
    fun unknownStrikeDoesNotConsumeChordNote() {
        val matcher = matcherWith(target("chord", setOf(0, 1), 1_000_000_000L))

        val result = matcher.match(strike("unknown", null, 1_000_000_000L), "loop-1")

        assertEquals(TargetMatchType.UNKNOWN, result.type)
        assertEquals(setOf(0, 1), matcher.targets().single().remainingNotes)
    }

    @Test
    fun chordOrderIsIrrelevantAndExtraNoteDoesNotConsumeRemainingNotes() {
        val matcher = matcherWith(target("chord", setOf(0, 1), 1_000_000_000L))

        val first = matcher.match(strike("note-1", 1, 1_000_000_000L), "loop-1")
        val extra = matcher.match(strike("note-extra", 2, 1_000_000_000L), "loop-1")
        val second = matcher.match(strike("note-0", 0, 1_000_000_000L), "loop-1")

        assertEquals(TargetMatchType.CORRECT, first.type)
        assertEquals(TargetMatchType.WRONG, extra.type)
        assertEquals(TargetMatchType.CORRECT, second.type)
        assertTrue(matcher.targets().single().isConsumed)
    }

    @Test
    fun equalDistanceUsesSequenceAsDeterministicTieBreaker() {
        val matcher = matcherWith(
            target("later", setOf(0), 1_100_000_000L, sequence = 2),
            target("earlier", setOf(0), 900_000_000L, sequence = 1)
        )

        val result = matcher.match(strike("tie", 0, 1_000_000_000L), "loop-1")

        assertEquals("earlier", result.target?.identity?.targetId)
    }

    @Test
    fun targetCannotBeMatchedAfterDeadlineFinalization() {
        val matcher = matcherWith(target("expired", setOf(0), 1_000_000_000L))

        val missed = matcher.finalize(1_200_000_000L)
        val late = matcher.match(strike("late", 0, 1_200_000_000L), "loop-1")

        assertEquals(TargetMatchType.MISSED, missed.single().type)
        assertEquals(TargetMatchType.EXTRA, late.type)
    }

    private fun matcherWith(vararg targets: MusicalTarget): MusicalTargetMatcher {
        return MusicalTargetMatcher().also { matcher -> targets.forEach(matcher::addTarget) }
    }

    private fun target(
        id: String,
        notes: Set<Int>,
        timestamp: Long,
        loopId: String = "loop-1",
        sequence: Int = 0
    ) = MusicalTarget(
        MusicalTargetIdentity(
            sessionId = "session-1",
            patternId = "pattern-1",
            loopId = loopId,
            sequenceIndex = sequence,
            targetId = id,
            beatIndex = sequence,
            subdivisionIndex = 0,
            expectedTimestampNanos = timestamp,
            expectedNotes = notes,
            chordId = if (notes.size > 1) id else ""
        )
    )

    private fun strike(id: String, note: Int?, timestamp: Long) = DetectedStrikeEvent(
        id = id,
        sessionId = "session-1",
        monotonicTimestampNanos = timestamp,
        detectedFrequencyHz = 220f,
        detectedNoteName = "A3",
        detectedCentsOffset = 0,
        detectedNote = note,
        matchedPitchDiffHz = 0f,
        pitchConfidence = if (note == null) 0.2f else 0.9f,
        onsetStrength = 0.8f,
        energy = 0.8f,
        pitchValid = note != null,
        source = "test"
    )
}