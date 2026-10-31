package com.pulse.camerapulse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var tvBpm: TextView
    private lateinit var tvHrv: TextView
    private lateinit var ppgProcessor: PpgProcessor

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvBpm = findViewById(R.id.tvBpm)
        tvHrv = findViewById(R.id.tvHrv)

        ppgProcessor = PpgProcessor { bpm, rmssd ->
            runOnUiThread {
                tvBpm.text = "${bpm.toInt()} BPM"
                tvHrv.text = "HRV (RMSSD): ${rmssd.toInt()} ms"
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

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

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        backgroundThread?.quitSafely()
    }
}
