package com.arkhamscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.MotionEvent
import android.app.Activity
import android.view.WindowManager
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

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPanel(vm: ScanViewModel, mode: Mode) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Text("Camera access is optional. You can search the catalog and confirm copies manually.")
        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        return
    }
    var running by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Experimental live OCR", style = MaterialTheme.typography.titleLarge)
    Text("Automatic acceptance is disabled until real-card validation. In rapid mode each stable presentation is directed to Re-sort. Remove the card completely between presentations.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { running = !running }) { Text(if (running) "Pause camera" else "Start camera") }
        FilterChip(selected = torch, onClick = { torch = !torch }, label = { Text("Torch") })
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!running) { Text("Camera paused · no frames retained"); return }
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val gate = remember { PresentationGate() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val currentMode by rememberUpdatedState(mode)
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    DisposableEffect(owner, previewView) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val main = ContextCompat.getMainExecutor(context)
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        var lastAnalyzedAt = 0L
        providerFuture.addListener({
            if (!disposed) try {
                provider = providerFuture.get()
                preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(1280, 960)).build()
                analysis!!.setAnalyzer(executor) { frame ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAnalyzedAt < 250L) { frame.close(); return@setAnalyzer }
                    lastAnalyzedAt = now
                    val image = frame.image
                    if (image == null || disposed) frame.close() else {
                        val input = InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees)
                        recognizer.process(input).addOnSuccessListener(main) { result ->
                            if (!disposed && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                vm.recognizedText(result.text)
                                val presentation = gate.observe(result.text.trim().length >= 8, SystemClock.elapsedRealtime())
                                if (presentation && currentMode == Mode.RAPID_NEW_INVENTORY) vm.queuePresentation()
                            }
                        }.addOnFailureListener(main) { if (!disposed) error = "OCR unavailable: ${it.message}" }
                            .addOnCompleteListener { frame.close() }
                    }
                }
                camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                previewView.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                        previewView.performClick()
                    }
                    true
                }
            } catch (e: Exception) { error = "Camera unavailable: ${e.message}" }
        }, main)
        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            val uses = listOfNotNull(preview, analysis).toTypedArray()
            provider?.unbind(*uses)
            recognizer.close(); executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(300.dp))
    Text("Tap preview to focus. OCR runs in memory; no camera files or thumbnails are created.", style = MaterialTheme.typography.labelSmall)
}
