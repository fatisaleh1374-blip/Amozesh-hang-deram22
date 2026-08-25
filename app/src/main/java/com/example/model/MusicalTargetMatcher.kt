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

data class TimingPolicy(
    val earlyWindowNanos: Long = 160_000_000L,
    val lateWindowNanos: Long = 160_000_000L,
    val perfectWindowNanos: Long = 45_000_000L,
    val goodWindowNanos: Long = 90_000_000L,
    val excellentWindowNanos: Long = goodWindowNanos
) {
    init {
        require(perfectWindowNanos >= 0)
        require(goodWindowNanos >= perfectWindowNanos)
        require(excellentWindowNanos >= perfectWindowNanos)
        require(goodWindowNanos >= excellentWindowNanos)
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

class MusicalTargetMatcher {
    /** Candidate selection and classification are pure; TargetRegistry owns all mutation. */
    fun selectCandidate(
        targets: Collection<MusicalTarget>,
        event: DetectedStrikeEvent,
        sessionId: String = event.sessionId,
        policy: TimingPolicy = TimingPolicy()
    ): MusicalTarget? = targets.asSequence()
        .filter { !it.finalized && it.identity.sessionId == sessionId }
        .filter { it.remainingNotes.isNotEmpty() }
        .filter { withinWindow(it.identity.expectedTimestampNanos, event.monotonicTimestampNanos, policy) }
        .sortedWith(
            compareBy<MusicalTarget> {
                abs(event.monotonicTimestampNanos - it.identity.expectedTimestampNanos)
            }.thenBy { it.identity.sequenceIndex }
                .thenBy { it.identity.loopId }
                .thenBy { it.identity.targetId }
        )
        .firstOrNull()

    fun classify(
        candidate: MusicalTarget?,
        event: DetectedStrikeEvent,
        policy: TimingPolicy = TimingPolicy()
    ): TargetMatchDecision {
        if (candidate == null) return TargetMatchDecision(TargetMatchType.EXTRA, null, null, null)

        val deviation = event.monotonicTimestampNanos - candidate.identity.expectedTimestampNanos
        val type = when {
            !event.pitchValid || event.detectedNote == null -> TargetMatchType.UNKNOWN
            event.detectedNote in candidate.remainingNotes -> TargetMatchType.CORRECT
            else -> TargetMatchType.WRONG
        }
        return TargetMatchDecision(
            type = type,
            target = candidate,
            timing = timingFor(deviation, policy),
            consumedNote = if (type == TargetMatchType.CORRECT) event.detectedNote else null
        )
    }

    fun finalizeCandidates(
        targets: Collection<MusicalTarget>,
        nowNanos: Long,
        policy: TimingPolicy = TimingPolicy()
    ): List<MusicalTarget> =
        targets.filter {
            !it.finalized && !it.isConsumed &&
                nowNanos > it.identity.expectedTimestampNanos + policy.lateWindowNanos
        }

    private fun withinWindow(expected: Long, detected: Long, policy: TimingPolicy): Boolean {
        val deviation = detected - expected
        return deviation >= -policy.earlyWindowNanos && deviation <= policy.lateWindowNanos
    }

    private fun timingFor(deviationNanos: Long, policy: TimingPolicy): TimingResult {
        val status = when {
            abs(deviationNanos) <= policy.perfectWindowNanos -> TimingStatus.PERFECT
            abs(deviationNanos) <= policy.excellentWindowNanos -> TimingStatus.EXCELLENT
            abs(deviationNanos) <= policy.goodWindowNanos -> TimingStatus.GOOD
            deviationNanos < 0 -> TimingStatus.EARLY
            deviationNanos <= policy.lateWindowNanos -> TimingStatus.LATE
            else -> TimingStatus.OUTSIDE_WINDOW
        }
        return TimingResult(status, deviationNanos)
    }
}

class TargetRegistry {
    private val active = linkedMapOf<String, MusicalTarget>()
    private val finalized = linkedMapOf<String, MusicalTarget>()
    private val consumedObligations = mutableMapOf<String, MutableSet<Int>>()
    private val processedEventIds = mutableSetOf<String>()

    fun register(target: MusicalTarget) {
        require(target.identity.targetId !in active && target.identity.targetId !in finalized) {
            "Duplicate target identity: ${target.identity.targetId}"
        }
        active[target.identity.targetId] = target
        consumedObligations[target.identity.targetId] = target.consumedNotes.toMutableSet()
    }

    fun activeTargets(): List<MusicalTarget> = active.values.toList()

    fun markProcessed(eventId: String): Boolean = processedEventIds.add(eventId)

    fun apply(decision: TargetMatchDecision) {
        val target = decision.target ?: return
        val note = decision.consumedNote ?: return
        if (decision.type != TargetMatchType.CORRECT) return
        val consumed = consumedObligations.getOrPut(target.identity.targetId) { mutableSetOf() }
        if (consumed.add(note)) {
            active[target.identity.targetId] = target.copy(consumedNotes = consumed.toSet())
        }
    }

    fun finalize(targetId: String): MusicalTarget? {
        val target = active.remove(targetId) ?: return null
        val result = target.copy(finalized = true)
        finalized[targetId] = result
        return result
    }

    fun clear() {
        active.clear()
        finalized.clear()
        consumedObligations.clear()
        processedEventIds.clear()
    }
}