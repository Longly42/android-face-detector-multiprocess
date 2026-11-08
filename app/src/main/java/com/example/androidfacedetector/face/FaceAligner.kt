package com.example.androidfacedetector.face

import android.graphics.Bitmap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 5 点人脸对齐。
 *
 * 为什么必须对齐：识别模型认的是"标准姿势的正脸"。你的头一歪、脸一侧，
 * 同样的脸在模型眼里就是另一个向量。所以先用 5 个关键点做一次几何变换，
 * 把脸"掰"成模板姿势，再送模型。
 *
 * 变换用的是相似变换（旋转 + 等比缩放 + 平移，不含错切和反射），
 * 二维情形下最优解有闭式公式，不需要 SVD：
 *
 *     theta = atan2(Cyx - Cxy, Cxx + Cyy)
 *
 * 其中 C = (1/n) * Σ (dst - mean_dst)(src - mean_src)^T
 */
object FaceAligner {

    const val SIZE = 112

    /**
     * ArcFace 标准 5 点模板（112x112），顺序必须和检测器给出的关键点一致：
     * 左眼、右眼、鼻尖、左嘴角、右嘴角。
     * 注意"左眼"是画面左边那只眼睛（对本人来说是右眼），跟检测模型的定义对齐。
     */
    private val REF = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    /**
     * 按 5 个关键点把人脸摆正，输出 112x112 的 Bitmap。
     *
     * @param src       原图（任意尺寸）
     * @param landmarks 10 个 float，依次是 5 个点的 x,y
     */
    fun align(src: Bitmap, landmarks: FloatArray): Bitmap {
        val m = estimateTransform(landmarks, REF)   // 原图 -> 模板
        val inv = invert(m)                          // 模板 -> 原图（反向采样用）

        val w = src.width
        val h = src.height
        val srcPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val dst = IntArray(SIZE * SIZE)
        var idx = 0
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                // 模板上的每个像素，反查它在原图里的位置
                val sx = inv[0] * x + inv[1] * y + inv[2]
                val sy = inv[3] * x + inv[4] * y + inv[5]
                dst[idx++] = sample(srcPixels, w, h, sx, sy)
            }
        }
        return Bitmap.createBitmap(dst, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    /** 求 c,R,t 使 dst ≈ c*R*src + t，返回 2x3 矩阵 {a,b,tx, c,d,ty} */
    private fun estimateTransform(src: FloatArray, dst: FloatArray): FloatArray {
        val n = src.size / 2
        var msx = 0f; var msy = 0f; var mdx = 0f; var mdy = 0f
        for (i in 0 until n) {
            msx += src[i * 2]; msy += src[i * 2 + 1]
            mdx += dst[i * 2]; mdy += dst[i * 2 + 1]
        }
        msx /= n; msy /= n; mdx /= n; mdy /= n

        var cxx = 0f; var cxy = 0f; var cyx = 0f; var cyy = 0f; var varS = 0f
        for (i in 0 until n) {
            val sx = src[i * 2] - msx
            val sy = src[i * 2 + 1] - msy
            val dx = dst[i * 2] - mdx
            val dy = dst[i * 2 + 1] - mdy
            cxx += dx * sx; cxy += dx * sy
            cyx += dy * sx; cyy += dy * sy
            varS += sx * sx + sy * sy
        }
        cxx /= n; cxy /= n; cyx /= n; cyy /= n; varS /= n

        val theta = atan2(cyx - cxy, cxx + cyy)
        val ct = cos(theta)
        val st = sin(theta)
        val scale = if (varS > 1e-12f) (ct * (cxx + cyy) + st * (cyx - cxy)) / varS else 1f

        // R = [[ct,-st],[st,ct]]，再乘 scale
        val a = scale * ct
        val b = -scale * st
        val c = scale * st
        val d = scale * ct
        val tx = mdx - (a * msx + b * msy)
        val ty = mdy - (c * msx + d * msy)
        return floatArrayOf(a, b, tx, c, d, ty)
    }

    /** 2x3 仿射矩阵求逆 */
    private fun invert(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[1]; val tx = m[2]
        val c = m[3]; val d = m[4]; val ty = m[5]
        val det = a * d - b * c
        if (kotlin.math.abs(det) < 1e-9f) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return floatArrayOf(
            ia, ib, -(ia * tx + ib * ty),
            ic, id, -(ic * tx + id * ty)
        )
    }

    /** 双线性采样：取 (x,y) 处颜色，越界就贴到边缘 */
    private fun sample(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val cx = x.coerceIn(0f, (w - 1).toFloat())
        val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = if (x0 + 1 < w) x0 + 1 else x0
        val y1 = if (y0 + 1 < h) y0 + 1 else y0
        val fx = cx - x0
        val fy = cy - y0

        val p00 = px[y0 * w + x0]
        val p10 = px[y0 * w + x1]
        val p01 = px[y1 * w + x0]
        val p11 = px[y1 * w + x1]

        val r = blend((p00 shr 16) and 0xFF, (p10 shr 16) and 0xFF, (p01 shr 16) and 0xFF, (p11 shr 16) and 0xFF, fx, fy)
        val g = blend((p00 shr 8) and 0xFF, (p10 shr 8) and 0xFF, (p01 shr 8) and 0xFF, (p11 shr 8) and 0xFF, fx, fy)
        val b = blend(p00 and 0xFF, p10 and 0xFF, p01 and 0xFF, p11 and 0xFF, fx, fy)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun blend(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Int {
        val top = c00 + (c10 - c00) * fx
        val bot = c01 + (c11 - c01) * fx
        return (top + (bot - top) * fy).toInt().coerceIn(0, 255)
    }
}
