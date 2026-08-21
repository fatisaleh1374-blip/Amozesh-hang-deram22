package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.audio.RecordedStrikeEvent
import com.example.audio.RecordedTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistentDataArchitectureTest {

    private lateinit var db: AppDatabase
    private lateinit var lessonDao: LessonProgressDao
    private lateinit var recordingDao: RecordingTrackDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        lessonDao = db.lessonProgressDao()
        recordingDao = db.recordingTrackDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testLessonProgressDao_insertAndQuery() = runBlocking {
        val lesson = LessonProgressEntity(
            lessonId = "lesson_1_ding",
            isCompleted = true,
            stars = 3,
            bestScore = 95,
            attempts = 2,
            lastPracticedAt = 1700000000000L
        )

        lessonDao.saveLessonProgress(lesson)

        val retrieved = lessonDao.getProgressForLesson("lesson_1_ding")
        assertNotNull(retrieved)
        assertEquals(3, retrieved?.stars)
        assertEquals(95, retrieved?.bestScore)
        assertTrue(retrieved?.isCompleted == true)
        assertEquals(2, retrieved?.attempts)

        val allList = lessonDao.getAllLessonProgress().first()
        assertEquals(1, allList.size)
        assertEquals("lesson_1_ding", allList[0].lessonId)
    }

    @Test
    fun testRecordingTrackDao_insertAndSerialization() = runBlocking {
        val events = listOf(
            RecordedStrikeEvent(noteNumber = 0, timestampMs = 100L, velocity = 0.9f, isAccent = true),
            RecordedStrikeEvent(noteNumber = 1, timestampMs = 350L, velocity = 0.8f, isAccent = false),
            RecordedStrikeEvent(noteNumber = 9, timestampMs = 600L, velocity = 1.0f, isAccent = true)
        )

        val track = RecordedTrack(
            id = "track_test_123",
            title = "بداهه‌نوازی آزمایشی D Kurd",
            date = "2026/08/21 12:00",
            scaleId = "D Kurd",
            durationMs = 1200L,
            bpm = 88,
            timeSignature = "6/8",
            events = events
        )

        val entity = RecordingTrackEntity.fromDomain(track)
        recordingDao.insertRecordingTrack(entity)

        val retrievedEntity = recordingDao.getRecordingTrackById("track_test_123")
        assertNotNull(retrievedEntity)
        val domainTrack = retrievedEntity!!.toDomain()

        assertEquals("track_test_123", domainTrack.id)
        assertEquals("بداهه‌نوازی آزمایشی D Kurd", domainTrack.title)
        assertEquals(3, domainTrack.events.size)
        assertEquals(0, domainTrack.events[0].noteNumber)
        assertTrue(domainTrack.events[0].isAccent)
        assertEquals(9, domainTrack.events[2].noteNumber)
        assertEquals(88, domainTrack.bpm)
        assertEquals("6/8", domainTrack.timeSignature)
    }
}
