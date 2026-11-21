package com.lingxi.feature.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

/**
 * 拍板书：CameraX 预览与拍照，一次可连续拍多张 [FR-CAP-01]；也可从系统相册导入 [FR-CAP-03]。
 *
 * 只负责拍照与选图：照片先存到 [outputDir]（应用私有目录），再通过回调交给 app 的整理流水线写库与识别。
 *
 * @param onCaptured 每拍一张回调一次（文件已写入私有目录）。
 * @param onImported 从相册选择的图片（系统照片选择器，无需存储权限）。
 * @param onDone 点「完成」返回。
 */
@Composable
fun CaptureFlowScreen(
    outputDir: File,
    onCaptured: (File) -> Unit,
    onImported: (List<Uri>) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    courseLabel: String? = null,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT)) { uris ->
            if (uris.isNotEmpty()) onImported(uris)
        }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                courseLabel?.let { "归档到：$it" } ?: "拍板书",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDone) { Text("完成") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            if (granted) {
                CameraPane(outputDir, onCaptured, onImport = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("需要相机权限才能拍板书", color = Color.White)
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("授权相机") }
                    FilledTonalButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("从相册导入") }
                }
            }
        }
    }
}

@Composable
private fun CameraPane(
    outputDir: File,
    onCaptured: (File) -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var shots by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }.onFailure { error = "无法打开相机：${it.message}" }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onImport) { Text("相册") }
            Box(
                Modifier
                    .size(72.dp)
                    .background(if (busy) Color.Gray else Color.White, CircleShape),
            ) {
                TextButton(
                    onClick = {
                        if (busy) return@TextButton
                        busy = true
                        outputDir.mkdirs()
                        val file = File(outputDir, "${UUID.randomUUID()}.jpg")
                        imageCapture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    busy = false
                                    shots++
                                    onCaptured(file)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    busy = false
                                    error = "拍照失败：${exception.message}"
                                }
                            },
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { Text("拍", color = Color.Black) }
            }
            Text("已拍 $shots 张", color = Color.White)
        }
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** 一次最多从相册导入的张数。 */
private const val MAX_IMPORT = 10
