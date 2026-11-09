package com.example.androidfacedetector.inference

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.os.Trace
import android.util.Log
import com.example.androidfacedetector.detector.ScrfdDetector
import com.example.androidfacedetector.face.AntiSpoofDetector
import com.example.androidfacedetector.face.FaceAligner
import com.example.androidfacedetector.face.FaceDatabase
import com.example.androidfacedetector.face.FaceEmbedder
import com.example.androidfacedetector.ipc.FaceResultParcel
import java.io.Closeable

/**
 * 推理引擎：三个模型 + 特征库。**单进程与双进程两种模式复用同一份实现**，
 * 区别只在调用方是本进程直接调（LocalEngine）还是跨 Binder（InferenceService）。
 *
 * 这样对照实验才干净：算法完全相同，变量只有"是否跨进程"。
 */
class InferenceEngine(context: Context) : Closeable {

    companion object {
        private const val TAG = "InferenceEngine"
        /** 检测输入边长；第 7 周用它做"输入分辨率"对照实验 */
        var detectInputSize: Int = 640
    }

    private val appContext = context.applicationContext

    private val detector = ScrfdDetector(appContext, threads = 2)
    private val spoof = AntiSpoofDetector(appContext, threads = 2)
    private val embedder = FaceEmbedder(appContext, threads = 2)
    private val database = FaceDatabase(appContext)

    @Volatile
    var spoofEnabled: Boolean = true

    val names: List<String> get() = database.names()
    val dbSize: Int get() = database.size

    /**
     * 检测 -> 活体 -> 识别。
     * 活体未通过就短路，不再做对齐与特征提取（省算力，也让"翻拍不进识别"的语义落在代码里）。
     */
    fun analyze(bitmap: Bitmap, requestId: Long, captureNs: Long): FaceResultParcel {
        val receiveNs = System.nanoTime()
        val pid = Process.myPid()
        val tid = Process.myTid()

        Trace.beginSection("infer.detect")
        val det = try {
            detector.detect(bitmap)
        } finally {
            Trace.endSection()
        }
        val face = det.largest()

        if (face == null) {
            return FaceResultParcel(
                requestId = requestId, status = FaceResultParcel.STATUS_NO_FACE,
                serviceReceiveNs = receiveNs, serviceDoneNs = System.nanoTime(),
                servicePid = pid, serviceTid = tid,
                detectPreMs = det.preMs, detectInferMs = det.inferMs, detectPostMs = det.postMs
            )
        }

        val base = FaceResultParcel(
            requestId = requestId,
            status = FaceResultParcel.STATUS_OK,
            faceCount = det.detections.size,
            score = face.score,
            x1 = face.x1, y1 = face.y1, x2 = face.x2, y2 = face.y2,
            landmarks = face.landmarks,
            detectPreMs = det.preMs, detectInferMs = det.inferMs, detectPostMs = det.postMs,
            serviceReceiveNs = receiveNs, servicePid = pid, serviceTid = tid
        )

        // ---------- 活体 ----------
        if (!spoofEnabled) {
            return base.copy(serviceDoneNs = System.nanoTime(), livenessScore = 1f, isReal = true)
        }
        Trace.beginSection("infer.spoof")
        val sp = try {
            spoof.detect(bitmap, face.x1, face.y1, face.x2, face.y2)
        } finally {
            Trace.endSection()
        }
        if (!sp.isReal) {
            return base.copy(
                isReal = false,
                livenessScore = sp.score,
                spoofMs = sp.inferMs,
                serviceDoneNs = System.nanoTime()
            )
        }

        // ---------- 对齐 ----------
        Trace.beginSection("infer.align")
        val tAlign0 = System.nanoTime()
        val aligned = try {
            FaceAligner.align(bitmap, face.landmarks)
        } finally {
            Trace.endSection()
        }
        val tAlign1 = System.nanoTime()

        // ---------- 特征 ----------
        Trace.beginSection("infer.embed")
        val feature = try {
            embedder.embed(aligned)
        } finally {
            Trace.endSection()
        }
        val tEmbed1 = System.nanoTime()

        // ---------- 匹配 ----------
        Trace.beginSection("infer.match")
        val m = try {
            database.match(feature)
        } finally {
            Trace.endSection()
        }

        return base.copy(
            isReal = true,
            livenessScore = sp.score,
            spoofMs = sp.inferMs,
            name = if (m != null && m.similarity >= FaceEmbedder.MATCH_THRESHOLD) m.name else null,
            similarity = m?.similarity ?: 0f,
            alignMs = (tAlign1 - tAlign0) / 1_000_000f,
            embedMs = (tEmbed1 - tAlign1) / 1_000_000f,
            serviceDoneNs = System.nanoTime()
        )
    }

    /** 用当前帧最大脸的特征注册身份 */
    fun register(bitmap: Bitmap, name: String, requestId: Long): FaceResultParcel {
        val r = analyze(bitmap, requestId, 0)
        if (r.status != FaceResultParcel.STATUS_OK) return r
        if (!r.isReal) {
            return r.copy(status = FaceResultParcel.STATUS_ERROR, statusMessage = "活体未通过，拒绝注册")
        }
        // 重新算一遍特征用于入库（analyze 已算过，这里为了接口简单重算一次，代价约 8ms）
        val det = detector.detect(bitmap)
        val face = det.largest()
            ?: return r.copy(status = FaceResultParcel.STATUS_ERROR, statusMessage = "未检测到人脸")
        val aligned = FaceAligner.align(bitmap, face.landmarks)
        val feature = embedder.embed(aligned)
        database.register(name, feature)
        Log.i(TAG, "注册 $name 成功，库中 ${database.size} 人")
        return r.copy(name = name, statusMessage = "已注册 $name")
    }

    fun clearDatabase() {
        database.clear()
    }

    /** 当前线程池里活着的工作线程数（调试页用） */
    fun activeThreads(): Int {
        val prefix = "infer-"
        return Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith(prefix) }
    }

    override fun close() {
        detector.close()
        spoof.close()
        embedder.close()
    }
}
