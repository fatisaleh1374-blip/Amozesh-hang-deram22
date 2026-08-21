package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.audio.RecordedStrikeEvent
import com.example.audio.RecordedTrack
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
            events = events
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
                eventsJson = encodeEventsJson(track.events)
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
                array.put(obj)
            }
            return array.toString()
        }

        fun parseEventsJson(json: String): List<RecordedStrikeEvent> {
            val list = mutableListOf<RecordedStrikeEvent>()
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        RecordedStrikeEvent(
                            noteNumber = obj.getInt("noteNumber"),
                            timestampMs = obj.getLong("timestampMs"),
                            velocity = obj.optDouble("velocity", 0.85).toFloat(),
                            isAccent = obj.optBoolean("isAccent", false)
                        )
                    )
                }
            } catch (_: Exception) {
                // Ignore and return empty list on malformed payload
            }
            return list
        }
    }
}
