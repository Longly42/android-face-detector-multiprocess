package com.example.androidfacedetector

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.androidfacedetector.detector.ScrfdDetector
import com.example.androidfacedetector.face.FaceAligner
import com.example.androidfacedetector.face.FaceEmbedder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端侧对齐 + 特征提取的一致性测试。
 *
 * 拿一张已知图片，在设备上跑完整链路，把两两余弦相似度打出来，
 * 和 PC 端 Python 参考实现的结果对照。
 *
 * 意义：对齐的仿射变换、双线性采样、归一化这几步只要有一处抄错，
 * 相似度就会整体垮掉（同人识别不出来）。这个测试能在改代码时第一时间发现。
 *
 * 参考值由 .tmp_model/validate_face.py 在 PC 上算出：
 *   检出 6 张脸，脸0 vs 脸1 = 0.0068，脸0 vs 脸3 = 0.1787 …
 */
@RunWith(AndroidJUnit4::class)
class RecognitionParityTest {

    private val tag = "ParityTest"

    @Test
    fun alignAndEmbedMatchesPcReference() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context

        // 测试图不入库（含肖像，不便随仓库分发）。
        // 自备一张多的人合照放到 app/src/androidTest/assets/t1.jpg 后即可运行本测试。
        val hasImage = try {
            testCtx.assets.open("t1.jpg").close(); true
        } catch (e: Exception) {
            false
        }
        Assume.assumeTrue("未提供测试图 app/src/androidTest/assets/t1.jpg，跳过", hasImage)

        val bmp = testCtx.assets.open("t1.jpg").use { BitmapFactory.decodeStream(it) }
        val w = bmp.width
        val h = bmp.height
        Log.i(tag, "测试图 ${w}x$h")

        val detector = ScrfdDetector(target)
        val embedder = FaceEmbedder(target)
        try {
            val res = detector.detect(bmp)
            Log.i(tag, "检出 ${res.detections.size} 张脸，耗时 预处理${res.preMs} 推理${res.inferMs} 后处理${res.postMs}")

            val features = res.detections.map { d ->
                val t0 = System.nanoTime()
                val aligned = FaceAligner.align(bmp, d.landmarks)
                val t1 = System.nanoTime()
                val f = embedder.embed(aligned)
                val t2 = System.nanoTime()
                Log.i(tag, "  脸 score=${d.score} 对齐=${(t1 - t0) / 1e6f}ms 特征=${(t2 - t1) / 1e6f}ms")
                f
            }

            var sumSq = 0f
            for (f in features) for (v in f) sumSq += v * v
            Log.i(tag, "特征范数平方平均值=${sumSq / features.size}（L2 归一化后应≈1.0）")

            for (i in features.indices) {
                for (j in i + 1 until features.size) {
                    Log.i(tag, "PAIR $i-$j = ${"%.4f".format(FaceEmbedder.cosine(features[i], features[j]))}")
                }
            }

            // 和 Python 参考值对照
            assertEquals("检出人脸数应与 PC 端一致", 6, res.detections.size)
            val expect = mapOf(
                "0-1" to 0.0068f,
                "0-2" to 0.0314f,
                "0-3" to 0.1787f,
                "1-2" to 0.0087f,
                "2-3" to 0.0228f
            )
            for ((pair, want) in expect) {
                val (i, j) = pair.split("-").map { it.toInt() }
                val got = FaceEmbedder.cosine(features[i], features[j])
                Log.i(tag, "对照 $pair 期望≈$want 实测=$got")
                assertTrue(
                    "相似度 $pair 偏差过大：期望≈$want，实测=$got",
                    kotlin.math.abs(got - want) < 0.05f
                )
            }
        } finally {
            detector.close()
            embedder.close()
        }
    }
}
