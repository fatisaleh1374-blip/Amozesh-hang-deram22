package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.model.NotePitchConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Result of real-time pitch and onset detection from microphone.
 */
data class DetectedPitchResult(
    val frequencyHz: Float,
    val noteName: String,
    val centsOffset: Int, // -50 to +50 cents from standard 12-TET pitch
    val amplitude: Float,
    val matchedNoteNumber: Int?, // 0 for Ding, 1..8, 9 for Slap, or null if outside scale
    val matchedPitchDiffHz: Float,
    val confidence: Float = 0.8f
)

/**
 * Real-time Pitch and Strike Detector for acoustic handpans.
 * Uses unified monotonic time stamps (nanoseconds) for latency-free evaluation.
 */
class PitchDetector(
    private val clock: PracticeClock = PracticeClock.Default
) {

    companion object {
        private const val TAG = "PitchDetector"
        private const val SAMPLE_RATE = 22050
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 2048 // ~92ms at 22.05kHz

        fun frequencyToNoteName(freq: Float): Pair<String, Int> {
            return YinPitchDetector.frequencyToNoteAndCents(freq)
        }
    }

    private var audioRecord: AudioRecord? = null
    private var trackingJob: Job? = null
    private var isListening = false
    private val onsetMatcher = OnsetAndPitchMatcher(SAMPLE_RATE)

    @SuppressLint("MissingPermission")
    fun startListening(
        scaleConfig: NotePitchConfig,
        onStrikeDetected: (DetectedPitchResult, Long) -> Unit, // Monotonic timestamp in nanoseconds
        onContinuousPitch: (DetectedPitchResult) -> Unit = {}
    ) {
        if (isListening) stopListening()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(BUFFER_SIZE * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isListening = true

            trackingJob = CoroutineScope(Dispatchers.Default).launch {
                val audioBuffer = ShortArray(BUFFER_SIZE)
                var lastRms = 0f
                var lastStrikeTimestampNanos = 0L

                while (isActive && isListening) {
                    val readSamples = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readSamples < BUFFER_SIZE) continue

                    val nowNanos = clock.nowNanos()

                    // Calculate RMS energy
                    var sumSquares = 0.0
                    for (i in 0 until readSamples) {
                        val sample = audioBuffer[i].toDouble() / 32768.0
                        sumSquares += sample * sample
                    }
                    val rms = sqrt(sumSquares / readSamples).toFloat()

                    val eval = onsetMatcher.processFrame(audioBuffer, readSamples, rms, lastRms, scaleConfig)

                    // Sub-frame timestamp based on sample offset
                    val exactStrikeTimestampNanos = nowNanos - ((readSamples - eval.onsetSampleOffset) * 1_000_000_000L / SAMPLE_RATE)

                    if (eval.detectedFreqHz > 0f) {
                        val result = DetectedPitchResult(
                            frequencyHz = eval.detectedFreqHz,
                            noteName = eval.noteName,
                            centsOffset = eval.centsOffset,
                            amplitude = (rms * 5f).coerceIn(0f, 1f),
                            matchedNoteNumber = eval.matchedScaleNote,
                            matchedPitchDiffHz = eval.centsDeviationFromScale,
                            confidence = eval.confidence
                        )

                        withContext(Dispatchers.Main) {
                            onContinuousPitch(result)
                        }

                        // Refractory window of 130ms (130,000,000 ns) for distinct strikes
                        if (eval.isStrike && (exactStrikeTimestampNanos - lastStrikeTimestampNanos) > 130_000_000L) {
                            lastStrikeTimestampNanos = exactStrikeTimestampNanos
                            withContext(Dispatchers.Main) {
                                onStrikeDetected(result, exactStrikeTimestampNanos)
                            }
                        }
                    } else if (eval.isStrike && (exactStrikeTimestampNanos - lastStrikeTimestampNanos) > 130_000_000L) {
                        // Percussive Slap / Tak hit without tonal pitch
                        val slapResult = DetectedPitchResult(
                            frequencyHz = 0f,
                            noteName = "Slap",
                            centsOffset = 0,
                            amplitude = (rms * 5f).coerceIn(0f, 1f),
                            matchedNoteNumber = NotePitchConfig.NOTE_SLAP,
                            matchedPitchDiffHz = 0f,
                            confidence = 0.85f
                        )
                        lastStrikeTimestampNanos = exactStrikeTimestampNanos
                        withContext(Dispatchers.Main) {
                            onStrikeDetected(slapResult, exactStrikeTimestampNanos)
                        }
                    }

                    lastRms = rms
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting pitch detection", e)
            isListening = false
        }
    }

    fun stopListening() {
        isListening = false
        trackingJob?.cancel()
        trackingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }
}
