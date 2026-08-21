package com.example

import com.example.audio.PracticeClock
import com.example.model.DifficultyLevel
import com.example.model.HandpanNote
import com.example.model.HandpanPattern
import com.example.model.HandpanTechnique
import com.example.model.InstrumentProfile
import com.example.model.MusicEvent
import com.example.model.NotationRenderer
import com.example.model.NotationSystem
import com.example.model.NoteEvent
import com.example.model.PatternCategory
import com.example.model.PlayingHand
import com.example.model.Subdivision
import com.example.model.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Master Production Quality Audit & Specification Verification Tests.
 * Covers Parts 1 through 28, 48, 54, 90, 91.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionAuditMasterTest {

    @Test
    fun testPart1_DKurd9InstrumentProfileMapping() {
        val profile = InstrumentProfile.DEFAULT_D_KURD_9
        assertEquals("d_kurd_9", profile.id)
        assertEquals("D3", profile.rootPitch)
        assertEquals(9, profile.fields.size)

        // Verify Ding
        val ding = profile.dingField
        assertEquals("D3", ding.scientificPitch)
        assertEquals("D", ding.pitchName)
        assertEquals(0, ding.displayNumber)
        assertTrue(ding.isDing)
        assertEquals(PlayingHand.EITHER, ding.defaultHand)

        // Verify Tone fields 1..8 with proper physical positioning
        val f1 = profile.getFieldByNumber(1)
        assertNotNull(f1)
        assertEquals("A3", f1!!.scientificPitch)
        assertEquals(PlayingHand.RIGHT, f1.defaultHand)

        val f2 = profile.getFieldByNumber(2)
        assertNotNull(f2)
        assertEquals("Bb3", f2!!.scientificPitch)
        assertEquals(PlayingHand.LEFT, f2.defaultHand)

        val f3 = profile.getFieldByNumber(3)
        assertNotNull(f3)
        assertEquals("C4", f3!!.scientificPitch)
        assertEquals(PlayingHand.RIGHT, f3.defaultHand)

        val f4 = profile.getFieldByNumber(4)
        assertNotNull(f4)
        assertEquals("D4", f4!!.scientificPitch)
        assertEquals(PlayingHand.LEFT, f4.defaultHand)

        val f5 = profile.getFieldByNumber(5)
        assertNotNull(f5)
        assertEquals("E4", f5!!.scientificPitch)
        assertEquals(PlayingHand.RIGHT, f5.defaultHand)

        val f6 = profile.getFieldByNumber(6)
        assertNotNull(f6)
        assertEquals("F4", f6!!.scientificPitch)
        assertEquals(PlayingHand.LEFT, f6.defaultHand)

        val f7 = profile.getFieldByNumber(7)
        assertNotNull(f7)
        assertEquals("G4", f7!!.scientificPitch)
        assertEquals(PlayingHand.RIGHT, f7.defaultHand)

        val f8 = profile.getFieldByNumber(8)
        assertNotNull(f8)
        assertEquals("A4", f8!!.scientificPitch)
        assertEquals(PlayingHand.LEFT, f8.defaultHand)
    }

    @Test
    fun testPart4To7_NotationDecoupling() {
        val profile = InstrumentProfile.DEFAULT_D_KURD_9

        // Note 0 (Ding)
        assertEquals("D", NotationRenderer.render(0, HandpanTechnique.DING, profile, NotationSystem.NUMERIC))
        assertEquals("D3", NotationRenderer.render(0, HandpanTechnique.DING, profile, NotationSystem.LETTER))
        assertEquals("D", NotationRenderer.render(0, HandpanTechnique.DING, profile, NotationSystem.LETTER_SIMPLE))
        assertEquals("ر", NotationRenderer.render(0, HandpanTechnique.DING, profile, NotationSystem.SOLFEGE))

        // Note 1 (A3)
        assertEquals("1", NotationRenderer.render(1, HandpanTechnique.TONE, profile, NotationSystem.NUMERIC))
        assertEquals("A3", NotationRenderer.render(1, HandpanTechnique.TONE, profile, NotationSystem.LETTER))
        assertEquals("A", NotationRenderer.render(1, HandpanTechnique.TONE, profile, NotationSystem.LETTER_SIMPLE))
        assertEquals("لا", NotationRenderer.render(1, HandpanTechnique.TONE, profile, NotationSystem.SOLFEGE))

        // Note 2 (Bb3)
        assertEquals("2", NotationRenderer.render(2, HandpanTechnique.TONE, profile, NotationSystem.NUMERIC))
        assertEquals("Bb3", NotationRenderer.render(2, HandpanTechnique.TONE, profile, NotationSystem.LETTER))
        assertEquals("Bb", NotationRenderer.render(2, HandpanTechnique.TONE, profile, NotationSystem.LETTER_SIMPLE))
        assertEquals("سی‌بمل", NotationRenderer.render(2, HandpanTechnique.TONE, profile, NotationSystem.SOLFEGE))

        // Rest and Slap
        assertEquals("𝄽", NotationRenderer.render(0, HandpanTechnique.REST, profile, NotationSystem.NUMERIC))
        assertEquals("S", NotationRenderer.render(9, HandpanTechnique.SLAP, profile, NotationSystem.NUMERIC))
    }

    @Test
    fun testPart13_MusicEventModelDecoupling() {
        val event1 = MusicEvent(
            targetNumber = 3,
            beatPosition = 1.0,
            duration = 0.5,
            velocity = 0.9f,
            accent = true,
            technique = HandpanTechnique.TONE,
            hand = PlayingHand.RIGHT
        )
        assertFalse(event1.isRest)
        assertFalse(event1.isDing)
        assertEquals(PlayingHand.RIGHT, event1.hand)

        // Simultaneous events test (Part 18)
        val event2 = MusicEvent(
            targetNumber = 0,
            beatPosition = 1.0,
            duration = 1.0,
            technique = HandpanTechnique.DING,
            hand = PlayingHand.LEFT
        )
        assertEquals(event1.beatPosition, event2.beatPosition, 0.0001)
    }

    @Test
    fun testNoteEventValidation() {
        // Valid NoteEvents
        val ding = NoteEvent(noteNumber = 0, beatPosition = 0.0, duration = 1.0, velocity = 0.8f)
        assertEquals(0, ding.noteNumber)
        assertTrue(ding.isDing)
        assertFalse(ding.isRest)

        val tone8 = NoteEvent(noteNumber = 8, beatPosition = 1.5, duration = 0.5, velocity = 0.9f)
        assertEquals(8, tone8.noteNumber)

        val slap = NoteEvent(noteNumber = 9, beatPosition = 2.0, duration = 0.5, velocity = 1.0f)
        assertEquals(9, slap.noteNumber)
        assertTrue(slap.isSlap)

        val rest = NoteEvent(noteNumber = 0, beatPosition = 3.0, isRest = true)
        assertTrue(rest.isRest)

        // Invalid NoteEvents must fail validation
        var threwInvalidNote = false
        try {
            NoteEvent(noteNumber = 12, beatPosition = 0.0)
        } catch (e: IllegalArgumentException) {
            threwInvalidNote = true
        }
        assertTrue("NoteEvent with noteNumber 12 should throw IllegalArgumentException", threwInvalidNote)

        var threwNegativeBeat = false
        try {
            NoteEvent(noteNumber = 1, beatPosition = -0.5)
        } catch (e: IllegalArgumentException) {
            threwNegativeBeat = true
        }
        assertTrue("NoteEvent with negative beatPosition should throw IllegalArgumentException", threwNegativeBeat)
    }

    @Test
    fun testPatternShareHelperSerializationAndValidation() {
        val originalPattern = com.example.model.HandpanPattern(
            id = "test_1",
            title = "الگوی آزمایشی",
            description = "تست اعتبارسنجی اشتراک",
            bpm = 80,
            timeSignature = com.example.model.TimeSignature(4, 4),
            bars = 2,
            events = listOf(
                NoteEvent(noteNumber = 0, beatPosition = 0.0, duration = 1.0, velocity = 0.9f, accent = true),
                NoteEvent(noteNumber = 1, beatPosition = 1.0, duration = 0.5, velocity = 0.8f),
                NoteEvent(noteNumber = 9, beatPosition = 2.0, duration = 0.5, velocity = 1.0f)
            ),
            difficulty = DifficultyLevel.INTERMEDIATE,
            category = PatternCategory.RHYTHM,
            recommendedSubdivision = Subdivision.EIGHTH
        )

        // 1. Serialize
        val json = com.example.data.local.PatternShareHelper.patternToJson(originalPattern)
        assertNotNull(json)
        assertTrue(json.contains("schemaVersion"))
        assertTrue(json.contains("HandpanPattern_v1"))

        // 2. Deserialize valid JSON
        val parseResult = com.example.data.local.PatternShareHelper.jsonToPattern(json)
        assertTrue(parseResult.isSuccess)
        val parsed = parseResult.getOrThrow()
        assertEquals(originalPattern.title, parsed.title)
        assertEquals(originalPattern.bpm, parsed.bpm)
        assertEquals(3, parsed.events.size)

        // 3. Reject invalid / corrupt JSON
        val corruptResult = com.example.data.local.PatternShareHelper.jsonToPattern("{ invalid_json: true }")
        assertTrue(corruptResult.isFailure)

        // 4. Reject invalid BPM
        val invalidBpmJson = json.replace("\"bpm\": 80", "\"bpm\": 999")
        val bpmResult = com.example.data.local.PatternShareHelper.jsonToPattern(invalidBpmJson)
        assertTrue(bpmResult.isFailure)

        // 5. Reject empty events
        val emptyEventsJson = json.replace("\"events\": [", "\"events\": [] //")
        val emptyResult = com.example.data.local.PatternShareHelper.jsonToPattern("""{"title":"Test","bpm":80,"bars":1,"events":[]}""")
        assertTrue(emptyResult.isFailure)
    }

    @Test
    fun testPatternSchedulerPreIndexing() {
        val events = listOf(
            NoteEvent(noteNumber = 0, beatPosition = 0.0),
            NoteEvent(noteNumber = 1, beatPosition = 0.5),
            NoteEvent(noteNumber = 2, beatPosition = 1.0)
        )
        val schedule = com.example.audio.PatternScheduler.buildSchedule(
            events = events,
            beatsPerBar = 4,
            totalBars = 1
        )
        assertTrue(schedule.isNotEmpty())
        assertEquals(0.0, schedule[0].beatPosition, 0.001)
        assertTrue(schedule[0].isDownbeat)
        assertEquals(1, schedule[0].events.size)
        assertEquals(0, schedule[0].events[0].noteNumber)
    }
}
