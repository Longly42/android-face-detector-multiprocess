package com.example.androidfacedetector.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

/**
 * 活体检测（Anti-Spoofing），基于 MiniFASNetV2 / V1SE 双模型融合，
 * 权重来自 minivision-ai/Silent-Face-Anti-Spoofing（MIT 许可）。
 *
 * 它解决的问题是：拿一张照片/另一块屏幕对着摄像头，骗过识别系统。
 * 判断依据不是"脸长什么样"，而是图像的纹理特征——翻拍会出现摩尔纹、
 * 反光、色偏、锐度衰减，这些痕迹模型看得出来。
 *
 * ⚠️ 预处理与本项目其他两个模型完全不同，最容易抄错：
 *   1. 输入是 80x80 的"扩大裁剪块"，不是紧贴人脸的框；
 *      扩大倍数写在模型名里（2.7 / 4.0），以框中心向外扩，再缩到 80x80；
 *   2. 通道顺序是 BGR（官方用 cv2.imread），我们的 Bitmap 是 RGB，要手动翻转；
 *   3. 不做归一化！官方的 to_tensor 被"modify by zkx"改成不除 255，
 *      模型吃的是 0~255 的原始亮度值。我们实测：按 0~1 输入时官方样例全判错，
 *      恢复 0~255 后三张样例判定全部与官方一致。
 *
 * 输出 3 类 softmax（官方约定 argmax==1 为真脸），两个模型的结果相加后
 * 取 argmax，分数除以模型个数。
 */
class AntiSpoofDetector(
    context: Context,
    threads: Int = 2
) {

    /** 每个模型的"裁剪扩大倍数"——来自官方模型文件名，必须和权重配对使用 */
    private data class Spec(val asset: String, val scale: Float)

    data class Result(
        val isReal: Boolean,   // argmax==1
        val score: Float,      // 判定类别的平均概率（0~1）
        val realProb: Float,   // "真脸"这一类的平均概率，直观参考
        val inferMs: Float
    )

    private companion object {
        const val TAG = "AntiSpoof"
        const val SIZE = 80
        val SPECS = listOf(
            Spec("antispoof_minifasnetv2_80x80.onnx", 2.7f),
            Spec("antispoof_minifasnetv1se_80x80.onnx", 4.0f)
        )
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions: List<Pair<Spec, OrtSession>>

    private val pixels = IntArray(SIZE * SIZE)
    private val inputData = FloatArray(3 * SIZE * SIZE)
    private val plane = SIZE * SIZE
    private val tmp = FloatArray(3)

    init {
        sessions = SPECS.map { spec ->
            val bytes = context.assets.open(spec.asset).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val s = env.createSession(bytes, options)
            options.close()
            Log.i(TAG, "模型 ${spec.asset} 加载成功 (${bytes.size / 1024} KB) scale=${spec.scale}")
            spec to s
        }
    }

    /**
     * @param src  原图（分析帧）
     * @param face 检测器给出的最大人脸框（x1,y1,x2,y2，原图坐标）
     */
    fun detect(src: Bitmap, fx1: Float, fy1: Float, fx2: Float, fy2: Float): Result {
        val t0 = System.nanoTime()

        // 官方 bbox 是 [x, y, w, h]，w = right-left+1
        val bx = fx1.toInt().coerceIn(0, src.width - 2)
        val by = fy1.toInt().coerceIn(0, src.height - 2)
        val bw = (fx2 - fx1).toInt().coerceAtLeast(2)
        val bh = (fy2 - fy1).toInt().coerceAtLeast(2)

        val probs = FloatArray(3)
        for ((spec, session) in sessions) {
            val (lx, ly, rx, ry) = newBox(src.width, src.height, bx, by, bw, bh, spec.scale)
            val pw = rx - lx + 1
            val ph = ry - ly + 1
            val patch = Bitmap.createBitmap(src, lx, ly, pw, ph)

            // 注意不能用 Bitmap.createScaledBitmap：它是纯双线性，大幅缩小时会走样，
            // 和 PC 端 cv2/PIL 的结果差很多。这里手写 cv2 语义的 INTER_LINEAR
            // （中心对齐），与 .tmp_model/validate_antispoof.py 逐位一致。
            resizeToInput(patch, SIZE, SIZE)

            val tensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(inputData),
                longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
            )
            var output: OrtSession.Result? = null
            try {
                output = session.run(mapOf("input" to tensor))
                val logits = (output[0].value as Array<*>)[0] as FloatArray
                // 官方融合方式：prediction += softmax(每个模型) —— 必须累加，
                // 不能让第二个模型覆盖第一个的结果（这正是之前分数对不上的原因）
                softmax(logits, tmp)
                for (i in 0..2) probs[i] += tmp[i]
            } finally {
                output?.close()
                tensor.close()
                patch.recycle()
            }
        }

        val t1 = System.nanoTime()
        var label = 0
        for (i in 1..2) if (probs[i] > probs[label]) label = i
        val n = sessions.size
        return Result(
            isReal = label == 1,
            score = probs[label] / n,
            realProb = probs[1] / n,
            inferMs = (t1 - t0) / 1_000_000f
        )
    }

    /** 调试用：返回第一个模型裁剪块 B/G/R 三通道的均值，用于和 PC 端比对预处理是否一致 */
    fun debugCropStats(src: Bitmap, fx1: Float, fy1: Float, fx2: Float, fy2: Float): FloatArray {
        val bx = fx1.toInt().coerceIn(0, src.width - 2)
        val by = fy1.toInt().coerceIn(0, src.height - 2)
        val bw = (fx2 - fx1).toInt().coerceAtLeast(2)
        val bh = (fy2 - fy1).toInt().coerceAtLeast(2)
        val (lx, ly, rx, ry) = newBox(src.width, src.height, bx, by, bw, bh, SPECS[0].scale)
        val patch = Bitmap.createBitmap(src, lx, ly, rx - lx + 1, ry - ly + 1)
        resizeToInput(patch, SIZE, SIZE)
        patch.recycle()
        val out = FloatArray(3)
        for (c in 0..2) {
            var s = 0f
            for (i in 0 until plane) s += inputData[c * plane + i]
            out[c] = s / plane
        }
        return out
    }

    fun close() {
        sessions.forEach { it.second.close() }
    }

    /** 照抄 generate_patches.py::_get_new_box：以框中心向外扩 scale 倍，限制在图内 */
    private fun newBox(srcW: Int, srcH: Int, x: Int, y: Int, bw: Int, bh: Int, scaleIn: Float): IntArray {
        val scale = min((srcH - 1).toFloat() / bh, min((srcW - 1).toFloat() / bw, scaleIn))
        val nw = bw * scale
        val nh = bh * scale
        val cx = bw / 2f + x
        val cy = bh / 2f + y
        var lx = cx - nw / 2
        var ly = cy - nh / 2
        var rx = cx + nw / 2
        var ry = cy + nh / 2
        if (lx < 0) { rx -= lx; lx = 0f }
        if (ly < 0) { ry -= ly; ly = 0f }
        if (rx > srcW - 1) { lx -= rx - srcW + 1; rx = srcW - 1f }
        if (ry > srcH - 1) { ly -= ry - srcH + 1; ry = srcH - 1f }
        return intArrayOf(lx.toInt(), ly.toInt(), rx.toInt(), ry.toInt())
    }

    /**
     * cv2.resize INTER_LINEAR 语义的缩放（中心对齐、无抗锯齿），并顺手把
     * ARGB 转 BGR 三平面写进输入缓冲。公式：src = (dst + 0.5) * srcSize / dstSize - 0.5
     */
    private fun resizeToInput(src: Bitmap, outW: Int, outH: Int) {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val xr = w.toFloat() / outW
        val yr = h.toFloat() / outH
        val wMax = (w - 1).toFloat()
        val hMax = (h - 1).toFloat()

        var i = 0
        for (y in 0 until outH) {
            val sy = ((y + 0.5f) * yr - 0.5f).coerceIn(0f, hMax)
            val y0 = floor(sy).toInt()
            val y1 = if (y0 + 1 < h) y0 + 1 else y0
            val wy = sy - y0
            val row0 = y0 * w
            val row1 = y1 * w
            for (x in 0 until outW) {
                val sx = ((x + 0.5f) * xr - 0.5f).coerceIn(0f, wMax)
                val x0 = floor(sx).toInt()
                val x1 = if (x0 + 1 < w) x0 + 1 else x0
                val wx = sx - x0

                val p00 = px[row0 + x0]; val p10 = px[row0 + x1]
                val p01 = px[row1 + x0]; val p11 = px[row1 + x1]

                val b = blend((p00 and 0xFF), (p10 and 0xFF), (p01 and 0xFF), (p11 and 0xFF), wx, wy)
                val g = blend((p00 shr 8) and 0xFF, (p10 shr 8) and 0xFF, (p01 shr 8) and 0xFF, (p11 shr 8) and 0xFF, wx, wy)
                val r = blend((p00 shr 16) and 0xFF, (p10 shr 16) and 0xFF, (p01 shr 16) and 0xFF, (p11 shr 16) and 0xFF, wx, wy)

                // BGR 三个平面，0~255 原始亮度，不做归一化（与官方一致）
                inputData[i] = b
                inputData[plane + i] = g
                inputData[2 * plane + i] = r
                i++
            }
        }
    }

    private fun blend(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Float {
        val top = c00 + (c10 - c00) * fx
        val bot = c01 + (c11 - c01) * fx
        return top + (bot - top) * fy
    }

    private fun softmax(logits: FloatArray, out: FloatArray) {
        var mx = logits[0]
        for (v in logits) if (v > mx) mx = v
        var sum = 0f
        for (i in logits.indices) {
            out[i] = exp(logits[i] - mx)
            sum += out[i]
        }
        for (i in out.indices) out[i] /= sum
    }
}
