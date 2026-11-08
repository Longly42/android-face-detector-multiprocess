package com.example.androidfacedetector.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * 人脸特征提取器（MobileFaceNet / w600k_mbf）。
 *
 * 做的事很简单：吃一张 112x112 的摆正人脸，吐一个 512 维的 float 数组。
 * 这个数组就是这张脸的"数字指纹"——同一个人的两张照片，向量方向几乎一致；
 * 不同人之间差得很远。判断是不是同一个人，只要算两个向量的夹角余弦。
 */
class FaceEmbedder(
    context: Context,
    modelAsset: String = DEFAULT_MODEL,
    threads: Int = 2
) {

    companion object {
        const val DEFAULT_MODEL = "w600k_mbf_112x112.onnx"
        const val DIM = 512
        const val SIZE = 112

        /** 判定为同一个人的相似度门槛。（实测同人 0.86~0.98，不同人 0.0~0.22，取中间偏严） */
        const val MATCH_THRESHOLD = 0.5f

        private const val TAG = "FaceEmbedder"

        /** 求两个已归一化向量的余弦相似度（等于点积） */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    // 复用缓冲，避免每帧分配
    private val pixels = IntArray(SIZE * SIZE)
    private val inputData = FloatArray(3 * SIZE * SIZE)
    private val plane = SIZE * SIZE

    init {
        val bytes = context.assets.open(modelAsset).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(bytes, options)
        options.close()
        inputName = session.inputNames.first()

        Log.i(TAG, "模型 $modelAsset 加载成功 (${bytes.size / 1024} KB)")
        session.inputInfo.forEach { (name, node) -> Log.i(TAG, "  输入  $name -> ${describe(node.info)}") }
        session.outputInfo.forEach { (name, node) -> Log.i(TAG, "  输出  $name -> ${describe(node.info)}") }
    }

    /**
     * @param face 已对齐的 112x112 人脸
     * @return 已做 L2 归一化的 512 维特征
     */
    fun embed(face: Bitmap): FloatArray {
        val w = face.width
        val h = face.height
        face.getPixels(pixels, 0, w, 0, 0, w, h)

        // Bitmap 是 ARGB，模型要 RGB 三个平面分开存（NCHW）
        // 归一化用 (x - 127.5) / 127.5，把 0~255 映射到 -1~1 —— ArcFace 系列的标准做法
        for (i in 0 until plane) {
            val p = pixels[i]
            inputData[i] = (((p shr 16) and 0xFF) - 127.5f) / 127.5f          // R
            inputData[plane + i] = (((p shr 8) and 0xFF) - 127.5f) / 127.5f   // G
            inputData[2 * plane + i] = ((p and 0xFF) - 127.5f) / 127.5f       // B
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputData),
            longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
        )
        var output: OrtSession.Result? = null
        try {
            output = session.run(mapOf(inputName to tensor))
            val raw = output[0].value
            val vec = (raw as Array<*>)[0] as FloatArray
            return l2Normalize(vec)
        } finally {
            output?.close()
            tensor.close()
        }
    }

    fun close() {
        session.close()
    }

    /** 归一化后，余弦相似度就退化成一次点积，省掉两次求模 */
    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm < 1e-6f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }

    private fun describe(info: ai.onnxruntime.ValueInfo): String {
        val t = info as? TensorInfo ?: return info.toString()
        return "${t.type} ${t.shape.contentToString()}"
    }
}
