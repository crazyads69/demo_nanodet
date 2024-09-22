package com.grouptwo.demo_od
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.applyCanvas
import androidx.lifecycle.lifecycleScope
import com.grouptwo.demo_od.NanoDet
import com.grouptwo.demo_od.util.Box
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val USE_GPU = false
    }

    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null
    private var detecting by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize NanoDet in a background thread
        lifecycleScope.launch(Dispatchers.Default) {
            NanoDet.init(assets, USE_GPU)
            withContext(Dispatchers.Main) {
                if (allPermissionsGranted()) {
                    startCamera()
                } else {
                    ActivityCompat.requestPermissions(
                        this@MainActivity, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                    )
                }
            }
        }

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }


    @Composable
    fun MainScreen() {
        var threshold by remember { mutableDoubleStateOf(0.3) }
        var nmsThreshold by remember { mutableDoubleStateOf(0.7) }
        var overlayBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var infoText by remember { mutableStateOf("") }

        Column(modifier = Modifier.fillMaxSize()) {
            // Camera Preview with Overlay
            CameraPreview(
                onFrameAnalyzed = { image ->
                    detectOnModel(image, threshold, nmsThreshold) { bitmap, info ->
                        overlayBitmap = bitmap
                        infoText = info
                    }
                },
                overlayBitmap = overlayBitmap
            )

            // Controls
            ThresholdControls(
                threshold = threshold,
                nmsThreshold = nmsThreshold,
                onThresholdChange = { threshold = it.toDouble() },
                onNmsThresholdChange = { nmsThreshold = it.toDouble() }
            )

            // Info Text
            Text(text = infoText, modifier = Modifier.padding(8.dp))
        }

    }

    @Composable
    fun CameraPreview(onFrameAnalyzed: (ImageProxy) -> Unit, overlayBitmap: Bitmap?) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val context = LocalContext.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    val executor = ContextCompat.getMainExecutor(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build()
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        preview.setSurfaceProvider(previewView.surfaceProvider)

                        imageAnalyzer = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(executor) { image ->
                                    onFrameAnalyzed(image)
                                    image.close()
                                }
                            }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalyzer
                            )
                        } catch (exc: Exception) {
                            Log.e(TAG, "Use case binding failed", exc)
                        }
                    }, executor)
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Overlay
            overlayBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Detection Overlay",
                    modifier = Modifier.fillMaxSize(),
                    alignment = Alignment.Center
                )
            }
        }

    }

    @Composable
    fun ThresholdControls(
        threshold: Double,
        nmsThreshold: Double,
        onThresholdChange: (Float) -> Unit,
        onNmsThresholdChange: (Float) -> Unit
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Threshold: ${String.format("%.2f", threshold)}")
            Slider(
                value = threshold.toFloat(),
                onValueChange = onThresholdChange,
                valueRange = 0.0f..1.0f
            )
            Text("NMS Threshold: ${String.format("%.2f", nmsThreshold)}")
            Slider(
                value = nmsThreshold.toFloat(),
                onValueChange = onNmsThresholdChange,
                valueRange = 0.0f..1.0f
            )
        }
    }

private fun detectOnModel(
    image: ImageProxy,
    threshold: Double,
    nmsThreshold: Double,
    onResult: (Bitmap, String) -> Unit
) {
    if (detecting) return
    detecting = true

    val startTime = System.currentTimeMillis()
    val bitmap = imageToBitmap(image)

    lifecycleScope.launch(Dispatchers.Default) {
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        val result = NanoDet.detect(rotatedBitmap, threshold, nmsThreshold)

        // Create a transparent overlay bitmap
        val overlayBitmap = Bitmap.createBitmap(
            rotatedBitmap.width,
            rotatedBitmap.height,
            Bitmap.Config.ARGB_8888
        ).applyCanvas {
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }

        drawBoxRects(overlayBitmap, result, rotatedBitmap.width, rotatedBitmap.height)

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        val fps = 1000f / duration

        val info = "NanoDet ${if (USE_GPU) "GPU" else "CPU"}\n" +
                "Size: ${image.width}x${image.height}\n" +
                "Time: ${duration / 1000f} s\nFPS: $fps"

        withContext(Dispatchers.Main) {
            onResult(overlayBitmap, info)
            detecting = false
        }
    }
}

    private fun imageToBitmap(image: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

private fun drawBoxRects(overlayBitmap: Bitmap, results: Array<Box>, width: Int, height: Int): Bitmap {
    val canvas = Canvas(overlayBitmap)
    val boxPaint = Paint().apply {
        alpha = 200
        style = Paint.Style.STROKE
        strokeWidth = 4 * overlayBitmap.width / 800f
        textSize = 40 * overlayBitmap.width / 800f
    }

    for (box in results) {
        boxPaint.color = box.getColor()

        // Scale the bounding box coordinates to match the overlay bitmap size
        val scaleX = overlayBitmap.width / width.toFloat()
        val scaleY = overlayBitmap.height / height.toFloat()
        val scaledRect = RectF(
            box.x0 * scaleX,
            box.y0 * scaleY,
            box.x1 * scaleX,
            box.y1 * scaleY
        )

        boxPaint.style = Paint.Style.FILL
        canvas.drawText(
            "${box.getLabel()} ${String.format("%.3f", box.getScore())}",
            scaledRect.left + 3,
            scaledRect.top + 40 * overlayBitmap.width / 1000f,
            boxPaint
        )

        boxPaint.style = Paint.Style.STROKE
        canvas.drawRect(scaledRect, boxPaint)
    }

    return overlayBitmap
}

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

}