package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.audio.RecordedStrikeEvent
import com.example.audio.RecordedTrack
import com.example.model.StrikeClassification
import com.example.model.AssessmentEventType
import com.example.model.AssessmentTimelineEvent
import com.example.model.TimingResult
import com.example.model.TimingStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Room entity representing a saved recorded live performance / loop track.
 */
@Entity(tableName = "recording_tracks")
data class RecordingTrackEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val date: String,
    val scaleId: String,
    val durationMs: Long,
    val eventsJson: String,
    val bpm: Int = 70,
    val timeSignature: String = "4/4",
    val timelineEventsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): RecordedTrack {
        val events = parseEventsJson(eventsJson)
        return RecordedTrack(
            id = id,
            title = title,
            date = date,
            scaleId = scaleId,
            durationMs = durationMs,
            events = events,
            timelineEvents = parseTimelineEventsJson(timelineEventsJson),
            bpm = bpm,
            timeSignature = timeSignature
        )
    }

    companion object {
        fun fromDomain(track: RecordedTrack): RecordingTrackEntity {
            return RecordingTrackEntity(
                id = track.id,
                title = track.title,
                date = track.date,
                scaleId = track.scaleId,
                durationMs = track.durationMs,
                eventsJson = encodeEventsJson(track.events),
                bpm = track.bpm,
                timeSignature = track.timeSignature,
                timelineEventsJson = encodeTimelineEventsJson(track.timelineEvents)
            )
        }

        fun encodeEventsJson(events: List<RecordedStrikeEvent>): String {
            val array = JSONArray()
            for (e in events) {
                val obj = JSONObject()
                obj.put("noteNumber", e.noteNumber)
                obj.put("timestampMs", e.timestampMs)
                obj.put("velocity", e.velocity.toDouble())
                obj.put("isAccent", e.isAccent)
                obj.put("classification", e.classification.name)
                obj.put("confidence", e.confidence)
                e.durationMs?.let { obj.put("durationMs", it) }
                e.hand?.let { obj.put("hand", it) }
                array.put(obj)
            }
            return array.toString()
        }

        fun parseEventsJson(json: String): List<RecordedStrikeEvent> {
            val array = JSONArray(json)
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        RecordedStrikeEvent(
                            noteNumber = obj.getInt("noteNumber"),
                            timestampMs = obj.getLong("timestampMs"),
                            velocity = obj.optDouble("velocity", 0.85).toFloat(),
                            isAccent = obj.optBoolean("isAccent", false),
                            durationMs = if (obj.has("durationMs")) obj.optLong("durationMs") else null,
                            hand = if (obj.has("hand")) obj.optString("hand") else null,
                            classification = StrikeClassification.valueOf(
                                obj.optString("classification", StrikeClassification.CORRECT_NOTE.name)
                            ),
                            confidence = obj.optDouble("confidence", 1.0).toFloat()
                        )
                    )
                }
            }
        }

        fun encodeTimelineEventsJson(events: List<AssessmentTimelineEvent>): String {
            val array = JSONArray()
            events.forEach { event ->
                array.put(JSONObject().apply {
                    put("eventId", event.eventId)
                    put("sessionId", event.sessionId)
                    put("assessmentSessionId", event.assessmentSessionId)
                    event.loopId?.let { put("loopId", it) }
                    put("patternId", event.patternId)
                    put("targetId", event.targetId)
                    put("sequenceIndex", event.sequenceIndex)
                    put("obligationId", event.obligationId)
                    put("expectedNotes", JSONArray(event.expectedNotes.toList()))
                    put("expectedNote", event.expectedNote)
                    put("detectedNote", event.detectedNote)
                    put("eventType", event.eventType.name)
                    put("expectedTimestampNanos", event.expectedTimestampNanos)
                    put("detectedTimestampNanos", event.detectedTimestampNanos)
                    put("deviationNanos", event.deviationNanos)
                    put("timingStatus", event.timingResult?.status?.name)
                    put("timingDeviationNanos", event.timingResult?.deviationNanos)
                    put("confidence", event.confidence)
                    put("source", event.source)
                    put("durationNanos", event.durationNanos)
                    put("isConsumed", event.isConsumed)
                })
            }
            return array.toString()
        }

        fun parseTimelineEventsJson(json: String): List<AssessmentTimelineEvent> {
            val array = JSONArray(json)
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val expectedNote = if (obj.isNull("expectedNote")) null else obj.getInt("expectedNote")
                    val timingStatus = obj.optString("timingStatus", "").takeIf { it.isNotBlank() }
                    add(AssessmentTimelineEvent(
                        eventId = obj.getString("eventId"),
                        sessionId = obj.getString("sessionId"),
                        loopId = obj.optString("loopId").takeIf { it.isNotBlank() },
                        sequenceIndex = obj.getInt("sequenceIndex"),
                        expectedNote = expectedNote,
                        detectedNote = if (obj.isNull("detectedNote")) null else obj.getInt("detectedNote"),
                        eventType = AssessmentEventType.valueOf(obj.getString("eventType")),
                        expectedTimestampNanos = obj.optLongOrNull("expectedTimestampNanos"),
                        detectedTimestampNanos = obj.optLongOrNull("detectedTimestampNanos"),
                        deviationNanos = obj.optLongOrNull("deviationNanos"),
                        timingResult = timingStatus?.let {
                            TimingResult(TimingStatus.valueOf(it), obj.optLong("timingDeviationNanos"))
                        },
                        confidence = obj.getDouble("confidence").toFloat(),
                        targetId = obj.optString("targetId").takeIf { it.isNotBlank() },
                        source = obj.getString("source"),
                        durationNanos = obj.optLongOrNull("durationNanos"),
                        isConsumed = obj.getBoolean("isConsumed"),
                        assessmentSessionId = obj.optString("assessmentSessionId", obj.getString("sessionId")),
                        patternId = obj.optString("patternId").takeIf { it.isNotBlank() },
                        obligationId = obj.optString("obligationId").takeIf { it.isNotBlank() },
                        expectedNotes = obj.optJSONArray("expectedNotes")?.let { notes ->
                            buildSet { for (j in 0 until notes.length()) add(notes.getInt(j)) }
                        } ?: expectedNote?.let { setOf(it) }.orEmpty()
                    ))
                }
            }
        }

        private fun JSONObject.optLongOrNull(name: String): Long? =
            if (isNull(name)) null else optLong(name)
        }
    }
}
