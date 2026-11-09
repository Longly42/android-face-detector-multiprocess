package com.example.androidfacedetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Trace
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.androidfacedetector.inference.EngineRouter
import com.example.androidfacedetector.inference.RunMode
import com.example.androidfacedetector.ipc.FaceClient
import com.example.androidfacedetector.ipc.FaceResultParcel
import com.example.androidfacedetector.perf.FrameEvent
import com.example.androidfacedetector.perf.LatencyStats
import com.example.androidfacedetector.perf.MetricsRecorder
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CameraScreen() }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val router = (context.applicationContext as FaceApp).router

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var statusText by remember { mutableStateOf("准备请求权限...") }
    var connText by remember { mutableStateOf("未连接") }
    var svcPid by remember { mutableIntStateOf(-1) }
    var frameResult by remember { mutableStateOf<FaceResultParcel?>(null) }
    var frameW by remember { mutableIntStateOf(0) }
    var frameH by remember { mutableIntStateOf(0) }
    var fps by remember { mutableIntStateOf(0) }
    var e2eAvg by remember { mutableFloatStateOf(0f) }
    var e2eP95 by remember { mutableFloatStateOf(0f) }

    var showDebug by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var registered by remember { mutableStateOf(listOf<String>()) }

    val frameCounter = remember { AtomicInteger(0) }
    val reqSeq = remember { AtomicLong(0) }
    val busy = remember { AtomicBoolean(false) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    // 采集/调用线程池：放大到 3 个线程，才能让多帧同时进入 Binder，
    // 从而真正触发推理进程的 Semaphore 限流与丢帧（单线程时永远只有 1 帧在途）
    val camThreadIdx = AtomicInteger(0)
    val cameraExecutor = remember {
        Executors.newFixedThreadPool(3) { r ->
            Thread(r, "cam-analysis-${camThreadIdx.getAndIncrement()}")
        }
    }
    val e2eStats = remember { LatencyStats() }
    // 最近一帧，注册时要用（分析线程写、UI 线程读）
    val lastFrame = remember { AtomicReference<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            fps = frameCounter.getAndSet(0)
            e2eAvg = e2eStats.avg()
            e2eP95 = e2eStats.percentile(0.95f)
        }
    }

    LaunchedEffect(Unit) { focusManager.clearFocus() }

    DisposableEffect(Unit) {
        router.listener = object : FaceClient.Listener {
            override fun onServiceConnected(pid: Int) {
                mainExecutor.execute {
                    svcPid = pid
                    connText = "已连接 :inference  pid=$pid"
                    registered = runCatching { router.names() }.getOrDefault(emptyList())
                }
            }

            override fun onServiceDisconnected(reason: String) {
                mainExecutor.execute {
                    svcPid = -1
                    connText = "断开：$reason"
                }
                // 自动重连：稍等再 bind，避免服务还没重启就绑失败
                cameraExecutor.execute {
                    Thread.sleep(800)
                    runCatching { router.setMode(RunMode.DUAL) }
                    Log.i("MainActivity", "尝试重连推理服务")
                }
            }
        }
        router.setMode(RunMode.DUAL)
        onDispose {
            router.listener = null
            cameraExecutor.shutdown()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasPermission = granted
            statusText = if (granted) "权限已允许" else "权限被拒绝！"
        }
    )

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
        else statusText = "相机就绪"
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
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
                                // 跳帧：在转换之前判断，连 YUV->Bitmap 的开销一起省掉
                                val seqNo = frameCounter.incrementAndGet()
                                val skip = router.frameSkip
                                if (skip > 1 && seqNo % skip != 0) return@setAnalyzer

                                val captureNs = System.nanoTime()
                                Trace.beginSection("frame.convert")
                                val bitmap = image.toUprightBitmap(mirror = true)
                                Trace.endSection()
                                val convertDoneNs = System.nanoTime()

                                // 不在这里限制在途帧数：并发额度统一交给推理进程的 Semaphore，
                                // 这样才能观察到"无限制 vs 有界"的差异
                                run {
                                    val reqId = reqSeq.incrementAndGet()
                                    Trace.beginSection("ipc.analyze")
                                    val r = router.analyze(bitmap, reqId, captureNs)
                                    Trace.endSection()
                                    val ipcReturnNs = System.nanoTime()
                                    lastFrame.set(bitmap)

                                    mainExecutor.execute {
                                        val shownNs = System.nanoTime()
                                        frameResult = r
                                        frameW = bitmap.width
                                        frameH = bitmap.height
                                        if (r.status == FaceResultParcel.STATUS_OK ||
                                            r.status == FaceResultParcel.STATUS_NO_FACE
                                        ) {
                                            val e2e = (shownNs - captureNs) / 1_000_000f
                                            e2eStats.add(e2e)
                                            MetricsRecorder.record(
                                                FrameEvent(
                                                    requestId = reqId,
                                                    seq = reqId,
                                                    captureNs = captureNs,
                                                    convertDoneNs = convertDoneNs,
                                                    ipcCallStartNs = convertDoneNs,
                                                    ipcReturnNs = ipcReturnNs,
                                                    uiShownNs = shownNs,
                                                    convertMs = (convertDoneNs - captureNs) / 1_000_000f,
                                                    ipcRoundMs = (ipcReturnNs - convertDoneNs) / 1_000_000f,
                                                    serviceWorkMs = r.serviceWorkMs,
                                                    queueWaitMs = r.queueWaitMs,
                                                    e2eMs = e2e,
                                                    detectPreMs = r.detectPreMs,
                                                    detectInferMs = r.detectInferMs,
                                                    detectPostMs = r.detectPostMs,
                                                    spoofMs = r.spoofMs,
                                                    alignMs = r.alignMs,
                                                    embedMs = r.embedMs,
                                                    status = r.status,
                                                    faceCount = r.faceCount,
                                                    liveness = r.isReal,
                                                    mode = router.mode.name
                                                )
                                            )
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.e("MainActivity", "帧处理失败", t)
                            } finally {
                                image.close()
                            }
                        }

                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                preview, analysis
                            )
                            statusText = "相机运行中"
                        } catch (e: Exception) {
                            Log.e("MainActivity", "相机绑定异常", e)
                            statusText = "相机启动失败: ${e.message}"
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        FaceOverlay(frameResult, frameW, frameH)

        Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text(
                "PID ${android.os.Process.myPid()} · ${router.mode.label}",
                color = Color.Green, fontSize = 12.sp
            )
            Text(
                connText,
                color = if (svcPid > 0) Color(0xFF00E676) else Color(0xFFEF5350),
                fontSize = 12.sp
            )
            Text(
                "FPS $fps   E2E avg ${"%.0f".format(e2eAvg)}ms  P95 ${"%.0f".format(e2eP95)}ms",
                color = Color.Yellow, fontSize = 12.sp
            )

            frameResult?.let { r ->
                Text("人脸 ${r.faceCount}   请求 #${r.requestId}", color = Color.White, fontSize = 12.sp)
                val liveness = when {
                    r.status == FaceResultParcel.STATUS_DROPPED -> "丢帧（背压）"
                    r.status == FaceResultParcel.STATUS_TIMEOUT -> "推理超时"
                    r.status == FaceResultParcel.STATUS_ERROR -> r.statusMessage.ifEmpty { "IPC 异常" }
                    !r.hasFace -> "画面中无人脸"
                    r.isReal -> "活体 通过  ${"%.2f".format(r.livenessScore)}"
                    else -> "活体 未通过（疑似翻拍）${"%.2f".format(r.livenessScore)}"
                }
                Text(
                    liveness,
                    color = when {
                        !r.isReal -> Color(0xFFEF5350)
                        r.status != FaceResultParcel.STATUS_OK -> Color(0xFFFFAB91)
                        else -> Color(0xFF00E676)
                    },
                    fontSize = 13.sp
                )
                if (r.status == FaceResultParcel.STATUS_OK && r.isReal && r.hasFace) {
                    val who = when {
                        r.name != null -> "识别 ${r.name}  ${"%.2f".format(r.similarity)}"
                        registered.isEmpty() -> "库为空，未注册"
                        else -> "未知身份  ${"%.2f".format(r.similarity)}"
                    }
                    Text(
                        who,
                        color = if (r.name != null) Color(0xFF00E676) else Color(0xFFFFD54F),
                        fontSize = 14.sp
                    )
                }
                if (r.status == FaceResultParcel.STATUS_OK) {
                    Text(
                        "检测 ${"%.1f".format(r.detectInferMs)}  活体 ${"%.1f".format(r.spoofMs)}  " +
                                "对齐 ${"%.1f".format(r.alignMs)}  特征 ${"%.1f".format(r.embedMs)}  服务端 ${"%.1f".format(r.serviceWorkMs)}",
                        color = Color.Cyan, fontSize = 10.sp
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { showDebug = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
        ) { Text("调试", fontSize = 12.sp) }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("姓名", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.width(130.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Color.Cyan,
                        unfocusedLabelColor = Color.LightGray,
                        cursorColor = Color.White,
                        focusedBorderColor = Color.Cyan,
                        unfocusedBorderColor = Color.Gray
                    )
                )
                Button(onClick = {
                    val bmp = lastFrame.get()
                    if (bmp == null) {
                        hint = "还没有可用帧"
                    } else if (nameInput.isBlank()) {
                        hint = "请先输入姓名"
                    } else {
                        hint = "注册中…"
                        focusManager.clearFocus()
                        val wanted = nameInput.trim()
                        cameraExecutor.execute {
                            val id = reqSeq.incrementAndGet()
                            Trace.beginSection("ipc.register")
                            val r = router.register(bmp, wanted, id)
                            Trace.endSection()
                            mainExecutor.execute {
                                hint = if (r.status == FaceResultParcel.STATUS_OK) {
                                    registered = router.names()
                                    "已注册 $wanted，共 ${registered.size} 人"
                                } else {
                                    "注册失败：${r.statusMessage.ifEmpty { "status=${r.status}" }}"
                                }
                            }
                        }
                    }
                }) { Text("注册", fontSize = 12.sp) }

                OutlinedButton(onClick = {
                    cameraExecutor.execute {
                        router.clearDatabase()
                        mainExecutor.execute {
                            registered = emptyList()
                            hint = "特征库已清空"
                        }
                    }
                }) { Text("清空", fontSize = 12.sp) }
            }
            Text(
                if (registered.isEmpty()) "已注册：无" else "已注册：${registered.joinToString("、")}",
                color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
            )
            if (hint.isNotEmpty()) Text(hint, color = Color(0xFFFFD54F), fontSize = 11.sp)
        }

        // 调试页做成覆盖层而不是新 Activity：否则 MainActivity 进入 stop，
        // CameraX 停止出帧，调试页上的队列/信号量数据会立刻变成 0
        if (showDebug) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101418))) {
                DebugScreen(onClose = { showDebug = false })
            }
        }
    }
}

@Composable
fun FaceOverlay(r: FaceResultParcel?, frameW: Int, frameH: Int) {
    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            textSize = 38f
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val res = r ?: return@Canvas
        if (frameW <= 0 || frameH <= 0 || !res.hasFace) return@Canvas

        val scale = min(size.width / frameW, size.height / frameH)
        val dx = (size.width - frameW * scale) / 2f
        val dy = (size.height - frameH * scale) / 2f

        val boxColor = when {
            !res.isReal -> Color(0xFFEF5350)
            res.name != null -> Color(0xFF00E676)
            else -> Color(0xFFFFA726)
        }
        drawRect(
            color = boxColor,
            topLeft = Offset(dx + res.x1 * scale, dy + res.y1 * scale),
            size = Size(res.width * scale, res.height * scale),
            style = Stroke(width = 4f)
        )
        var i = 0
        while (i + 1 < res.landmarks.size) {
            drawCircle(
                color = Color(0xFFFF5252),
                radius = 5f,
                center = Offset(dx + res.landmarks[i] * scale, dy + res.landmarks[i + 1] * scale)
            )
            i += 2
        }

        val label = when {
            res.status != FaceResultParcel.STATUS_OK -> ""
            !res.isReal -> "疑似翻拍 ${"%.2f".format(res.livenessScore)}"
            res.name != null -> "${res.name} ${"%.2f".format(res.similarity)}"
            else -> "未知 ${"%.2f".format(res.similarity)}"
        }
        if (label.isNotEmpty()) {
            textPaint.color = when {
                !res.isReal -> 0xFFEF5350.toInt()
                res.name != null -> 0xFF00E676.toInt()
                else -> 0xFFFFA726.toInt()
            }
            drawContext.canvas.nativeCanvas.drawText(
                label,
                dx + res.x1 * scale,
                (dy + res.y1 * scale - 12f).coerceAtLeast(40f),
                textPaint
            )
        }
    }
}

/** ImageProxy（YUV 帧）→ 摆正 + 镜像后的 Bitmap */
fun ImageProxy.toUprightBitmap(mirror: Boolean): Bitmap {
    val src = toBitmap()
    val matrix = Matrix()
    val rot = imageInfo.rotationDegrees
    if (rot != 0) matrix.postRotate(rot.toFloat())
    if (mirror) matrix.postScale(-1f, 1f)
    return if (matrix.isIdentity) src
    else Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}
