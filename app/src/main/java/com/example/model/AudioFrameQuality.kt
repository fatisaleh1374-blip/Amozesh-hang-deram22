package com.example.model

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

enum class AudioFrameStatus {
    VALID,
    SILENT,
    LOW_SIGNAL,
    NOISY,
    OVERLOADED,
    INVALID
}

data class AudioFrameQuality(
    val sampleCount: Int,
    val sampleRateHz: Int,
    val rms: Float,
    val peak: Float,
    val clippingRatio: Float,
    val noiseFloorRms: Float,
    val signalToNoiseRatioDb: Float,
    val signalConfidence: Float,
    val status: AudioFrameStatus,
    val captureTimestampNanos: Long,
    val analysisStartTimestampNanos: Long,
    val analysisEndTimestampNanos: Long
) {
    val captureToAnalysisLatencyNanos: Long
        get() = analysisEndTimestampNanos - captureTimestampNanos

    val analysisDurationNanos: Long
        get() = analysisEndTimestampNanos - analysisStartTimestampNanos

    init {
        require(sampleCount >= 0)
        require(sampleRateHz > 0)
        require(rms in 0f..1f)
        require(peak in 0f..1f)
        require(clippingRatio in 0f..1f)
        require(noiseFloorRms in 0f..1f)
        require(signalToNoiseRatioDb >= 0f)
        require(signalConfidence in 0f..1f)
        require(captureTimestampNanos >= 0L)
        require(analysisStartTimestampNanos >= captureTimestampNanos)
        require(analysisEndTimestampNanos >= analysisStartTimestampNanos)
    }
}

object AudioFrameQualityAnalyzer {
    const val MINIMUM_SAMPLE_RATE_HZ = 8_000
    const val MAXIMUM_SAMPLE_RATE_HZ = 192_000
    const val SILENCE_RMS = 0.004f
    const val MINIMUM_SIGNAL_RMS = 0.012f
    const val HIGH_NOISE_SNR_DB = 6f
    const val CLIPPING_SAMPLE_LEVEL = 0.999f
    const val OVERLOAD_CLIPPING_RATIO = 0.01f

    fun analyze(
        samples: ShortArray,
        sampleCount: Int,
        sampleRateHz: Int,
        noiseFloorRms: Float,
        captureTimestampNanos: Long,
        analysisStartTimestampNanos: Long,
        analysisEndTimestampNanos: Long
    ): AudioFrameQuality {
        val boundedCount = sampleCount.coerceIn(0, samples.size)
        if (sampleCount <= 0 || sampleCount > samples.size ||
            sampleRateHz !in MINIMUM_SAMPLE_RATE_HZ..MAXIMUM_SAMPLE_RATE_HZ ||
            captureTimestampNanos < 0L ||
            analysisStartTimestampNanos < captureTimestampNanos ||
            analysisEndTimestampNanos < analysisStartTimestampNanos
        ) {
            return AudioFrameQuality(
                sampleCount = boundedCount,
                sampleRateHz = sampleRateHz.coerceAtLeast(1),
                rms = 0f,
                peak = 0f,
                clippingRatio = 0f,
                noiseFloorRms = noiseFloorRms.coerceIn(0f, 1f),
                signalToNoiseRatioDb = 0f,
                signalConfidence = 0f,
                status = AudioFrameStatus.INVALID,
                captureTimestampNanos = captureTimestampNanos.coerceAtLeast(0L),
                analysisStartTimestampNanos = max(
                    captureTimestampNanos.coerceAtLeast(0L),
                    analysisStartTimestampNanos.coerceAtLeast(0L)
                ),
                analysisEndTimestampNanos = max(
                    max(captureTimestampNanos.coerceAtLeast(0L), analysisStartTimestampNanos.coerceAtLeast(0L)),
                    analysisEndTimestampNanos.coerceAtLeast(0L)
                )
            )
        }

        var sumSquares = 0.0
        var peak = 0f
        var clippingSamples = 0
        for (index in 0 until boundedCount) {
            val amplitude = abs(samples[index].toFloat() / Short.MAX_VALUE)
            peak = max(peak, amplitude)
            sumSquares += amplitude * amplitude
            if (amplitude >= CLIPPING_SAMPLE_LEVEL) clippingSamples++
        }
        val rms = sqrt(sumSquares / boundedCount).toFloat()
        val safeNoiseFloor = noiseFloorRms.coerceIn(0f, 1f)
        val snr = if (safeNoiseFloor <= 0f) 60f
        else (20.0 * log10(max(rms / safeNoiseFloor, 1.0f).toDouble())).toFloat()
        val clippingRatio = clippingSamples.toFloat() / boundedCount
        val status = when {
            rms <= SILENCE_RMS -> AudioFrameStatus.SILENT
            clippingRatio >= OVERLOAD_CLIPPING_RATIO -> AudioFrameStatus.OVERLOADED
            rms < MINIMUM_SIGNAL_RMS -> AudioFrameStatus.LOW_SIGNAL
            snr < HIGH_NOISE_SNR_DB -> AudioFrameStatus.NOISY
            else -> AudioFrameStatus.VALID
        }
        val signalConfidence = when (status) {
            AudioFrameStatus.VALID -> ((rms - safeNoiseFloor) / max(rms, MINIMUM_SIGNAL_RMS))
                .coerceIn(0f, 1f)
            AudioFrameStatus.NOISY -> (snr / HIGH_NOISE_SNR_DB).coerceIn(0f, 1f)
            else -> 0f
        }
        return AudioFrameQuality(
            sampleCount = boundedCount,
            sampleRateHz = sampleRateHz,
            rms = rms,
            peak = peak.coerceIn(0f, 1f),
            clippingRatio = clippingRatio,
            noiseFloorRms = safeNoiseFloor,
            signalToNoiseRatioDb = snr.coerceAtLeast(0f),
            signalConfidence = signalConfidence,
            status = status,
            captureTimestampNanos = captureTimestampNanos,
            analysisStartTimestampNanos = analysisStartTimestampNanos,
            analysisEndTimestampNanos = analysisEndTimestampNanos
        )
    }
}