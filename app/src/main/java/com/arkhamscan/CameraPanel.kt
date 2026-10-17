package com.arkhamscan

import android.Manifest
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.arkhamscan.core.Mode
import com.arkhamscan.core.PresentationGate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/** Capture belongs to the active Activity, not a lazy-list item or browser lifetime. */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun CaptureEngine(vm: ScanViewModel, previewView: PreviewView) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val desired by vm.remote.cameraRequest.collectAsState()
    val foreground by vm.remote.foreground.collectAsState()
    val generation by vm.remote.cameraGeneration.collectAsState()
    val withPreview by vm.remote.previewAttached.collectAsState()
    val mode by vm.mode.collectAsState()
    val currentMode by rememberUpdatedState(mode)
    val torch by vm.remote.torch.collectAsState()
    var camera by remember { mutableStateOf<Camera?>(null) }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    if (desired != "running" || !foreground || !vm.remote.permissionGranted()) return
    DisposableEffect(owner, generation, withPreview) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val main = ContextCompat.getMainExecutor(context)
        val gate = PresentationGate()
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        var lastAnalyzedAt = 0L
        providerFuture.addListener({
            if (!disposed) try {
                provider = providerFuture.get()
                preview = if (withPreview) Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) } else null
                analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(1280, 960)).build()
                analysis!!.setAnalyzer(executor) { frame ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAnalyzedAt < 250L) { frame.close(); return@setAnalyzer }
                    lastAnalyzedAt = now
                    val image = frame.image
                    if (image == null || disposed) frame.close() else {
                        recognizer.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees)).addOnSuccessListener(main) { result ->
                            if (!disposed && vm.remote.cameraRequest.value == "running" && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                vm.remote.cameraStatus.value = "scanning"
                                vm.recognizedText(result.text)
                                if (gate.observe(result.text.trim().length >= 8, SystemClock.elapsedRealtime()) && currentMode == Mode.RAPID_NEW_INVENTORY) vm.queuePresentation()
                            }
                        }.addOnFailureListener(main) { if (!disposed && vm.remote.cameraRequest.value == "running") vm.remote.cameraFailure("OCR unavailable: ${it.message}") }
                            .addOnCompleteListener { frame.close() }
                    }
                }
                camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *listOfNotNull(preview, analysis).toTypedArray())
                vm.remote.cameraStatus.value = "camera ready · waiting for OCR"; vm.remote.changed()
                previewView.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(previewView.meteringPointFactory.createPoint(event.x, event.y)).build())
                        previewView.performClick()
                    }
                    true
                }
            } catch (e: Exception) { vm.remote.cameraFailure("Camera unavailable: ${e.message}") }
        }, main)
        onDispose {
            disposed = true; analysis?.clearAnalyzer()
            provider?.unbind(*listOfNotNull(preview, analysis).toTypedArray()); camera = null
            recognizer.close(); executor.shutdown()
        }
    }
}

@Composable
fun CameraPanel(vm: ScanViewModel, mode: Mode, previewView: PreviewView) {
    var granted by remember { mutableStateOf(vm.remote.permissionGranted()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; vm.remote.changed() }
    if (!granted) {
        Text("Camera access is optional. You can search the catalog and confirm copies manually.")
        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        return
    }
    val running by vm.remote.cameraRequest.collectAsState()
    val status by vm.remote.cameraStatus.collectAsState()
    val error by vm.remote.cameraError.collectAsState()
    val torch by vm.remote.torch.collectAsState()
    Text("Experimental live OCR", style = MaterialTheme.typography.titleLarge)
    Text("Automatic acceptance is disabled. Use Interactive mode for browser selection and confirmation. Rapid mode queues uncertain attempts without adding ownership.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { runCatching { vm.remote.camera(if (running == "running") "pause" else "start") }.onFailure { vm.remote.cameraFailure(it.message ?: "Camera unavailable") } }) { Text(if (running == "running") "Pause camera" else "Start camera") }
        FilterChip(selected = torch, onClick = { vm.remote.torch.value = !torch }, label = { Text("Torch") })
    }
    Text(status)
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (running != "running") return
    DisposableEffect(Unit) { vm.remote.previewAttached.value = true; onDispose { vm.remote.previewAttached.value = false } }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(260.dp))
    Text("Tap preview to focus. Frames stay on the phone; the browser receives metadata only.", style = MaterialTheme.typography.labelSmall)
}
