package com.grouptwo.demo_od

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.applyCanvas
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
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
    }

    private lateinit var cameraExecutor: ExecutorService
    private var detecting by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize NanoDet and start camera if permissions are granted
        lifecycleScope.launch(Dispatchers.Default) {
            NanoDet.init(assets, false) // Initialize with CPU by default
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
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        // State variables
        var threshold by remember { mutableDoubleStateOf(0.3) }
        var nmsThreshold by remember { mutableDoubleStateOf(0.7) }
        var overlayBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var isBackCamera by remember { mutableStateOf(true) }
        var useGPU by remember { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }
        var showThresholdDialog by remember { mutableStateOf(false) }
        var fps by remember { mutableStateOf(0f) }

        // Initialize NanoDet when GPU usage changes
        LaunchedEffect(useGPU) {
            NanoDet.init(assets, useGPU)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("NanoDet Object Detection") },
                    actions = {
                        // Menu button and dropdown
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Threshold Settings") },
                                onClick = {
                                    showThresholdDialog = true
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (useGPU) "Disable GPU" else "Enable GPU") },
                                onClick = {
                                    useGPU = !useGPU
                                    showMenu = false
                                }
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Camera preview and object detection
                CameraPreview(
                    onFrameAnalyzed = { image ->
                        detectOnModel(image, threshold, nmsThreshold, useGPU) { bitmap, detectionFps ->
                            overlayBitmap = bitmap
                            fps = detectionFps
                        }
                    },
                    overlayBitmap = overlayBitmap,
                    isBackCamera = isBackCamera,
                    onSwitchCamera = { isBackCamera = !isBackCamera }
                )

                // GPU/CPU indicator
                Text(
                    text = if (useGPU) "GPU" else "CPU",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    color = androidx.compose.ui.graphics.Color.Green,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                // FPS counter
                Text(
                    text = String.format("%.1f FPS", fps),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.Green,
                )
            }
        }

        // Threshold settings dialog
        if (showThresholdDialog) {
            ThresholdSettingsDialog(
                threshold = threshold,
                nmsThreshold = nmsThreshold,
                onThresholdChange = { threshold = it },
                onNmsThresholdChange = { nmsThreshold = it },
                onDismiss = { showThresholdDialog = false }
            )
        }
    }

    @Composable
    fun ThresholdSettingsDialog(
        threshold: Double,
        nmsThreshold: Double,
        onThresholdChange: (Double) -> Unit,
        onNmsThresholdChange: (Double) -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Threshold Settings") },
            text = {
                Column {
                    Text("Threshold: ${String.format("%.2f", threshold)}")
                    Slider(
                        value = threshold.toFloat(),
                        onValueChange = { onThresholdChange(it.toDouble()) },
                        valueRange = 0f..1f
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("NMS Threshold: ${String.format("%.2f", nmsThreshold)}")
                    Slider(
                        value = nmsThreshold.toFloat(),
                        onValueChange = { onNmsThresholdChange(it.toDouble()) },
                        valueRange = 0f..1f
                    )
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text("OK")
                }
            }
        )
    }

    @Composable
    fun CameraPreview(
        onFrameAnalyzed: (ImageProxy) -> Unit,
        overlayBitmap: Bitmap?,
        isBackCamera: Boolean,
        onSwitchCamera: () -> Unit
    ) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val context = LocalContext.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
        val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

        // Set up camera when the camera direction changes
        LaunchedEffect(isBackCamera) {
            setupCamera(
                context,
                lifecycleOwner,
                cameraProviderFuture,
                previewView,
                isBackCamera,
                onFrameAnalyzed
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Camera preview
            AndroidView(
                factory = { previewView.apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                } },
                modifier = Modifier.fillMaxSize()
            )

            // Overlay with detection results
            overlayBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Detection Overlay",
                    modifier = Modifier.fillMaxSize(),
                    alignment = Alignment.Center
                )
            }

            // Switch camera button
            Button(
                onClick = onSwitchCamera,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Text("Switch Camera")
            }
        }
    }

    private fun setupCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        cameraProviderFuture: ListenableFuture<ProcessCameraProvider>,
        previewView: PreviewView,
        isBackCamera: Boolean,
        onFrameAnalyzed: (ImageProxy) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val cameraSelector = if (isBackCamera) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val imageAnalyzer = ImageAnalysis.Builder()
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
    }

    private fun detectOnModel(
        image: ImageProxy,
        threshold: Double,
        nmsThreshold: Double,
        useGPU: Boolean,
        onResult: (Bitmap, Float) -> Unit
    ) {
        if (detecting) return
        detecting = true

        val startTime = System.currentTimeMillis()
        val bitmap = imageToBitmap(image)

        lifecycleScope.launch(Dispatchers.Default) {
            // Rotate the bitmap if necessary
            val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            // Perform object detection
            val result = NanoDet.detect(rotatedBitmap, threshold, nmsThreshold)

            // Create overlay bitmap
            val overlayBitmap = Bitmap.createBitmap(
                rotatedBitmap.width,
                rotatedBitmap.height,
                Bitmap.Config.ARGB_8888
            ).applyCanvas {
                drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }

            // Draw detection results on the overlay
            drawBoxRects(overlayBitmap, result, rotatedBitmap.width, rotatedBitmap.height)

            // Calculate FPS
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime
            val fps = 1000f / duration

            withContext(Dispatchers.Main) {
                onResult(overlayBitmap, fps)
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

        // Copy Y, U, and V data to nv21 array
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        // Convert NV21 format to Bitmap
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

//    private fun drawBoxRects(overlayBitmap: Bitmap, results: Array<Box>, width: Int, height: Int): Bitmap {
//        val canvas = Canvas(overlayBitmap)
//        val boxPaint = Paint().apply {
//            alpha = 200
//            style = Paint.Style.STROKE
//            strokeWidth = 4 * overlayBitmap.width / 800f
//            textSize = 40 * overlayBitmap.width / 800f
//        }
//
//        for (box in results) {
//            boxPaint.color = box.getColor()
//
//            // Scale the bounding box to match the overlay dimensions
//            val scaleX = overlayBitmap.width / width.toFloat()
//            val scaleY = overlayBitmap.height / height.toFloat()
//            val scaledRect = RectF(
//                box.x0 * scaleX,
//                box.y0 * scaleY,
//                box.x1 * scaleX,
//                box.y1 * scaleY
//            )
//
//            // Draw the label and score
//            boxPaint.style = Paint.Style.FILL
//            canvas.drawText(
//                "${box.getLabel()} ${String.format("%.3f", box.getScore())}",
//                scaledRect.left + 3,
//                scaledRect.top + 40 * overlayBitmap.width / 1000f,
//                boxPaint
//            )
//
//            // Draw the bounding box
//            boxPaint.style = Paint.Style.STROKE
//            canvas.drawRect(scaledRect, boxPaint)
//        }
//
//        return overlayBitmap
//    }

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

            // Ensure correct scaling
            val scaleX = overlayBitmap.width / width.toFloat()
            val scaleY = overlayBitmap.height / height.toFloat()

            // Map coordinates correctly
            val left = box.x0 * scaleX
            val top = box.y0 * scaleY
            val right = box.x1 * scaleX
            val bottom = box.y1 * scaleY

            val scaledRect = RectF(left, top, right, bottom)

            // Draw the bounding box
            canvas.drawRect(scaledRect, boxPaint)

            // Draw the label
            canvas.drawText(
                "${box.getLabel()} ${String.format("%.2f", box.getScore())}",
                left,
                top - 10,
                boxPaint
            )
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