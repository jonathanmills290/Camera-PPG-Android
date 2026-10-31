package com.pulse.camerapulse

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

class PpgProcessor(
    private val onMetricsUpdate: (bpm: Double, rmssd: Double) -> Unit,
    private val onWaveformSample: (Float) -> Unit
) {
    private var lastPeakTime = 0L
    private val rrIntervals = ArrayDeque<Long>(30)

    private var lowPassVal = 0.0
    private var highPassVal = 0.0
    private val alphaLow = 0.25
    private val alphaHigh = 0.03
    private var maxAmp = 2.0

    fun processPpgSample(rawIntensity: Double, timestamp: Long) {
        lowPassVal += alphaLow * (rawIntensity - lowPassVal)
        highPassVal += alphaHigh * (lowPassVal - highPassVal)
        val filtered = lowPassVal - highPassVal

        val absVal = abs(filtered)
        if (absVal > maxAmp) {
            maxAmp = absVal
        } else {
            maxAmp = maxAmp * 0.995 + 0.005 * 2.0
        }
        val normalized = (filtered / maxAmp).toFloat()
        onWaveformSample(normalized)

        // Dynamic thresholding: auto-scales to offset/recessed flash
        val adaptiveThreshold = (maxAmp * 0.35).coerceIn(0.5, 2.5)

        val elapsed = timestamp - lastPeakTime
        if (filtered > adaptiveThreshold && elapsed > 330) {
            if (lastPeakTime > 0 && elapsed in 330..1500) {
                rrIntervals.addLast(elapsed)
                if (rrIntervals.size > 20) rrIntervals.removeFirst()

                val bpm = 60000.0 / elapsed
                val rmssd = calculateRMSSD(rrIntervals)
                onMetricsUpdate(bpm, rmssd)
            }
            lastPeakTime = timestamp
        }
    }

    private fun calculateRMSSD(intervals: Collection<Long>): Double {
        if (intervals.size < 2) return 0.0
        val diffs = intervals.zipWithNext { a, b -> (b - a).toDouble() }
        val sumSquared = diffs.sumOf { it * it }
        return sqrt(sumSquared / diffs.size)
    }
}
