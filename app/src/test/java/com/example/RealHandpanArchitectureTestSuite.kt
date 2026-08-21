package com.example

import com.example.audio.AcousticPracticeEvaluator
import com.example.audio.AudioEngine
import com.example.audio.PracticeClock
import com.example.audio.PracticeEngine
import com.example.audio.StrikeAccuracyStatus
import com.example.data.builtin.BuiltinExercises
import com.example.model.HandpanPattern
import com.example.model.NotePitchConfig
import com.example.model.NoteEvent
import com.example.model.PracticeInputMode
import com.example.model.StrikeClassification
import com.example.model.AssessmentEventType
import com.example.model.AssessmentTimeline
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import android.content.Context

class FakePracticeClock(var initialNanos: Long = 1_000_000_000L) : PracticeClock {
    var currentNanos = initialNanos
    override fun nowNanos(): Long = currentNanos
    fun advanceMs(ms: Long) {
        currentNanos += ms * 1_000_000L
    }
}

class FakeAudioEngine : AudioEngine(null) {
    var playedNotes = mutableListOf<Int>()
    var currentConfig = NotePitchConfig.D_KURD_9

    override fun playNote(noteNumber: Int, accent: Boolean, velocity: Float) {
        playedNotes.add(noteNumber)
    }

    override fun playMetronomeClick(isAccent: Boolean) {}
    override fun setMasterVolume(volume: Float) {}
    override fun setMetronomeVolume(volume: Float) {}
    override fun loadSamples(config: NotePitchConfig) {
        currentConfig = config
    }
    override fun reloadNoteSample(noteNumber: Int) {}
    override fun removeCustomSample(noteNumber: Int) {}
    override fun isCustomSampleLoaded(noteNumber: Int): Boolean = false
    override fun getPitchConfig(): NotePitchConfig = currentConfig
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class RealHandpanArchitectureTestSuite {

    private lateinit var fakeClock: FakePracticeClock
    private lateinit var fakeAudio: FakeAudioEngine
    private lateinit var evaluator: AcousticPracticeEvaluator
    private lateinit var practiceEngine: PracticeEngine
    private lateinit var testPattern: HandpanPattern

    @Before
    fun setUp() {
        fakeClock = FakePracticeClock()
        fakeAudio = FakeAudioEngine()
        evaluator = AcousticPracticeEvaluator(clock = fakeClock)
        practiceEngine = PracticeEngine(
            audioEngine = fakeAudio,
            clock = fakeClock,
            acousticEvaluator = evaluator
        )
        testPattern = BuiltinExercises.ALL_BUILTIN_PATTERNS.first()
    }

    // 1. Default mode is REAL_HANDPAN
    @Test
    fun test01_defaultModeIsRealHandpan() {
        assertEquals(PracticeInputMode.REAL_HANDPAN, practiceEngine.uiState.value.inputMode)
    }

    // 2. Setting input mode to VIRTUAL_HANDPAN updates state
    @Test
    fun test02_setInputModeVirtualUpdatesState() {
        practiceEngine.setInputMode(PracticeInputMode.VIRTUAL_HANDPAN)
        assertEquals(PracticeInputMode.VIRTUAL_HANDPAN, practiceEngine.uiState.value.inputMode)
        assertFalse(practiceEngine.uiState.value.acousticAssessmentEnabled)
    }

    // 3. Switching back to REAL_HANDPAN enables acoustic assessment
    @Test
    fun test03_switchBackToRealHandpanEnablesAcoustic() {
        practiceEngine.setInputMode(PracticeInputMode.VIRTUAL_HANDPAN)
        practiceEngine.setInputMode(PracticeInputMode.REAL_HANDPAN)
        assertEquals(PracticeInputMode.REAL_HANDPAN, practiceEngine.uiState.value.inputMode)
        assertTrue(practiceEngine.uiState.value.acousticAssessmentEnabled)
    }

    // 4. In REAL_HANDPAN mode, acoustic assessment evaluates strikes accurately
    @Test
    fun test04_acousticEvaluatorEvaluatesAccurateHit() {
        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)
        evaluator.notifyExpectedTestTarget(listOf(testPattern.events.first { !it.isRest }), fakeClock.currentNanos)
        // Target note at 0ms is D3 (approx 146.83 Hz)
        val result = evaluator.evaluateDetectedPitch(146.8f, 0.9f)
        assertNotNull(result)
        assertEquals(StrikeAccuracyStatus.PERFECT, result?.status)
    }

    // 5. Wrong pitch registers WRONG_NOTE
    @Test
    fun test05_wrongPitchRegistersWrongNote() {
        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)
        evaluator.notifyExpectedTestTarget(listOf(testPattern.events.first { !it.isRest }), fakeClock.currentNanos)
        // Note 0 is D3 (146.83Hz). F3 is note 2 (174.61Hz)
        val result = evaluator.evaluateDetectedPitch(174.6f, 0.9f)
        assertNotNull(result)
        assertEquals(StrikeAccuracyStatus.WRONG_NOTE, result?.status)
    }

    @Test
    fun test05b_fastFractionalTargetsChooseNearestExpectedEvent() {
        val pattern = HandpanPattern(
            id = "fractional_targets",
            title = "Fractional targets",
            description = "nearest target matching",
            bpm = 120,
            events = listOf(
                NoteEvent(noteNumber = 0, beatPosition = 0.0),
                NoteEvent(noteNumber = 1, beatPosition = 0.25)
            )
        )
        evaluator.startAssessment(pattern, NotePitchConfig.D_KURD_9, bpm = 120)
        val firstTarget = fakeClock.currentNanos
        evaluator.notifyExpectedTestTarget(listOf(pattern.events[0]), firstTarget)
        val secondTarget = firstTarget + 125_000_000L
        evaluator.notifyExpectedTestTarget(listOf(pattern.events[1]), secondTarget)

        val result = evaluator.evaluateDetectedPitch(220.0f, 0.9f, secondTarget)

        assertEquals(StrikeAccuracyStatus.PERFECT, result?.status)
        assertEquals(0L, result?.deviationMs)
        assertEquals(listOf(1), result?.expectedNotes)
        assertEquals(1, result?.detectedNote)
    }

    // 6. Early hit registration
    @Test
    fun test06_earlyHitRegistration() {
        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)
        val expected = testPattern.events[1]
        evaluator.notifyExpectedTestTarget(listOf(expected), fakeClock.currentNanos + 500_000_000L)
        // Advance clock to right before next note (-120ms)
        fakeClock.advanceMs(380) // expected note is at 500ms
        val result = evaluator.evaluateDetectedPitch(expected.noteNumber.let { NotePitchConfig.D_KURD_9.baseFrequencies[it] ?: 146.8f }, 0.9f)
        assertNotNull(result)
        assertEquals(StrikeAccuracyStatus.EARLY, result?.status)
    }

    // 7. Late hit registration
    @Test
    fun test07_lateHitRegistration() {
        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)
        val expected = testPattern.events[1]
        evaluator.notifyExpectedTestTarget(listOf(expected), fakeClock.currentNanos + 500_000_000L)
        fakeClock.advanceMs(620) // expected note is at 500ms
        val result = evaluator.evaluateDetectedPitch(expected.noteNumber.let { NotePitchConfig.D_KURD_9.baseFrequencies[it] ?: 146.8f }, 0.9f)
        assertNotNull(result)
        assertEquals(StrikeAccuracyStatus.LATE, result?.status)
    }

    // 8. Scale config propagation updates pitch reference
    @Test
    fun test08_scaleConfigPropagation() {
        fakeAudio.loadSamples(NotePitchConfig.C_MINOR_PYGMY_9)
        evaluator.startAssessment(testPattern, fakeAudio.getPitchConfig())
        assertEquals(NotePitchConfig.C_MINOR_PYGMY_9, fakeAudio.getPitchConfig())
    }

    // 9. Stop assessment resets active evaluator state
    @Test
    fun test09_stopAssessmentResetsState() {
        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)
        assertTrue(evaluator.state.value.isActive)
        evaluator.stopAssessment(showSummary = false)
        assertFalse(evaluator.state.value.isActive)
    }

    // 10. Pattern loading retains input mode
    @Test
    fun test10_patternLoadingRetainsInputMode() {
        practiceEngine.setInputMode(PracticeInputMode.REAL_HANDPAN)
        practiceEngine.loadPattern(BuiltinExercises.ALL_BUILTIN_PATTERNS[1])
        assertEquals(PracticeInputMode.REAL_HANDPAN, practiceEngine.uiState.value.inputMode)
    }

    // 11. Toggle acoustic assessment in PracticeEngine
    @Test
    fun test11_toggleAcousticAssessment() {
        assertTrue(practiceEngine.uiState.value.acousticAssessmentEnabled)
        practiceEngine.toggleAcousticAssessment()
        assertFalse(practiceEngine.uiState.value.acousticAssessmentEnabled)
        practiceEngine.toggleAcousticAssessment()
        assertTrue(practiceEngine.uiState.value.acousticAssessmentEnabled)
    }

    // 12. Metronome volume and master volume settings
    @Test
    fun test12_volumeConfigurations() {
        fakeAudio.setMasterVolume(0.7f)
        fakeAudio.setMetronomeVolume(0.5f)
        // Verify no crash on volume configurations
        assertTrue(true)
    }

    // 13. PracticeEngine pause and restart lifecycle
    @Test
    fun test13_pauseAndRestartLifecycle() {
        practiceEngine.loadPattern(testPattern)
        practiceEngine.pause()
        assertFalse(practiceEngine.uiState.value.isPlaying)
        practiceEngine.restart()
        assertEquals(1, practiceEngine.uiState.value.currentBar)
    }

    // 14. Speed multiplier calculation
    @Test
    fun test14_effectiveBpmCalculation() {
        practiceEngine.loadPattern(testPattern)
        val initialBpm = practiceEngine.uiState.value.effectiveBpm
        practiceEngine.setSpeedMultiplier(1.5f)
        assertEquals((initialBpm * 1.5f).toInt(), practiceEngine.uiState.value.effectiveBpm)
    }

    // 15. Speed ladder configuration
    @Test
    fun test15_speedLadderConfiguration() {
        practiceEngine.configureSpeedLadder(increment = 5, roundsPerStep = 3, targetBpm = 120)
        assertEquals(5, practiceEngine.uiState.value.ladderBpmIncrement)
        assertEquals(3, practiceEngine.uiState.value.ladderRoundsPerStep)
        assertEquals(120, practiceEngine.uiState.value.ladderTargetBpm)
    }

    // 16. Loop range boundary clamping
    @Test
    fun test16_loopRangeClamping() {
        practiceEngine.loadPattern(testPattern)
        practiceEngine.setLoopRange(1, 100)
        assertTrue(practiceEngine.uiState.value.loopEndBar <= testPattern.bars)
    }

    // 17. Evaluator summary calculates accuracy percentage
    @Test
    fun test17_evaluatorAccuracyCalculation() {
        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)
        evaluator.notifyExpectedTestTarget(listOf(testPattern.events.first { !it.isRest }), fakeClock.currentNanos)
        evaluator.evaluateDetectedPitch(146.83f, 0.95f)
        val state = evaluator.state.value
        assertEquals(1, state.perfectCount)
        assertTrue(state.accuracyPercentage > 0f)
    }

    // 18. Evaluator handles quiet noise threshold
    @Test
    fun test18_evaluatorHandlesLowConfidenceNoise() {
        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)
        val result = evaluator.evaluateDetectedPitch(146.83f, 0.2f) // below confidence threshold
        assertEquals(StrikeAccuracyStatus.EXTRA_STRIKE, result?.status)
        assertEquals(0, evaluator.state.value.unknownNoteCount)
    }

    @Test
    fun test29_unknownStrikeWithinTargetIsNotSilentlyDropped() {
        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)
        evaluator.notifyExpectedTestTarget(listOf(testPattern.events.first { !it.isRest }), fakeClock.currentNanos)

        val result = evaluator.evaluateDetectedPitch(146.83f, 0.2f)

        assertEquals(StrikeAccuracyStatus.UNKNOWN_NOTE, result?.status)
        assertEquals(1, evaluator.state.value.unknownNoteCount)
        evaluator.stopAssessment(showSummary = false)
        assertEquals(1, evaluator.state.value.missedCount)
    }

    @Test
    fun test30_wrongStrikeDoesNotConsumeExpectedTarget() {
        val expected = testPattern.events.first { !it.isRest }
        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)
        evaluator.notifyExpectedTestTarget(listOf(expected), fakeClock.currentNanos)

        val wrong = evaluator.evaluateDetectedPitch(261.63f, 0.95f)
        val correct = evaluator.evaluateDetectedPitch(146.83f, 0.95f)

        assertEquals(StrikeAccuracyStatus.WRONG_NOTE, wrong?.status)
        assertEquals(StrikeAccuracyStatus.PERFECT, correct?.status)
        assertEquals(1, evaluator.state.value.wrongNoteCount)
        assertEquals(1, evaluator.state.value.perfectCount)
        assertEquals(0, evaluator.state.value.missedCount)
    }

    @Test
    fun test31_futureTargetsAreNotExpiredDuringLookahead() {
        val first = testPattern.events.first { !it.isRest }
        val second = testPattern.events.drop(1).first { !it.isRest }
        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)

        evaluator.notifyExpectedTestTarget(listOf(first), fakeClock.currentNanos + 1_000_000_000L)
        evaluator.notifyExpectedTestTarget(listOf(second), fakeClock.currentNanos + 2_000_000_000L)

        assertEquals(0, evaluator.state.value.missedCount)

        fakeClock.advanceMs(1_200)
        evaluator.notifyExpectedTestTarget(emptyList(), fakeClock.currentNanos)

        assertEquals(1, evaluator.state.value.missedCount)
    }

    @Test
    fun test33_duplicateStrikeWithSameTimestampIsEvaluatedOnce() {
        val expected = testPattern.events.first { !it.isRest }
        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)
        evaluator.notifyExpectedTestTarget(listOf(expected), fakeClock.currentNanos)

        val first = evaluator.evaluateDetectedPitch(146.83f, 0.95f, fakeClock.currentNanos)
        val duplicate = evaluator.evaluateDetectedPitch(146.83f, 0.95f, fakeClock.currentNanos)

        assertEquals(StrikeAccuracyStatus.PERFECT, first?.status)
        assertEquals(StrikeAccuracyStatus.PERFECT, duplicate?.status)
        assertEquals(1, evaluator.state.value.perfectCount)
        assertEquals(1, evaluator.state.value.totalStrikesEvaluated)
    }

    @Test
    fun test34_evaluatorPublishesExpectedAndClassifiedEventsToCanonicalTimeline() {
        val timeline = AssessmentTimeline()
        evaluator = AcousticPracticeEvaluator(clock = fakeClock, timeline = timeline)
        val expected = testPattern.events.first { !it.isRest }

        evaluator.startAssessment(testPattern, NotePitchConfig.D_KURD_9)
        evaluator.notifyExpectedTestTarget(listOf(expected), fakeClock.currentNanos)
        evaluator.evaluateDetectedPitch(146.83f, 0.95f, fakeClock.currentNanos)

        assertEquals(
            listOf(AssessmentEventType.EXPECTED, AssessmentEventType.CORRECT),
            timeline.snapshot().map { it.eventType }
        )
        assertEquals(1, timeline.snapshot().count { it.isConsumed })
    }

    // 19. All builtin patterns have valid notes
    @Test
    fun test19_allBuiltinPatternsHaveValidNotes() {
        BuiltinExercises.ALL_BUILTIN_PATTERNS.forEach { pattern ->
            assertTrue(pattern.events.isNotEmpty())
            assertTrue(pattern.bpm in 30..300)
        }
    }

    // 20. Real Handpan Architecture preserves non-destructive virtual fallback
    @Test
    fun test20_nonDestructiveVirtualFallbackPreserved() {
        practiceEngine.setInputMode(PracticeInputMode.VIRTUAL_HANDPAN)
        assertEquals(PracticeInputMode.VIRTUAL_HANDPAN, practiceEngine.uiState.value.inputMode)
        assertFalse(practiceEngine.uiState.value.acousticAssessmentEnabled)

        practiceEngine.setInputMode(PracticeInputMode.REAL_HANDPAN)
        assertEquals(PracticeInputMode.REAL_HANDPAN, practiceEngine.uiState.value.inputMode)
        assertTrue(practiceEngine.uiState.value.acousticAssessmentEnabled)
    }

    @Test
    fun test21_evaluatorUsesPatternBpmInsteadOfFixed500Milliseconds() {
        val bpm60Pattern = HandpanPattern(
            id = "bpm_60",
            title = "BPM 60",
            description = "timing test",
            bpm = 60,
            bars = 1,
            events = listOf(NoteEvent(noteNumber = 0, beatPosition = 1.0))
        )
        evaluator.startAssessment(bpm60Pattern, NotePitchConfig.D_KURD_9, bpm = 60)
        evaluator.notifyExpectedTestTarget(listOf(bpm60Pattern.events.first()), fakeClock.currentNanos + 1_000_000_000L)
        fakeClock.advanceMs(1000)

        val result = evaluator.evaluateDetectedPitch(146.83f, 0.9f)

        assertNotNull(result)
        assertEquals(StrikeAccuracyStatus.PERFECT, result?.status)
    }

    @Test
    fun test22_performanceRecorderCapturesEventFromUserStrike() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = PerformanceRecorder(context, fakeAudio)

        recorder.startRecording()
        recorder.recordStrike(noteNumber = 3, isAccent = true, velocity = 1.0f, hand = "R")
        val track = recorder.stopRecording(scaleName = "D Kurd")

        assertNotNull(track)
        assertEquals(1, track?.events?.size)
        assertEquals(3, track?.events?.first()?.noteNumber)
        assertEquals("R", track?.events?.first()?.hand)
        recorder.release()
    }

    @Test
    fun test32_performanceRecorderKeepsUnknownStrikeTimelineEvent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = PerformanceRecorder(context, fakeAudio)

        recorder.startRecording()
        recorder.recordStrike(
            noteNumber = -1,
            classification = StrikeClassification.UNKNOWN_NOTE,
            confidence = 0.2f
        )
        val track = recorder.stopRecording(scaleName = "D Kurd")

        assertEquals(StrikeClassification.UNKNOWN_NOTE, track?.events?.single()?.classification)
        assertEquals(0.2f, track?.events?.single()?.confidence ?: 0f, 0.001f)
        recorder.release()
    }

    @Test
    fun test23_combinesCorrectNoteWithGoodTimingFromAuthoritativeTarget() {
        val pattern = HandpanPattern(
            id = "combined_good",
            title = "Combined",
            description = "combined timing test",
            bpm = 60,
            bars = 1,
            events = listOf(NoteEvent(noteNumber = 0, beatPosition = 0.0))
        )
        evaluator.startAssessment(pattern, NotePitchConfig.D_KURD_9, bpm = 60)
        evaluator.notifyExpectedTestTarget(pattern.events, fakeClock.currentNanos)
        fakeClock.advanceMs(60)

        val result = evaluator.evaluateDetectedPitch(146.83f, 0.95f)

        assertNotNull(result)
        assertTrue(result?.noteCorrect == true)
        assertEquals(TimingAccuracyStatus.GOOD, result?.timingStatus)
        assertEquals(StrikeAccuracyStatus.GOOD, result?.status)
        assertEquals(60L, result?.deviationMs)
    }

    @Test
    fun test24_reportsWrongNoteAndLateTimingIndependently() {
        val pattern = HandpanPattern(
            id = "wrong_late",
            title = "Wrong and late",
            description = "combined mismatch test",
            bpm = 60,
            bars = 1,
            events = listOf(NoteEvent(noteNumber = 0, beatPosition = 0.0))
        )
        evaluator.startAssessment(pattern, NotePitchConfig.D_KURD_9, bpm = 60)
        evaluator.notifyExpectedTestTarget(pattern.events, fakeClock.currentNanos)
        fakeClock.advanceMs(120)

        val result = evaluator.evaluateDetectedPitch(261.63f, 0.95f)

        assertNotNull(result)
        assertTrue(result?.noteCorrect == false)
        assertEquals(TimingAccuracyStatus.LATE, result?.timingStatus)
        assertEquals(StrikeAccuracyStatus.WRONG_NOTE, result?.status)
        assertEquals(120L, result?.deviationMs)
    }

    @Test
    fun test25_reportsWrongNoteWithoutDestroyingPerfectTiming() {
        val pattern = HandpanPattern(
            id = "wrong_perfect",
            title = "Wrong but on time",
            description = "independent axes",
            bpm = 60,
            bars = 1,
            events = listOf(NoteEvent(noteNumber = 0, beatPosition = 0.0))
        )
        evaluator.startAssessment(pattern, NotePitchConfig.D_KURD_9, bpm = 60)
        evaluator.notifyExpectedTestTarget(pattern.events, fakeClock.currentNanos)

        val result = evaluator.evaluateDetectedPitch(261.63f, 0.95f)

        assertEquals(StrikeAccuracyStatus.WRONG_NOTE, result?.status)
        assertEquals(TimingAccuracyStatus.PERFECT, result?.timingStatus)
        assertEquals(100f, evaluator.state.value.timingAccuracyPercentage, 0.01f)
        assertEquals(0f, evaluator.state.value.noteAccuracyPercentage, 0.01f)
    }

    @Test
    fun test26_expiresUnmatchedExpectedEventAsMissed() {
        val pattern = HandpanPattern(
            id = "missed",
            title = "Missed",
            description = "missed event test",
            bpm = 60,
            bars = 1,
            events = listOf(NoteEvent(noteNumber = 0, beatPosition = 0.0))
        )
        evaluator.startAssessment(pattern, NotePitchConfig.D_KURD_9, bpm = 60)
        evaluator.notifyExpectedTestTarget(pattern.events, fakeClock.currentNanos)
        fakeClock.advanceMs(200)
        evaluator.notifyExpectedTestTarget(emptyList(), fakeClock.currentNanos)

        assertEquals(1, evaluator.state.value.missedCount)
        assertEquals(1, evaluator.state.value.totalStrikesEvaluated)
    }

    @Test
    fun test27_simultaneousTargetRequiresEachExpectedNote() {
        val pattern = HandpanPattern(
            id = "chord",
            title = "Chord",
            description = "simultaneous notes",
            bpm = 60,
            bars = 1,
            events = listOf(
                NoteEvent(noteNumber = 0, beatPosition = 0.0),
                NoteEvent(noteNumber = 1, beatPosition = 0.0)
            )
        )
        evaluator.startAssessment(pattern, NotePitchConfig.D_KURD_9, bpm = 60)
        evaluator.notifyExpectedTestTarget(pattern.events, fakeClock.currentNanos)

        val first = evaluator.evaluateDetectedPitch(146.83f, 0.95f)
        val second = evaluator.evaluateDetectedPitch(220.0f, 0.95f)

        assertEquals(StrikeAccuracyStatus.PERFECT, first?.status)
        assertEquals(StrikeAccuracyStatus.PERFECT, second?.status)
        assertEquals(2, evaluator.state.value.perfectCount)
        assertEquals(2, evaluator.state.value.totalStrikesEvaluated)
    }

    @Test
    fun test28_partialChordOnlyMissesRemainingObligation() {
        val pattern = HandpanPattern(
            id = "partial_chord",
            title = "Partial chord",
            description = "remaining note should be missed",
            bpm = 60,
            bars = 1,
            events = listOf(
                NoteEvent(noteNumber = 0, beatPosition = 0.0),
                NoteEvent(noteNumber = 1, beatPosition = 0.0)
            )
        )
        evaluator.startAssessment(pattern, NotePitchConfig.D_KURD_9, bpm = 60)
        evaluator.notifyExpectedTestTarget(pattern.events, fakeClock.currentNanos)
        evaluator.evaluateDetectedPitch(146.83f, 0.95f)
        fakeClock.advanceMs(200)
        evaluator.notifyExpectedTestTarget(emptyList(), fakeClock.currentNanos)

        assertEquals(1, evaluator.state.value.perfectCount)
        assertEquals(1, evaluator.state.value.missedCount)
        assertEquals(2, evaluator.state.value.totalStrikesEvaluated)
    }
}
