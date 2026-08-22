package com.example.audio

import com.example.model.NotePitchConfig
import com.example.model.DetectedStrikeEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class AudioAnalysisSession(
    private val detector: PitchDetector = PitchDetector()
) {
    private data class Listener(
        val onStrike: (DetectedStrikeEvent) -> Unit,
        val onPitch: (DetectedPitchResult) -> Unit
    )

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val sessionId = "audio-${System.identityHashCode(this)}"
    private val eventSequence = AtomicLong(0L)
    private var listening = false
    private var microphoneLease: AudioResourceCoordinator.Lease? = null

    @Synchronized
    fun acquire(
        scaleConfig: NotePitchConfig,
        onStrike: (DetectedStrikeEvent) -> Unit,
        onPitch: (DetectedPitchResult) -> Unit = {}
    ): Subscription {
        val listener = Listener(onStrike, onPitch)
        if (!listening) {
            microphoneLease = AudioResourceCoordinator.tryAcquire(sessionId)
                ?: return Subscription {}
        }
        listeners += listener
        if (!listening) {
            try {
                detector.startListening(
                    scaleConfig = scaleConfig,
                    onStrikeDetected = { result, timestamp ->
                        val event = DetectedStrikeEvent(
                            id = "$sessionId-${eventSequence.incrementAndGet()}",
                            sessionId = sessionId,
                            monotonicTimestampNanos = timestamp,
                            detectedFrequencyHz = result.frequencyHz,
                            detectedNoteName = result.noteName,
                            detectedCentsOffset = result.centsOffset,
                            detectedNote = result.matchedNoteNumber,
                            matchedPitchDiffHz = result.matchedPitchDiffHz,
                            pitchConfidence = result.confidence,
                            onsetStrength = result.amplitude,
                            energy = result.amplitude,
                            pitchValid = result.matchedNoteNumber != null && result.confidence >= 0.5f
                        )
                        listeners.forEach { it.onStrike(event) }
                    },
                    onContinuousPitch = { result ->
                        listeners.forEach { it.onPitch(result) }
                    }
                )
                listening = true
            } catch (error: RuntimeException) {
                listeners -= listener
                listening = false
                microphoneLease?.close()
                microphoneLease = null
            }
        }
        return Subscription { release(listener) }
    }

    @Synchronized
    private fun release(listener: Listener) {
        listeners -= listener
        if (listeners.isEmpty() && listening) {
            detector.stopListening()
            listening = false
            microphoneLease?.close()
            microphoneLease = null
        }
    }

    fun close() {
        synchronized(this) {
            listeners.clear()
            if (listening) {
                detector.stopListening()
                listening = false
            }
            microphoneLease?.close()
            microphoneLease = null
        }
        detector.release()
    }

    class Subscription internal constructor(
        private val onClose: () -> Unit
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (!closed) {
                closed = true
                onClose()
            }
        }
    }
}

internal fun DetectedStrikeEvent.toPitchResult(): DetectedPitchResult {
    return DetectedPitchResult(
        frequencyHz = detectedFrequencyHz,
        noteName = detectedNoteName,
        centsOffset = detectedCentsOffset,
        amplitude = energy,
        matchedNoteNumber = detectedNote,
        matchedPitchDiffHz = matchedPitchDiffHz,
        confidence = pitchConfidence
    )
}