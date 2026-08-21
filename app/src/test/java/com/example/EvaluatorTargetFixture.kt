package com.example

import com.example.audio.AcousticPracticeEvaluator
import com.example.audio.PatternScheduler
import com.example.model.NoteEvent
import com.example.model.TimeSignature
import java.util.concurrent.atomic.AtomicInteger

private val testLoopIndex = AtomicInteger()

fun AcousticPracticeEvaluator.notifyExpectedTestTarget(
    events: List<NoteEvent>,
    targetTimestampNanos: Long
) {
    val activeEvents = events.filterNot(NoteEvent::isRest)
    if (activeEvents.isEmpty()) return
    val target = PatternScheduler.buildSchedule(
        events = activeEvents,
        beatsPerBar = 4,
        totalBars = 1,
        timeSignature = TimeSignature.Common44,
        assessmentSessionId = assessmentSessionIdForTesting,
        patternId = "test-pattern",
        loopIndex = testLoopIndex.getAndIncrement(),
        scheduleStartTimestampNanos = targetTimestampNanos,
        bpm = 60
    ).firstNotNullOfOrNull { it.target } ?: return
    notifyExpectedTarget(target)
}
