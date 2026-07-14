package io.digibyte.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import io.digibyte.core.model.DigiByteUri
import io.digibyte.core.model.DigiIdRequest
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteNavy
import android.os.Handler
import android.os.Looper
import android.util.Size
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "QrScannerScreen"

/**
 * Full-screen QR scanner shared between wallet Scan and Digi-ID Scan.
 *
 * On successful scan, the URI type is detected:
 *   - digiid://   → onDigiId(DigiIdRequest)
 *   - dgbnode://  → onNode(raw)
 *   - digibyte:   → onDigiByteUri(DigiByteUri)
 *   - valid addr  → onDigiByteUri(DigiByteUri(address))
 *   - other       → onInvalidQr()
 *
 * Camera permission is requested inline with a rationale dialog.
 */
@Composable
fun QrScannerScreen(
    onDigiId: (DigiIdRequest) -> Unit,
    onDigiByteUri: (DigiByteUri) -> Unit,
    onNode: (String) -> Unit,
    onInvalidQr: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var showRationale by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) showRationale = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Rationale dialog shown when permission is denied
    if (showRationale) {
        AlertDialog(
            onDismissRequest = { onCancel() },
            title = { Text("Camera Permission Required") },
            text = {
                Text(
                    "The QR scanner needs camera access to read DigiByte addresses and Digi-ID codes. " +
                        "Please grant camera permission in Settings."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }) { Text("Try Again") }
            },
            dismissButton = {
                TextButton(onClick = { onCancel() }) { Text("Cancel") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            CameraPreviewWithZxing(
                onScanResult = { raw ->
                    dispatchScanResult(raw, onDigiId, onDigiByteUri, onNode, onInvalidQr)
                }
            )
        } else {
            // Waiting for permission — show placeholder
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = DigiByteAccent)
            }
        }

        // Overlay UI
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DigiByteNavy.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scan QR Code",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = Color.White
                        )
                    }
                }
            }

            // Viewfinder hint
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Text(
                        text = "Point camera at a DigiByte address or Digi-ID QR code",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Cancel button at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("Cancel")
            }
        }
    }
}

/** Camera preview wired to ZXing via ImageAnalysis. Single-scan (auto-closes after first hit). */
@Composable
private fun CameraPreviewWithZxing(
    onScanResult: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Thread-safe guard against multiple callbacks for a single scan
    val scanned = remember { AtomicBoolean(false) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    // Use a callback instead of blocking .get() — avoids hanging on Android 15+
    // where camera service initialization can be slow.
    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor, ZxingAnalyzer { result ->
                            if (scanned.compareAndSet(false, true)) {
                                mainHandler.post { onScanResult(result) }
                            }
                        })
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

/** ZXing ImageAnalysis.Analyzer — feeds YUV frames to MultiFormatReader. */
private class ZxingAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height

            // On some devices (especially Android 15+), rowStride > width due to
            // memory alignment padding. Strip padding so ZXing gets clean pixel rows.
            val bytes: ByteArray
            val dataWidth: Int
            if (rowStride == width) {
                bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                dataWidth = width
            } else {
                // Copy row-by-row, skipping stride padding
                bytes = ByteArray(width * height)
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(bytes, row * width, width)
                }
                dataWidth = width
            }

            val source = PlanarYUVLuminanceSource(
                bytes, dataWidth, height,
                0, 0, width, height, false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)
            onResult(result.text)
        } catch (_: NotFoundException) {
            // No QR in this frame — normal, ignore
        } catch (e: Exception) {
            Log.w(TAG, "ZXing error: ${e.message}")
        } finally {
            image.close()
        }
    }
}

/** Route the raw scan string to the appropriate callback. */
private fun dispatchScanResult(
    raw: String,
    onDigiId: (DigiIdRequest) -> Unit,
    onDigiByteUri: (DigiByteUri) -> Unit,
    onNode: (String) -> Unit,
    onInvalidQr: (String) -> Unit
) {
    val trimmed = raw.trim()
    when {
        trimmed.startsWith("digiid://") -> {
            val request = DigiIdRequest.parse(trimmed)
            if (request != null) {
                onDigiId(request)
            } else {
                onInvalidQr("Invalid Digi-ID QR: missing required fields")
            }
        }
        trimmed.startsWith("dgbnode://") -> onNode(trimmed)
        trimmed.startsWith("digibyte:") -> {
            val uri = DigiByteUri.parse(trimmed)
            if (uri != null) {
                onDigiByteUri(uri)
            } else {
                onInvalidQr("Invalid DigiByte payment URI")
            }
        }
        else -> {
            // Attempt to treat as a raw address
            val uri = DigiByteUri.parse(trimmed)
            if (uri != null && trimmed.isNotBlank()) {
                onDigiByteUri(uri)
            } else {
                onInvalidQr("Unrecognized QR code content")
            }
        }
    }
}
