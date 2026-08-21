package com.example.model

import kotlin.math.abs

data class MusicalTargetIdentity(
    val sessionId: String,
    val patternId: String,
    val loopId: String,
    val sequenceIndex: Int,
    val targetId: String,
    val beatIndex: Int,
    val subdivisionIndex: Int,
    val expectedTimestampNanos: Long,
    val expectedNotes: Set<Int>,
    val chordId: String
)

data class MusicalTarget(
    val identity: MusicalTargetIdentity,
    val consumedNotes: Set<Int> = emptySet(),
    val finalized: Boolean = false
) {
    val remainingNotes: Set<Int>
        get() = identity.expectedNotes - consumedNotes

    val isConsumed: Boolean
        get() = remainingNotes.isEmpty()
}

data class MatchingPolicy(
    val earlyWindowNanos: Long = 160_000_000L,
    val lateWindowNanos: Long = 160_000_000L,
    val perfectWindowNanos: Long = 45_000_000L,
    val goodWindowNanos: Long = 90_000_000L
) {
    init {
        require(perfectWindowNanos >= 0)
        require(goodWindowNanos >= perfectWindowNanos)
        require(earlyWindowNanos >= goodWindowNanos)
        require(lateWindowNanos >= goodWindowNanos)
    }
}

enum class TargetMatchType {
    CORRECT,
    WRONG,
    UNKNOWN,
    EXTRA,
    MISSED
}

data class TargetMatchDecision(
    val type: TargetMatchType,
    val target: MusicalTarget?,
    val timing: TimingResult?,
    val consumedNote: Int?,
    val duplicate: Boolean = false
)

class MusicalTargetMatcher(
    private val policy: MatchingPolicy = MatchingPolicy()
) {
    private val targets = linkedMapOf<String, MusicalTarget>()
    private val processedEventIds = mutableSetOf<String>()

    fun addTarget(target: MusicalTarget) {
        require(target.identity.targetId !in targets) {
            "Duplicate target identity: ${target.identity.targetId}"
        }
        targets[target.identity.targetId] = target
    }

    fun targets(): List<MusicalTarget> = targets.values.toList()

    fun clear() {
        targets.clear()
        processedEventIds.clear()
    }

    fun match(
        event: DetectedStrikeEvent,
        loopId: String,
        sessionId: String = event.sessionId
    ): TargetMatchDecision {
        if (!processedEventIds.add(event.id)) {
            return TargetMatchDecision(TargetMatchType.EXTRA, null, null, null, duplicate = true)
        }

        val candidate = targets.values
            .asSequence()
            .filter { !it.finalized && it.identity.sessionId == sessionId }
            .filter { it.identity.loopId == loopId }
            .filter { it.remainingNotes.isNotEmpty() }
            .filter { withinWindow(it.identity.expectedTimestampNanos, event.monotonicTimestampNanos) }
            .sortedWith(
                compareBy<MusicalTarget> {
                    abs(event.monotonicTimestampNanos - it.identity.expectedTimestampNanos)
                }.thenBy { it.identity.sequenceIndex }
                    .thenBy { it.identity.targetId }
            )
            .firstOrNull()

        if (candidate == null) {
            return TargetMatchDecision(TargetMatchType.EXTRA, null, null, null)
        }

        val deviation = event.monotonicTimestampNanos - candidate.identity.expectedTimestampNanos
        val timing = timingFor(deviation)
        val type = when {
            !event.pitchValid || event.detectedNote == null -> TargetMatchType.UNKNOWN
            event.detectedNote in candidate.remainingNotes -> TargetMatchType.CORRECT
            else -> TargetMatchType.WRONG
        }
        val consumed = if (type == TargetMatchType.CORRECT) event.detectedNote else null
        if (consumed != null) {
            targets[candidate.identity.targetId] = candidate.copy(
                consumedNotes = candidate.consumedNotes + consumed
            )
        }
        return TargetMatchDecision(type, candidate, timing, consumed)
    }

    fun finalize(nowNanos: Long): List<TargetMatchDecision> {
        val finalized = mutableListOf<TargetMatchDecision>()
        targets.values.toList().forEach { target ->
            if (!target.finalized && !target.isConsumed &&
                nowNanos > target.identity.expectedTimestampNanos + policy.lateWindowNanos
            ) {
                targets[target.identity.targetId] = target.copy(finalized = true)
                finalized += TargetMatchDecision(TargetMatchType.MISSED, target, null, null)
            }
        }
        return finalized
    }

    private fun withinWindow(expected: Long, detected: Long): Boolean {
        val deviation = detected - expected
        return deviation >= -policy.earlyWindowNanos && deviation <= policy.lateWindowNanos
    }

    private fun timingFor(deviationNanos: Long): TimingResult {
        val status = when {
            abs(deviationNanos) <= policy.perfectWindowNanos -> TimingStatus.PERFECT
            abs(deviationNanos) <= policy.goodWindowNanos -> TimingStatus.GOOD
            deviationNanos < 0 -> TimingStatus.EARLY
            deviationNanos <= policy.lateWindowNanos -> TimingStatus.LATE
            else -> TimingStatus.OUTSIDE_WINDOW
        }
        return TimingResult(status, deviationNanos)
    }
}