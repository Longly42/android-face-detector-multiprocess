package com.example.androidfacedetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraScreen()
        }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 是否已有相机权限
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var statusText by remember { mutableStateOf("准备请求权限...") }

    // 界面要显示的两个数字/图片
    var fps by remember { mutableIntStateOf(0) }
    var debugBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 计数器：算 FPS 用；抽帧用（不用每帧都刷界面）
    val frameCounter = remember { AtomicInteger(0) }
    val sampleCounter = remember { AtomicInteger(0) }

    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    // 分析线程：给它起个名字，以后在 Profiler 里一眼能认出来
    val cameraExecutor = remember {
        Executors.newSingleThreadExecutor { r -> Thread(r, "cam-analysis") }
    }
    // 界面销毁时把线程收掉（原来的代码漏了这步）
    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    // 每秒算一次 FPS
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            fps = frameCounter.getAndSet(0)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasPermission = granted
            statusText = if (granted) "权限已允许，启动相机..." else "权限被拒绝！"
        }
    )

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            Log.d("CameraDebug", "发起权限请求")
            launcher.launch(Manifest.permission.CAMERA)
        } else {
            statusText = "已有权限，启动相机..."
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    Log.d("CameraDebug", "开始创建 PreviewView")
                    val previewView = PreviewView(ctx).apply {
                        // 用 FIT_CENTER：画面完整显示不裁剪，以后画框才对得准
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        Log.d("CameraDebug", "成功获取 CameraProvider")

                        // 1. 预览用例
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        // 2. 分析用例：要 640x480，只保留最新帧
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setTargetResolution(android.util.Size(640, 480))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        imageAnalysis.setAnalyzer(cameraExecutor) { image ->
                            try {
                                // 生帧 -> 摆正的 Bitmap
                                val bmp = image.toUprightBitmap(mirror = true)
                                frameCounter.incrementAndGet()

                                // 每 15 帧才刷一次界面，省电也省性能
                                if (sampleCounter.incrementAndGet() % 15 == 0) {
                                    mainExecutor.execute { debugBitmap = bmp }
                                    Log.d(
                                        "CameraDebug",
                                        "帧 ${image.width}x${image.height} " +
                                                "歪了${image.imageInfo.rotationDegrees}度 " +
                                                "结果 ${bmp.width}x${bmp.height}"
                                    )
                                }
                            } catch (t: Throwable) {
                                Log.e("CameraDebug", "帧转换失败", t)
                            } finally {
                                // 必须 close！不 close 就不会再送新帧
                                image.close()
                            }
                        }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                preview,
                                imageAnalysis
                            )
                            Log.d("CameraDebug", "相机绑定成功！")
                            statusText = "相机已启动"
                        } catch (e: Exception) {
                            Log.e("CameraDebug", "相机绑定异常", e)
                            statusText = "相机启动失败: ${e.message}"
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 左上角：状态 + FPS + 转换后的缩略图
        Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text(text = statusText, color = Color.Green)
            Text(text = "FPS: $fps", color = Color.Yellow)
            debugBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(120.dp)
                        .border(2.dp, Color.Green)
                )
            }
        }
    }
}

/** ImageProxy（生的 YUV 帧）→ 摆正 + 镜像后的 Bitmap */
fun ImageProxy.toUprightBitmap(mirror: Boolean): Bitmap {
    // toBitmap() 是 CameraX 1.3 自带的功能，帮我们把 YUV 转成图片
    // 如果这里报红：把光标放上去按 Alt+Enter，会自动加 import androidx.camera.core.toBitmap
    val src = toBitmap()

    val matrix = Matrix()
    val rot = imageInfo.rotationDegrees
    if (rot != 0) matrix.postRotate(rot.toFloat())   // 先转正
    if (mirror) matrix.postScale(-1f, 1f)            // 再左右翻转

    return if (matrix.isIdentity) src
    else Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}
