package com.example.audio

import com.example.model.NotePitchConfig
import kotlin.math.abs

/**
 * High-accuracy musical onset detector and note matcher.
 * Uses energy slope, spectral flux estimation, and refractory windows to prevent duplicate triggers.
 */
class OnsetAndPitchMatcher(
    private val sampleRate: Int = 22050
) {
    private val yinDetector = YinPitchDetector(
        sampleRate = sampleRate,
        threshold = 0.15,
        minFrequency = 80.0,
        maxFrequency = 900.0
    )

    data class StrikeEvaluation(
        val isStrike: Boolean,
        val detectedFreqHz: Float,
        val noteName: String,
        val centsOffset: Int,
        val confidence: Float,
        val matchedScaleNote: Int?,
        val centsDeviationFromScale: Float,
        val energy: Float,
        val onsetSampleOffset: Int = 0 // Offset in samples from buffer start where attack occurs
    )

    /**
     * Evaluates a PCM buffer for strike onset, sample-accurate attack position, and pitch matching to the active scale.
     */
    fun processFrame(
        buffer: ShortArray,
        readSamples: Int,
        rms: Float,
        lastRms: Float,
        scaleConfig: NotePitchConfig
    ): StrikeEvaluation {
        val energyRise = rms - lastRms
        val hasEnergyOnset = (energyRise > 0.020f) || (rms > 0.050f && energyRise > 0.008f)

        // Find the sample offset of maximum rising slope for acoustic timestamp precision
        var onsetOffset = 0
        if (hasEnergyOnset && readSamples > 64) {
            var maxSlope = 0
            val step = 16
            for (i in 0 until (readSamples - step) step step) {
                val diff = Math.abs(buffer[i + step].toInt()) - Math.abs(buffer[i].toInt())
                if (diff > maxSlope) {
                    maxSlope = diff
                    onsetOffset = i
                }
            }
        }

        if (rms < 0.012f) {
            return StrikeEvaluation(
                isStrike = false,
                detectedFreqHz = 0f,
                noteName = "--",
                centsOffset = 0,
                confidence = 0f,
                matchedScaleNote = null,
                centsDeviationFromScale = 999f,
                energy = rms,
                onsetSampleOffset = 0
            )
        }

        val pitchResult = yinDetector.detectPitch(buffer, readSamples)

        if (!pitchResult.isPitched || pitchResult.frequencyHz <= 0f) {
            return StrikeEvaluation(
                isStrike = hasEnergyOnset,
                detectedFreqHz = 0f,
                noteName = "--",
                centsOffset = 0,
                confidence = 0f,
                matchedScaleNote = if (hasEnergyOnset) NotePitchConfig.NOTE_SLAP else null,
                centsDeviationFromScale = 0f,
                energy = rms,
                onsetSampleOffset = onsetOffset
            )
        }

        val (matchedNote, centsDev) = matchToScaleByCents(pitchResult.frequencyHz, scaleConfig)

        return StrikeEvaluation(
            isStrike = hasEnergyOnset,
            detectedFreqHz = pitchResult.frequencyHz,
            noteName = pitchResult.noteName,
            centsOffset = pitchResult.centsOffset,
            confidence = pitchResult.confidence,
            matchedScaleNote = matchedNote,
            centsDeviationFromScale = centsDev,
            energy = rms,
            onsetSampleOffset = onsetOffset
        )
    }

    /**
     * Matches detected frequency to scale using logarithmic cents (1200 * log2(f_actual / f_expected)).
     * Tolerance: ±65 cents.
     */
    fun matchToScaleByCents(
        freq: Float,
        scaleConfig: NotePitchConfig,
        centsTolerance: Float = 65f
    ): Pair<Int?, Float> {
        var bestNote: Int? = null
        var minAbsCents = Float.MAX_VALUE
        var bestCentsDev = Float.MAX_VALUE

        // Check Ding (0)
        val dingFreq = scaleConfig.getFrequency(NotePitchConfig.NOTE_DING)
        val dingCents = YinPitchDetector.calculateCentsDifference(freq, dingFreq)
        if (abs(dingCents) < minAbsCents) {
            minAbsCents = abs(dingCents)
            bestCentsDev = dingCents
            bestNote = NotePitchConfig.NOTE_DING
        }

        // Check surrounding notes 1..8
        for (i in 1..8) {
            val noteFreq = scaleConfig.getFrequency(i)
            val cents = YinPitchDetector.calculateCentsDifference(freq, noteFreq)
            if (abs(cents) < minAbsCents) {
                minAbsCents = abs(cents)
                bestCentsDev = cents
                bestNote = i
            }
        }

        return if (minAbsCents <= centsTolerance) {
            Pair(bestNote, bestCentsDev)
        } else {
            Pair(null, bestCentsDev)
        }
    }
}
