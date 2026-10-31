package com.pulse.camerapulse

enum class PolygraphState {
    AWAITING_PULSE,
    CALIBRATING,
    READY,
    QUESTION_ACTIVE,
    EVALUATING,
    COMPLETE
}

data class QuestionResult(
    val question: String,
    val baselineBpm: Double,
    val peakBpm: Double,
    val baselineHrv: Double,
    val minHrv: Double,
    val stressScore: Int,
    val verdict: String,
    val isDeceptionAnomaly: Boolean
)

class PolygraphEngine(
    private val onStateChanged: (PolygraphState) -> Unit,
    private val onCalibrationProgress: (percent: Int, remainingSeconds: Int) -> Unit,
    private val onQuestionResult: (QuestionResult) -> Unit
) {
    var state = PolygraphState.AWAITING_PULSE
        private set

    val questions = listOf(
        "Is your name Jonathan?",
        "Are you located in Ohio right now?",
        "Have you withheld any critical information?",
        "Are you being completely honest about your commitments?",
        "Is there anything you are actively attempting to hide?"
    )

    var currentQuestionIndex = 0
        private set

    private val baselineBpmList = mutableListOf<Double>()
    private val baselineHrvList = mutableListOf<Double>()
    var baselineBpm = 0.0
        private set
    var baselineHrv = 0.0
        private set

    private val calibrationTargetSeconds = 15
    private var calibrationStartTime = 0L

    private var questionStartTime = 0L
    private val questionDurationMs = 8000L
    private var questionPeakBpm = 0.0
    private var questionMinHrv = 999.0
    private var questionSamplesCount = 0

    fun onNewBiometrics(bpm: Double, hrv: Double) {
        val now = System.currentTimeMillis()

        when (state) {
            PolygraphState.AWAITING_PULSE -> {
                if (bpm in 40.0..180.0) {
                    calibrationStartTime = now
                    state = PolygraphState.CALIBRATING
                    onStateChanged(state)
                }
            }
            PolygraphState.CALIBRATING -> {
                baselineBpmList.add(bpm)
                baselineHrvList.add(hrv)

                val elapsed = (now - calibrationStartTime) / 1000
                val percent = ((elapsed.toDouble() / calibrationTargetSeconds) * 100).toInt().coerceIn(0, 100)
                val remaining = (calibrationTargetSeconds - elapsed).toInt().coerceAtLeast(0)
                onCalibrationProgress(percent, remaining)

                if (elapsed >= calibrationTargetSeconds && baselineBpmList.size >= 5) {
                    baselineBpm = baselineBpmList.average()
                    baselineHrv = baselineHrvList.average()
                    state = PolygraphState.READY
                    onStateChanged(state)
                }
            }
            PolygraphState.QUESTION_ACTIVE -> {
                questionSamplesCount++
                if (bpm > questionPeakBpm) questionPeakBpm = bpm
                if (hrv < questionMinHrv) questionMinHrv = hrv

                val elapsed = now - questionStartTime
                if (elapsed >= questionDurationMs) {
                    finalizeQuestion()
                }
            }
            else -> {}
        }
    }

    fun startNextQuestion(): Boolean {
        if (currentQuestionIndex >= questions.size) {
            state = PolygraphState.COMPLETE
            onStateChanged(state)
            return false
        }
        questionStartTime = System.currentTimeMillis()
        questionPeakBpm = baselineBpm
        questionMinHrv = baselineHrv
        questionSamplesCount = 0
        state = PolygraphState.QUESTION_ACTIVE
        onStateChanged(state)
        return true
    }

    private fun finalizeQuestion() {
        val deltaBpm = (questionPeakBpm - baselineBpm).coerceAtLeast(0.0)
        val deltaHrv = (baselineHrv - questionMinHrv).coerceAtLeast(0.0)

        val bpmScore = (deltaBpm * 6.0).toInt().coerceIn(0, 50)
        val hrvScore = (deltaHrv * 2.5).toInt().coerceIn(0, 50)
        val compositeScore = (bpmScore + hrvScore).coerceIn(0, 100)

        val isAnomaly = compositeScore >= 60 || deltaBpm >= 9.0
        val verdict = when {
            compositeScore < 35 -> "TRUTH / CALM"
            compositeScore < 60 -> "ELEVATED / INCONCLUSIVE"
            else -> "DECEPTION / STRESS ANOMALY"
        }

        val result = QuestionResult(
            question = questions[currentQuestionIndex],
            baselineBpm = baselineBpm,
            peakBpm = questionPeakBpm,
            baselineHrv = baselineHrv,
            minHrv = questionMinHrv,
            stressScore = compositeScore,
            verdict = verdict,
            isDeceptionAnomaly = isAnomaly
        )

        currentQuestionIndex++
        state = if (currentQuestionIndex >= questions.size) PolygraphState.COMPLETE else PolygraphState.READY
        onQuestionResult(result)
        onStateChanged(state)
    }

    fun reset() {
        baselineBpmList.clear()
        baselineHrvList.clear()
        baselineBpm = 0.0
        baselineHrv = 0.0
        currentQuestionIndex = 0
        state = PolygraphState.AWAITING_PULSE
        onStateChanged(state)
    }
}
