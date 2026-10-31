package com.pulse.camerapulse

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusBadge: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvQuestionTitle: TextView
    private lateinit var tvQuestionText: TextView
    private lateinit var btnAction: Button
    private lateinit var waveformView: WaveformView
    private lateinit var tvBpmLive: TextView
    private lateinit var tvBpmBaseline: TextView
    private lateinit var tvHrvLive: TextView
    private lateinit var tvHrvBaseline: TextView
    private lateinit var layoutVerdict: LinearLayout
    private lateinit var tvVerdictTitle: TextView
    private lateinit var tvVerdictSubtitle: TextView

    private lateinit var ppgProcessor: PpgProcessor
    private lateinit var polygraphEngine: PolygraphEngine

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        progressBar = findViewById(R.id.progressBar)
        tvQuestionTitle = findViewById(R.id.tvQuestionTitle)
        tvQuestionText = findViewById(R.id.tvQuestionText)
        btnAction = findViewById(R.id.btnAction)
        waveformView = findViewById(R.id.waveformView)
        tvBpmLive = findViewById(R.id.tvBpmLive)
        tvBpmBaseline = findViewById(R.id.tvBpmBaseline)
        tvHrvLive = findViewById(R.id.tvHrvLive)
        tvHrvBaseline = findViewById(R.id.tvHrvBaseline)
        layoutVerdict = findViewById(R.id.layoutVerdict)
        tvVerdictTitle = findViewById(R.id.tvVerdictTitle)
        tvVerdictSubtitle = findViewById(R.id.tvVerdictSubtitle)

        setupPolygraph()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            startCamera()
        }
    }

    private fun setupPolygraph() {
        polygraphEngine = PolygraphEngine(
            onStateChanged = { state ->
                runOnUiThread { handleStateChange(state) }
            },
            onCalibrationProgress = { percent, remainingSeconds ->
                runOnUiThread {
                    progressBar.progress = percent
                    btnAction.text = "Calibrating ($remainingSeconds s)..."
                }
            },
            onQuestionResult = { result ->
                runOnUiThread { displayQuestionResult(result) }
            }
        )

        ppgProcessor = PpgProcessor(
            onMetricsUpdate = { bpm, rmssd ->
                runOnUiThread {
                    tvBpmLive.text = "${bpm.toInt()} BPM"
                    tvHrvLive.text = "${rmssd.toInt()} ms"
                }
                polygraphEngine.onNewBiometrics(bpm, rmssd)
            },
            onWaveformSample = { sample ->
                waveformView.addSample(sample)
            }
        )

        btnAction.setOnClickListener {
            if (polygraphEngine.state == PolygraphState.READY) {
                val started = polygraphEngine.startNextQuestion()
                if (started) {
                    val qIndex = polygraphEngine.currentQuestionIndex + 1
                    tvQuestionTitle.text = "QUESTION $qIndex OF ${polygraphEngine.questions.size}"
                    tvQuestionText.text = polygraphEngine.questions[polygraphEngine.currentQuestionIndex]
                    btnAction.text = "Recording Response (8s)..."
                    btnAction.isEnabled = false
                    btnAction.setBackgroundColor(Color.parseColor("#E65100"))
                    waveformView.setWaveformColor(Color.parseColor("#FFD600"))
                }
            } else if (polygraphEngine.state == PolygraphState.COMPLETE) {
                polygraphEngine.reset()
                handleStateChange(PolygraphState.AWAITING_PULSE)
            }
        }
    }

    private fun handleStateChange(state: PolygraphState) {
        when (state) {
            PolygraphState.AWAITING_PULSE -> {
                tvStatusBadge.text = "PLACE FINGER"
                tvStatusBadge.setTextColor(Color.parseColor("#FFB300"))
                tvQuestionTitle.text = "SENSOR INITIALIZATION"
                tvQuestionText.text = "Place index finger gently over camera lens and flash..."
                btnAction.text = "Awaiting Contact"
                btnAction.isEnabled = false
                btnAction.setBackgroundColor(Color.parseColor("#37474F"))
                waveformView.setWaveformColor(Color.parseColor("#78909C"))
                progressBar.progress = 0
            }
            PolygraphState.CALIBRATING -> {
                tvStatusBadge.text = "CALIBRATING"
                tvStatusBadge.setTextColor(Color.parseColor("#40C4FF"))
                tvQuestionTitle.text = "BASELINE CALIBRATION"
                tvQuestionText.text = "Keep finger still and relax. Establishing autonomic baseline..."
                btnAction.isEnabled = false
                btnAction.setBackgroundColor(Color.parseColor("#00838F"))
                waveformView.setWaveformColor(Color.parseColor("#00E676"))
            }
            PolygraphState.READY -> {
                tvStatusBadge.text = "BASELINE READY"
                tvStatusBadge.setTextColor(Color.parseColor("#00E676"))
                tvBpmBaseline.text = "Base: ${polygraphEngine.baselineBpm.toInt()} BPM"
                tvHrvBaseline.text = "Base: ${polygraphEngine.baselineHrv.toInt()} ms"

                val qNum = polygraphEngine.currentQuestionIndex + 1
                tvQuestionTitle.text = "READY FOR QUESTION $qNum"
                tvQuestionText.text = "Ask: \"${polygraphEngine.questions[polygraphEngine.currentQuestionIndex]}\""
                btnAction.text = "Begin Question $qNum"
                btnAction.isEnabled = true
                btnAction.setBackgroundColor(Color.parseColor("#2E7D32"))
                waveformView.setWaveformColor(Color.parseColor("#00E676"))
            }
            PolygraphState.QUESTION_ACTIVE -> {
                tvStatusBadge.text = "ANALYZING"
                tvStatusBadge.setTextColor(Color.parseColor("#FF9100"))
            }
            PolygraphState.EVALUATING -> {}
            PolygraphState.COMPLETE -> {
                tvStatusBadge.text = "EXAM COMPLETE"
                tvStatusBadge.setTextColor(Color.parseColor("#00E676"))
                tvQuestionTitle.text = "POLYGRAPH SESSION FINISHED"
                tvQuestionText.text = "All questions evaluated against physiological baseline."
                btnAction.text = "Restart Polygraph Exam"
                btnAction.isEnabled = true
                btnAction.setBackgroundColor(Color.parseColor("#1565C0"))
            }
        }
    }

    private fun displayQuestionResult(result: QuestionResult) {
        val deltaBpm = result.peakBpm - result.baselineBpm
        val deltaHrv = result.baselineHrv - result.minHrv

        tvVerdictTitle.text = result.verdict
        tvVerdictSubtitle.text = "Stress Score: ${result.stressScore}% | ΔBPM: +${deltaBpm.toInt()} | ΔHRV: -${deltaHrv.toInt()}ms"

        if (result.isDeceptionAnomaly) {
            layoutVerdict.setBackgroundColor(Color.parseColor("#3E1B1B"))
            tvVerdictTitle.setTextColor(Color.parseColor("#FF5252"))
            waveformView.setWaveformColor(Color.parseColor("#FF1744"))
        } else if (result.stressScore < 35) {
            layoutVerdict.setBackgroundColor(Color.parseColor("#1B3320"))
            tvVerdictTitle.setTextColor(Color.parseColor("#69F0AE"))
            waveformView.setWaveformColor(Color.parseColor("#00E676"))
        } else {
            layoutVerdict.setBackgroundColor(Color.parseColor("#382E18"))
            tvVerdictTitle.setTextColor(Color.parseColor("#FFD740"))
            waveformView.setWaveformColor(Color.parseColor("#FFC400"))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        backgroundThread = HandlerThread("CameraBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backCameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList[0]

        imageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val yPlane = image.planes[0]
                    val vPlane = image.planes[2]
                    val yBuffer = yPlane.buffer
                    val vBuffer = vPlane.buffer
                    val yRowStride = yPlane.rowStride
                    val vRowStride = vPlane.rowStride
                    val vPixelStride = vPlane.pixelStride

                    val startX = (image.width - 60) / 2
                    val startY = (image.height - 60) / 2
                    val endX = startX + 60
                    val endY = startY + 60

                    var totalRed = 0.0
                    var count = 0

                    for (y in startY until endY step 2) {
                        val yRow = y * yRowStride
                        val vRow = (y / 2) * vRowStride
                        for (x in startX until endX step 2) {
                            val yVal = yBuffer.get(yRow + x).toInt() and 0xFF
                            val vVal = vBuffer.get(vRow + (x / 2) * vPixelStride).toInt() and 0xFF
                            totalRed += (yVal + 1.402 * (vVal - 128)).coerceIn(0.0, 255.0)
                            count++
                        }
                    }
                    if (count > 0) ppgProcessor.processPpgSample(totalRed / count, System.currentTimeMillis())
                } finally {
                    image.close()
                }
            }, backgroundHandler)
        }

        manager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val reader = imageReader ?: return
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_LOCK, true)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                }
                camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(req.build(), null, backgroundHandler)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, backgroundHandler)
            }
            override fun onDisconnected(c: CameraDevice) { c.close() }
            override fun onError(c: CameraDevice, err: Int) { c.close() }
        }, backgroundHandler)
    }

    override fun onDestroy() {
        super.onDestroy()
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        backgroundThread?.quitSafely()
    }
}
