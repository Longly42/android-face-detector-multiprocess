package com.example.androidfacedetector

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.androidfacedetector.detector.ScrfdDetector
import com.example.androidfacedetector.face.AntiSpoofDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 活体检测的端侧一致性测试。
 *
 * 需要 Silent-Face-Anti-Spoofing 官方样例图（含人像，不入库）：
 *   app/src/androidTest/assets/image_T1.jpg  ->  官方预期：真脸
 *   app/src/androidTest/assets/image_F1.jpg  ->  官方预期：假脸
 * 缺图时自动跳过。
 *
 * 参考结果（PC 端 Python，.tmp_model/validate_antispoof.py，cv2 语义 resize）：
 *   image_T1.jpg -> REAL  score=0.9980
 *   image_F1.jpg -> FAKE  score=0.7237
 */
@RunWith(AndroidJUnit4::class)
class AntiSpoofParityTest {

    private val tag = "SpoofParityTest"

    private fun readOrNull(ctx: android.content.Context, name: String): android.graphics.Bitmap? =
        try {
            ctx.assets.open(name).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }

    @Test
    fun officialSamplesMatchPcReference() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context

        val realImg = readOrNull(testCtx, "image_T1.jpg")
        val fakeImg = readOrNull(testCtx, "image_F1.jpg")
        Assume.assumeTrue("缺少官方样例图 image_T1/image_F1，跳过", realImg != null && fakeImg != null)

        val detector = ScrfdDetector(target)
        val spoifer = AntiSpoofDetector(target)
        try {
            // 真脸样例
            val realBox = detector.detect(realImg!!).largest()
            assertTrue("T1 应检出人脸", realBox != null)
            Log.i(tag, "T1 检测框 x1=${realBox!!.x1} y1=${realBox.y1} x2=${realBox.x2} y2=${realBox.y2} " +
                    "score=${realBox.score} (PC 参考 bbox=[97,186,307,176])")
            Log.i(tag, "T1 裁剪块 B/G/R 均值 = " +
                    spoifer.debugCropStats(realImg, realBox.x1, realBox.y1, realBox.x2, realBox.y2)
                        .joinToString(" ") { "%.2f".format(it) } + "  (PC 端另行打印)")
            val real = spoifer.detect(realImg, realBox!!.x1, realBox.y1, realBox.x2, realBox.y2)
            Log.i(tag, "image_T1 -> isReal=${real.isReal} score=${real.score} realProb=${real.realProb} ${real.inferMs}ms")

            // 假脸样例
            val fakeBox = detector.detect(fakeImg!!).largest()
            assertTrue("F1 应检出人脸", fakeBox != null)
            val fake = spoifer.detect(fakeImg, fakeBox!!.x1, fakeBox.y1, fakeBox.x2, fakeBox.y2)
            Log.i(tag, "image_F1 -> isReal=${fake.isReal} score=${fake.score} realProb=${fake.realProb} ${fake.inferMs}ms")

            assertTrue("T1 应判为真脸（PC 参考 isReal=true, score≈0.998）", real.isReal)
            assertTrue("F1 应判为假脸（PC 参考 isReal=false, score≈0.724）", !fake.isReal)
            assertEquals("T1 分数应与 PC 端一致", 0.9980f, real.score, 0.02f)
            assertEquals("F1 分数应与 PC 端一致", 0.7237f, fake.score, 0.02f)
        } finally {
            detector.close()
            spoifer.close()
        }
    }
}
