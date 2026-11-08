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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.androidfacedetector.detector.DetectResult
import com.example.androidfacedetector.detector.ScrfdDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

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

    // ---------- 各种状态 ----------
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var statusText by remember { mutableStateOf("准备请求权限...") }
    var detector by remember { mutableStateOf<ScrfdDetector?>(null) }
    var result by remember { mutableStateOf<DetectResult?>(null) }
    var frameW by remember { mutableIntStateOf(0) }   // 分析帧的宽（摆正后）
    var frameH by remember { mutableIntStateOf(0) }   // 分析帧的高
    var fps by remember { mutableIntStateOf(0) }

    val frameCounter = remember { AtomicInteger(0) }
    val busy = remember { AtomicBoolean(false) }      // 推理没做完就跳过新帧（第5周换成 Semaphore）
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    val cameraExecutor = remember {
        Executors.newSingleThreadExecutor { r -> Thread(r, "cam-analysis") }
    }

    // 每秒刷新 FPS
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            fps = frameCounter.getAndSet(0)
        }
    }

    // 加载模型：只做一次，放到 IO 线程，别卡住界面
    LaunchedEffect(Unit) {
        statusText = "正在加载人脸检测模型..."
        val loaded = withContext(Dispatchers.IO) {
            try {
                ScrfdDetector(context)
            } catch (t: Throwable) {
                Log.e("CameraDebug", "模型加载失败", t)
                null
            }
        }
        if (loaded != null) {
            detector = loaded
            statusText = "模型已就绪"
        } else {
            statusText = "模型加载失败：检查 assets/${ScrfdDetector.DEFAULT_MODEL}"
        }
    }

    // 界面销毁时收掉线程和模型
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            detector?.close()
        }
    }

    // ---------- 权限 ----------
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasPermission = granted
            statusText = if (granted) "权限已允许" else "权限被拒绝！"
        }
    )

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            Log.d("CameraDebug", "发起权限请求")
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------- 界面 ----------
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        // FIT_CENTER：画面完整不裁剪，和下面的画框坐标能对上
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                    }

                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(android.util.Size(640, 480))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analysis.setAnalyzer(cameraExecutor) { image ->
                            try {
                                val bitmap = image.toUprightBitmap(mirror = true)
                                frameCounter.incrementAndGet()

                                val d = detector
                                if (d != null && busy.compareAndSet(false, true)) {
                                    try {
                                        val r = d.detect(bitmap)
                                        mainExecutor.execute {
                                            result = r
                                            frameW = bitmap.width
                                            frameH = bitmap.height
                                        }
                                    } finally {
                                        busy.set(false)
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.e("CameraDebug", "帧处理失败", t)
                            } finally {
                                image.close()
                            }
                        }

                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                preview,
                                analysis
                            )
                            Log.d("CameraDebug", "相机绑定成功")
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

        // 人脸框 + 关键点
        DetectionOverlay(result, frameW, frameH)

        // 左上角信息面板
        Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text(text = statusText, color = Color.Green, fontSize = 13.sp)
            Text(text = "FPS: $fps", color = Color.Yellow, fontSize = 13.sp)
            result?.let { r ->
                Text(text = "人脸: ${r.detections.size}", color = Color.White, fontSize = 13.sp)
                r.detections.firstOrNull()?.let {
                    Text(text = "置信度: ${"%.2f".format(it.score)}", color = Color.White, fontSize = 13.sp)
                }
                Text(
                    text = "预处理 ${"%.1f".format(r.preMs)}ms  " +
                            "推理 ${"%.1f".format(r.inferMs)}ms  " +
                            "后处理 ${"%.1f".format(r.postMs)}ms",
                    color = Color.Cyan,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * 把检测框画在预览上面。
 * 坐标换算：预览用 FIT_CENTER，所以按"等比缩放 + 居中"算出偏移量，和预览完全重合。
 */
@Composable
fun DetectionOverlay(result: DetectResult?, frameW: Int, frameH: Int) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val r = result ?: return@Canvas
        if (frameW <= 0 || frameH <= 0) return@Canvas

        val scale = min(size.width / frameW, size.height / frameH)
        val dx = (size.width - frameW * scale) / 2f
        val dy = (size.height - frameH * scale) / 2f

        r.detections.forEach { d ->
            drawRect(
                color = Color(0xFF00E676),
                topLeft = Offset(dx + d.x1 * scale, dy + d.y1 * scale),
                size = Size(d.width * scale, d.height * scale),
                style = Stroke(width = 4f)
            )
            var i = 0
            while (i + 1 < d.landmarks.size) {
                drawCircle(
                    color = Color(0xFFFF5252),
                    radius = 5f,
                    center = Offset(dx + d.landmarks[i] * scale, dy + d.landmarks[i + 1] * scale)
                )
                i += 2
            }
        }
    }
}

/** ImageProxy（生的 YUV 帧）→ 摆正 + 镜像后的 Bitmap */
fun ImageProxy.toUprightBitmap(mirror: Boolean): Bitmap {
    // toBitmap() 是 CameraX 1.3 自带的：帮我们把 YUV 转成图片
    // 如果这里报红：光标放上去按 Alt+Enter 自动导入
    val src = toBitmap()

    val matrix = Matrix()
    val rot = imageInfo.rotationDegrees
    if (rot != 0) matrix.postRotate(rot.toFloat())
    if (mirror) matrix.postScale(-1f, 1f)

    return if (matrix.isIdentity) src
    else Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}
