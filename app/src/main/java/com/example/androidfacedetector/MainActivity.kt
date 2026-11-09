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
import com.example.androidfacedetector.face.AntiSpoofDetector
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
 * 一帧的完整处理结果：检测 -> 活体 -> 识别。
 *
 * 三段流水线的语义：
 *  - spoof.isReal == false：判为翻拍（照片/屏幕），出于安全考虑直接不出识别结果
 *  - feature == null：活体通过但画面里没有脸
 *  - name == null && feature != null：活体通过、有脸，但库里没有匹配（或库为空）
 */
class FrameResult(
    val detect: DetectResult,
    val spoof: AntiSpoofDetector.Result,
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
    var spoofer by remember { mutableStateOf<AntiSpoofDetector?>(null) }
    var frameResult by remember { mutableStateOf<FrameResult?>(null) }
    var frameW by remember { mutableIntStateOf(0) }
    var frameH by remember { mutableIntStateOf(0) }
    var fps by remember { mutableIntStateOf(0) }

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

    // 三个模型一起加载，都在 IO 线程，别卡界面
    LaunchedEffect(Unit) {
        statusText = "正在加载模型（检测/活体/识别）..."
        val loaded = withContext(Dispatchers.IO) {
            try {
                Triple(
                    ScrfdDetector(context),
                    AntiSpoofDetector(context),
                    FaceEmbedder(context)
                )
            } catch (t: Throwable) {
                Log.e("CameraDebug", "模型加载失败", t)
                null
            }
        }
        if (loaded != null) {
            detector = loaded.first
            spoofer = loaded.second
            embedder = loaded.third
            statusText = "三个模型已就绪"
            hint = "请输入姓名后点注册"
        } else {
            statusText = "模型加载失败：检查 assets/ 下的 onnx 文件"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            detector?.close()
            spoofer?.close()
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
                                val s = spoofer
                                val e = embedder
                                if (d != null && s != null && e != null &&
                                    busy.compareAndSet(false, true)
                                ) {
                                    try {
                                        val fr = recognize(bitmap, d, s, e, database)
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
                // 活体结论
                val liveness = if (fr.spoof.isReal)
                    "活体: 通过  ${"%.2f".format(fr.spoof.score)}"
                else
                    "活体: 未通过(疑似翻拍)  ${"%.2f".format(fr.spoof.score)}"
                Text(
                    text = liveness,
                    color = if (fr.spoof.isReal) Color(0xFF00E676) else Color(0xFFEF5350),
                    fontSize = 14.sp
                )
                // 识别结论：活体没过就不出识别结果
                if (fr.spoof.isReal) {
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
                }
                Text(
                    text = "检测 ${"%.1f".format(r.preMs)}/${"%.1f".format(r.inferMs)}/${"%.1f".format(r.postMs)}  " +
                            "活体 ${"%.1f".format(fr.spoof.inferMs)}  " +
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
                            hint = "画面里还没有通过活体检测的人脸"
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
 * 检测 -> 活体 -> 识别 一条龙。跑在摄像头分析线程上。
 *
 * 活体没通过时直接短路返回，不做对齐和特征提取——
 * 一来省算力，二来安全语义清晰：翻拍的脸不允许进入注册/识别环节。
 */
private fun recognize(
    bitmap: Bitmap,
    detector: ScrfdDetector,
    spoof: AntiSpoofDetector,
    embedder: FaceEmbedder,
    database: FaceDatabase
): FrameResult {
    val r = detector.detect(bitmap)
    val face = r.largest()
        ?: return FrameResult(
            r, AntiSpoofDetector.Result(true, 0f, 0f, 0f),
            null, 0f, null, 0f, 0f
        )

    val sp = spoof.detect(bitmap, face.x1, face.y1, face.x2, face.y2)
    if (!sp.isReal) {
        return FrameResult(r, sp, null, 0f, null, 0f, 0f)
    }

    val t0 = System.nanoTime()
    val aligned = FaceAligner.align(bitmap, face.landmarks)
    val t1 = System.nanoTime()
    val feature = embedder.embed(aligned)
    val t2 = System.nanoTime()

    val m = database.match(feature)
    val name = if (m != null && m.similarity >= FaceEmbedder.MATCH_THRESHOLD) m.name else null

    return FrameResult(
        detect = r,
        spoof = sp,
        name = name,
        similarity = m?.similarity ?: 0f,
        feature = feature,
        alignMs = (t1 - t0) / 1_000_000f,
        embedMs = (t2 - t1) / 1_000_000f
    )
}

/**
 * 把检测框、关键点、活体与识别结论画在预览上面。
 * 颜色语义：活体通过且识别到 = 绿；活体通过未识别 = 橙；活体未通过 = 红。
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
            val isLargest = d === largest
            val boxColor = when {
                isLargest && !res.spoof.isReal -> Color(0xFFEF5350)   // 红：非活体
                isLargest && res.name != null -> Color(0xFF00E676)    // 绿：已识别
                else -> Color(0xFFFFA726)                             // 橙：未识别
            }

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

        if (largest != null) {
            val label = when {
                !res.spoof.isReal -> "疑似翻拍  ${"%.2f".format(res.spoof.score)}"
                res.name != null -> "${res.name}  ${"%.2f".format(res.similarity)}"
                res.feature == null -> ""
                else -> "未知  ${"%.2f".format(res.similarity)}"
            }
            if (label.isNotEmpty()) {
                textPaint.color = when {
                    !res.spoof.isReal -> 0xFFEF5350.toInt()
                    res.name != null -> 0xFF00E676.toInt()
                    else -> 0xFFFFA726.toInt()
                }
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
