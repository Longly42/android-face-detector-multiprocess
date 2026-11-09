package com.example.androidfacedetector.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * SCRFD-500M-KPS 人脸检测器。
 *
 * 工作流程只有四步：
 *   1. 预处理：把任意大小的图缩放并贴到 640x640 的黑底上，再归一化成 float 数组
 *   2. 推理：交给 ONNX Runtime 跑模型
 *   3. 解码：模型吐出的是"相对锚点的距离"，要换算成人脸框和关键点坐标
 *   4. NMS：同一个人脸会出好几个框，只留最好的那个
 */
class ScrfdDetector(
    context: Context,
    modelAsset: String = DEFAULT_MODEL,
    threads: Int = 2,
    /** 模型输入边长：640（默认）或 320（第 7 周分辨率对照实验用） */
    private val inputSize: Int = 640
) {

    companion object {
        const val DEFAULT_MODEL = "scrfd_500m_bnkps_shape640x640.onnx"
        private const val TAG = "Scrfd"
        private const val NUM_ANCHORS = 2   // SCRFD 每个格子放 2 个锚点
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    // ---- 复用缓冲，避免每帧都 new 一堆对象（不然 GC 会拖慢帧率）----
    private val inputBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputBitmap)
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(inputSize * inputSize)
    private val inputData = FloatArray(3 * inputSize * inputSize)
    private val plane = inputSize * inputSize

    init {
        val bytes = context.assets.open(modelAsset).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)                    // 单进程内用几个线程算
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(bytes, options)
        options.close()
        inputName = session.inputNames.first()

        // 把模型结构打到 Logcat —— 第一次接模型时，这几行日志是你最重要的"体检报告"
        Log.i(TAG, "模型 $modelAsset 加载成功 (${bytes.size / 1024} KB)")
        session.inputInfo.forEach { (name, node) ->
            Log.i(TAG, "  输入  $name -> ${describe(node.info)}")
        }
        session.outputInfo.forEach { (name, node) ->
            Log.i(TAG, "  输出  $name -> ${describe(node.info)}")
        }
    }

    private fun describe(info: Any?): String = when (info) {
        is TensorInfo -> "${info.type} ${info.shape.contentToString()}"
        else -> info?.toString() ?: "未知"
    }

    /** 释放模型。Activity 销毁时调用。 */
    fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭模型异常", e)
        }
    }

    /**
     * 对一张"摆正"的 Bitmap 做人脸检测。
     * @param src 原图（不要传 640x640 的，传你手里那张就行，内部会缩放）
     * @return 检测结果 + 三段耗时
     */
    fun detect(
        src: Bitmap,
        scoreThreshold: Float = 0.5f,
        nmsThreshold: Float = 0.4f
    ): DetectResult {
        val t0 = System.nanoTime()
        val scale = preprocess(src)
        val t1 = System.nanoTime()

        // 模型输入：1 张图、3 个通道、640x640，float32
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputData),
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )
        var output: OrtSession.Result? = null
        try {
            output = session.run(mapOf(inputName to tensor))
            val t2 = System.nanoTime()

            val heads = collectOutputs(output)
            val detections = decode(heads, scale, scoreThreshold, nmsThreshold)
            val t3 = System.nanoTime()

            return DetectResult(
                detections = detections,
                preMs = (t1 - t0) / 1_000_000f,
                inferMs = (t2 - t1) / 1_000_000f,
                postMs = (t3 - t2) / 1_000_000f
            )
        } finally {
            output?.close()
            tensor.close()
        }
    }

    // ======================= 第 1 步：预处理 =======================

    /**
     * 等比例缩放 + 黑底填充（letterbox），并归一化写入 inputData。
     * 返回缩放比例，后面把坐标换算回原图要用。
     */
    private fun preprocess(src: Bitmap): Float {
        val imRatio = src.height.toFloat() / src.width
        val newW: Int
        val newH: Int
        if (imRatio > 1f) {                 // 竖图：高度顶满
            newH = inputSize
            newW = (newH / imRatio).toInt().coerceAtLeast(1)
        } else {                            // 横图：宽度顶满
            newW = inputSize
            newH = (newW * imRatio).toInt().coerceAtLeast(1)
        }
        val scale = newH.toFloat() / src.height

        // 贴到左上角，其余补黑（和 InsightFace 官方做法一致，坐标换算最简单）
        inputCanvas.drawColor(Color.BLACK)
        inputCanvas.drawBitmap(src, null, Rect(0, 0, newW, newH), drawPaint)

        inputBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // 归一化：(像素 - 127.5) / 128，并拆成 R/G/B 三个平面（模型要 NCHW 布局）
        var i = 0
        while (i < plane) {
            val p = pixels[i]
            inputData[i] = (((p shr 16) and 0xFF) - 127.5f) / 128f
            inputData[i + plane] = (((p shr 8) and 0xFF) - 127.5f) / 128f
            inputData[i + 2 * plane] = ((p and 0xFF) - 127.5f) / 128f
            i++
        }
        return scale
    }

    // ======================= 第 2 步：收集模型输出 =======================

    /** 一组输出头：n = 锚点个数，c = 每个锚点的数值个数（1=分数, 4=框, 10=关键点） */
    private class Head(val n: Int, val c: Int, val data: FloatArray)

    /**
     * 不靠输出名字猜顺序，而是按"每个锚点几个数"来认：
     * c=1 是置信度，c=4 是框的偏移，c=10 是 5 个关键点。
     * 这样就算别人导出的模型名字不一样，也能跑。
     */
    private fun collectOutputs(output: OrtSession.Result): List<Head> {
        val heads = ArrayList<Head>(9)
        for (entry in output) {
            val t = entry.value as? OnnxTensor ?: continue
            val shape = t.info.shape
            var total = 1
            for (d in shape) total *= d.toInt()
            if (total <= 0) continue

            val c = when {
                shape.size >= 3 -> shape[shape.size - 1].toInt()   // (1,12800,4)
                shape.size == 2 -> if (shape[0] == 1L) 1 else shape[1].toInt()  // (1,12800) 或 (12800,4)
                else -> 1
            }
            if (c <= 0 || total % c != 0) continue

            val fb = t.floatBuffer
            val arr = FloatArray(fb.remaining())
            fb.get(arr)
            heads.add(Head(total / c, c, arr))
        }
        return heads
    }

    // ======================= 第 3 步：解码 =======================

    /**
     * 模型输出的是"从锚点中心到框四条边的距离"，这里换算成绝对坐标。
     * 输出一共 9 个张量 = 3 个尺度(stride 8/16/32) × 3 种(分数/框/关键点)。
     */
    private fun decode(
        heads: List<Head>,
        scale: Float,
        scoreThreshold: Float,
        nmsThreshold: Float
    ): List<Detection> {
        // 按锚点数分组：12800 / 3200 / 800 三组，每组各有 c=1/4/10 三个张量
        val groups = LinkedHashMap<Int, HashMap<Int, FloatArray>>()
        for (h in heads) {
            groups.getOrPut(h.n) { HashMap() }[h.c] = h.data
        }

        val candidates = ArrayList<Detection>()

        for ((n, byC) in groups) {
            val scores = byC[1] ?: continue
            val bbox = byC[4] ?: continue
            val kps = byC[10]

            val cells = n / NUM_ANCHORS
            val feature = sqrt(cells.toFloat()).roundToInt()
            if (feature * feature != cells) continue      // 不是方块特征图，跳过
            val stride = inputSize.toFloat() / feature   // 80格 -> 8, 40格 -> 16, 20格 -> 32

            for (idx in 0 until n) {
                val score = scores[idx]
                if (score < scoreThreshold) continue

                val cell = idx / NUM_ANCHORS
                val cx = (cell % feature) * stride
                val cy = (cell / feature) * stride

                val b = idx * 4
                val x1 = (cx - bbox[b] * stride) / scale
                val y1 = (cy - bbox[b + 1] * stride) / scale
                val x2 = (cx + bbox[b + 2] * stride) / scale
                val y2 = (cy + bbox[b + 3] * stride) / scale

                val lm = FloatArray(10)
                if (kps != null) {
                    val k = idx * 10
                    for (j in 0 until 5) {
                        lm[j * 2] = (cx + kps[k + j * 2] * stride) / scale
                        lm[j * 2 + 1] = (cy + kps[k + j * 2 + 1] * stride) / scale
                    }
                }
                candidates.add(Detection(score, x1, y1, x2, y2, lm))
            }
        }

        return nms(candidates, nmsThreshold)
    }

    // ======================= 第 4 步：NMS =======================

    /** 非极大值抑制：框重叠太多就只留分数高的那个 */
    private fun nms(candidates: MutableList<Detection>, threshold: Float): List<Detection> {
        if (candidates.isEmpty()) return emptyList()
        candidates.sortByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (d in candidates) {
            var overlap = false
            for (k in kept) {
                if (iou(d, k) > threshold) {
                    overlap = true
                    break
                }
            }
            if (!overlap) kept.add(d)
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)
        val w = x2 - x1
        val h = y2 - y1
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }
}
