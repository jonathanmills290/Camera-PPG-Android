package com.pulse.camerapulse

import java.util.ArrayDeque
import kotlin.math.sqrt

class PpgProcessor(private val onMetricsUpdate: (bpm: Double, rmssd: Double) -> Unit) {
    private var lastPeakTime = 0L
    private val rrIntervals = ArrayDeque<Long>(30)

    private var lowPassVal = 0.0
    private var highPassVal = 0.0
    private val alphaLow = 0.25
    private val alphaHigh = 0.03

    fun processPpgSample(rawIntensity: Double, timestamp: Long) {
        lowPassVal += alphaLow * (rawIntensity - lowPassVal)
        highPassVal += alphaHigh * (lowPassVal - highPassVal)
        val filtered = lowPassVal - highPassVal

        val elapsed = timestamp - lastPeakTime
        if (filtered > 2.0 && elapsed > 330) {
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
