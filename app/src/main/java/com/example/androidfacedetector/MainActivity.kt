package com.example.androidfacedetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
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
import com.example.androidfacedetector.detector.DetectResult
import com.example.androidfacedetector.detector.ScrfdDetector
import com.example.androidfacedetector.face.FaceAligner
import com.example.androidfacedetector.face.FaceDatabase
import com.example.androidfacedetector.face.FaceEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * 一帧的完整处理结果：检测 + 识别。
 * @param name       识别到的名字；null 表示"库为空"或"相似度没过阈值"
 * @param similarity 与库中最相似者的余弦相似度
 * @param feature    最大那张脸的特征，注册时直接用，不用再算一遍
 */
class FrameResult(
    val detect: DetectResult,
    val name: String?,
    val similarity: Float,
    val feature: FloatArray?,
    val alignMs: Float,
    val embedMs: Float
)

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
    val focusManager = LocalFocusManager.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var statusText by remember { mutableStateOf("准备请求权限...") }
    var detector by remember { mutableStateOf<ScrfdDetector?>(null) }
    var embedder by remember { mutableStateOf<FaceEmbedder?>(null) }
    var frameResult by remember { mutableStateOf<FrameResult?>(null) }
    var frameW by remember { mutableIntStateOf(0) }
    var frameH by remember { mutableIntStateOf(0) }
    var fps by remember { mutableIntStateOf(0) }

    // 注册相关
    var nameInput by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var registered by remember { mutableStateOf(listOf<String>()) }

    val frameCounter = remember { AtomicInteger(0) }
    val busy = remember { AtomicBoolean(false) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember {
        Executors.newSingleThreadExecutor { r -> Thread(r, "cam-analysis") }
    }

    val database = remember {
        FaceDatabase(context).also { registered = it.names() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            fps = frameCounter.getAndSet(0)
        }
    }

    // 进界面时别让输入框抢焦点，不然键盘会弹出来挡住底部注册区
    LaunchedEffect(Unit) { focusManager.clearFocus() }

    // 两个模型一起加载，都在 IO 线程，别卡界面
    LaunchedEffect(Unit) {
        statusText = "正在加载模型..."
        val t0 = System.nanoTime()
        val pair = withContext(Dispatchers.IO) {
            try {
                ScrfdDetector(context) to FaceEmbedder(context)
            } catch (t: Throwable) {
                Log.e("CameraDebug", "模型加载失败", t)
                null
            }
        }
        if (pair != null) {
            detector = pair.first
            embedder = pair.second
            val ms = (System.nanoTime() - t0) / 1_000_000
            statusText = "检测+识别模型已就绪 (${ms}ms)"
            hint = "请输入姓名后点注册"
        } else {
            statusText = "模型加载失败：检查 assets/ 下的 onnx 文件"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            detector?.close()
            embedder?.close()
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
        if (!hasPermission) {
            Log.d("CameraDebug", "发起权限请求")
            launcher.launch(Manifest.permission.CAMERA)
        }
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
                                val bitmap = image.toUprightBitmap(mirror = true)
                                frameCounter.incrementAndGet()

                                val d = detector
                                val e = embedder
                                if (d != null && e != null && busy.compareAndSet(false, true)) {
                                    try {
                                        val fr = recognize(bitmap, d, e, database)
                                        mainExecutor.execute {
                                            frameResult = fr
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

        FaceOverlay(frameResult, frameW, frameH)

        // 左上角信息面板
        Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text(text = statusText, color = Color.Green, fontSize = 13.sp)
            Text(text = "FPS: $fps", color = Color.Yellow, fontSize = 13.sp)
            frameResult?.let { fr ->
                val r = fr.detect
                Text(text = "人脸: ${r.detections.size}", color = Color.White, fontSize = 13.sp)
                r.largest()?.let { d ->
                    Text(
                        text = "置信度 ${"%.2f".format(d.score)}",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
                // 识别结论
                val who = when {
                    fr.feature == null -> "—"
                    database.size == 0 -> "库为空，未注册"
                    fr.name != null -> "识别: ${fr.name}  ${"%.2f".format(fr.similarity)}"
                    else -> "未知身份  ${"%.2f".format(fr.similarity)}"
                }
                Text(
                    text = who,
                    color = if (fr.name != null) Color(0xFF00E676) else Color(0xFFFFD54F),
                    fontSize = 15.sp
                )
                Text(
                    text = "预处理 ${"%.1f".format(r.preMs)}  推理 ${"%.1f".format(r.inferMs)}  " +
                            "后处理 ${"%.1f".format(r.postMs)}  |  " +
                            "对齐 ${"%.1f".format(fr.alignMs)}  特征 ${"%.1f".format(fr.embedMs)}",
                    color = Color.Cyan,
                    fontSize = 11.sp
                )
            }
        }

        // 底部：注册区
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
                    modifier = Modifier.width(140.dp),
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
                Button(
                    onClick = {
                        val feat = frameResult?.feature
                        if (feat == null) {
                            hint = "画面里还没有人脸，先把脸对准摄像头"
                        } else if (nameInput.isBlank()) {
                            hint = "请先输入姓名"
                        } else {
                            database.register(nameInput, feat)
                            registered = database.names()
                            hint = "已注册 ${nameInput.trim()}，共 ${database.size} 人"
                            focusManager.clearFocus()
                        }
                    }
                ) { Text("注册当前人脸", fontSize = 12.sp) }

                OutlinedButton(
                    onClick = {
                        database.clear()
                        registered = emptyList()
                        hint = "特征库已清空"
                    }
                ) { Text("清空", fontSize = 12.sp) }
            }
            Text(
                text = if (registered.isEmpty()) "已注册：无" else "已注册：${registered.joinToString("、")}",
                color = Color.LightGray,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (hint.isNotEmpty()) {
                Text(text = hint, color = Color(0xFFFFD54F), fontSize = 12.sp)
            }
        }
    }
}

/**
 * 检测 + 识别一条龙。跑在摄像头分析线程上。
 */
private fun recognize(
    bitmap: Bitmap,
    detector: ScrfdDetector,
    embedder: FaceEmbedder,
    database: FaceDatabase
): FrameResult {
    val r = detector.detect(bitmap)
    val face = r.largest() ?: return FrameResult(r, null, 0f, null, 0f, 0f)

    val t0 = System.nanoTime()
    val aligned = FaceAligner.align(bitmap, face.landmarks)
    val t1 = System.nanoTime()
    val feature = embedder.embed(aligned)
    val t2 = System.nanoTime()

    val m = database.match(feature)
    val name = if (m != null && m.similarity >= FaceEmbedder.MATCH_THRESHOLD) m.name else null

    return FrameResult(
        detect = r,
        name = name,
        similarity = m?.similarity ?: 0f,
        feature = feature,
        alignMs = (t1 - t0) / 1_000_000f,
        embedMs = (t2 - t1) / 1_000_000f
    )
}

/**
 * 把检测框、关键点、识别名字画在预览上面。
 * 坐标换算：预览用 FIT_CENTER，所以按"等比缩放 + 居中"算偏移，和预览完全重合。
 */
@Composable
fun FaceOverlay(fr: FrameResult?, frameW: Int, frameH: Int) {
    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            textSize = 38f
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val res = fr ?: return@Canvas
        if (frameW <= 0 || frameH <= 0) return@Canvas

        val scale = min(size.width / frameW, size.height / frameH)
        val dx = (size.width - frameW * scale) / 2f
        val dy = (size.height - frameH * scale) / 2f
        val largest = res.detect.largest()

        res.detect.detections.forEach { d ->
            val isMatched = (d === largest) && res.name != null
            val boxColor =
                if (isMatched) Color(0xFF00E676) else Color(0xFFFFA726)

            drawRect(
                color = boxColor,
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

        // 名字标签画在最大那张脸的框上方
        if (largest != null) {
            val label = when {
                res.name != null -> "${res.name}  ${"%.2f".format(res.similarity)}"
                res.feature == null -> ""
                else -> "未知  ${"%.2f".format(res.similarity)}"
            }
            if (label.isNotEmpty()) {
                textPaint.color = if (res.name != null) 0xFF00E676.toInt() else 0xFFFFA726.toInt()
                val x = dx + largest.x1 * scale
                val y = (dy + largest.y1 * scale - 12f).coerceAtLeast(40f)
                drawContext.canvas.nativeCanvas.drawText(label, x, y, textPaint)
            }
        }
    }
}

/** ImageProxy（生的 YUV 帧）→ 摆正 + 镜像后的 Bitmap */
fun ImageProxy.toUprightBitmap(mirror: Boolean): Bitmap {
    val src = toBitmap()

    val matrix = Matrix()
    val rot = imageInfo.rotationDegrees
    if (rot != 0) matrix.postRotate(rot.toFloat())
    if (mirror) matrix.postScale(-1f, 1f)

    return if (matrix.isIdentity) src
    else Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}
