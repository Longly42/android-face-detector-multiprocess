package com.example.androidfacedetector.perf

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 滑动窗口时延统计：给对照实验输出 平均 / P50 / P95。
 * 用固定长度环形缓冲，避免长时间运行内存膨胀。
 */
class LatencyStats(private val capacity: Int = 256) {

    private val ring = FloatArray(capacity)
    private var writeIdx = 0
    private var size = 0

    val count: Int get() = size

    @Synchronized
    fun add(ms: Float) {
        if (ms < 0 || ms.isNaN() || ms.isInfinite()) return
        ring[writeIdx] = ms
        writeIdx = (writeIdx + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized
    fun avg(): Float {
        if (size == 0) return 0f
        var s = 0f
        for (i in 0 until size) s += ring[i]
        return s / size
    }

    @Synchronized
    fun max(): Float {
        if (size == 0) return 0f
        var m = 0f
        for (i in 0 until size) if (ring[i] > m) m = ring[i]
        return m
    }

    /** p 取 0~1，例如 0.5 / 0.95 */
    @Synchronized
    fun percentile(p: Float): Float {
        if (size == 0) return 0f
        val copy = ring.copyOf(size)
        copy.sort()
        val idx = ((size - 1) * p).toInt().coerceIn(0, size - 1)
        return copy[idx]
    }

    @Synchronized
    fun reset() {
        writeIdx = 0
        size = 0
    }
}

/**
 * 一次完整采集→显示的事件链记录。
 *
 * 时间戳策略：全部用 System.nanoTime()（单调时钟），跨进程比较也安全，
 * 因为同一台设备上 nanoTime 是同一个单调基准；记录里再附 elapseRealtime，
 * 便于与 Perfetto/atrace 的 CLOCK_MONOTONIC 对齐。
 */
data class FrameEvent(
    val requestId: Long,
    /** 单帧序号（本进程内递增） */
    val seq: Long,
    val captureNs: Long,
    val convertDoneNs: Long,
    val ipcCallStartNs: Long,
    val ipcReturnNs: Long,
    val uiShownNs: Long,
    val convertMs: Float,
    val ipcRoundMs: Float,
    val serviceWorkMs: Float,
    val queueWaitMs: Float,
    val e2eMs: Float,
    val detectPreMs: Float,
    val detectInferMs: Float,
    val detectPostMs: Float,
    val spoofMs: Float,
    val alignMs: Float,
    val embedMs: Float,
    val status: Int,
    val faceCount: Int,
    val liveness: Boolean,
    val mode: String
)

/**
 * 记录事件链并导出 CSV。
 *
 * 导出路径为应用私有目录，取数据用：
 *   adb pull /sdcard/Android/data/com.example.androidfacedetector/files/metrics
 */
object MetricsRecorder {

    private const val TAG = "Metrics"
    private const val CAPACITY = 3000

    private val events = ArrayDeque<FrameEvent>(CAPACITY)

    @Synchronized
    fun record(e: FrameEvent) {
        if (events.size >= CAPACITY) events.removeFirst()
        events.addLast(e)
        // 结构化单行日志，方便 logcat 直接 grep 与后续脚本解析
        Log.i(
            TAG,
            "FRAME req=${e.requestId} seq=${e.seq} mode=${e.mode} status=${e.status} " +
                    "e2e=${"%.2f".format(e.e2eMs)} conv=${"%.2f".format(e.convertMs)} " +
                    "ipc=${"%.2f".format(e.ipcRoundMs)} queue=${"%.2f".format(e.queueWaitMs)} " +
                    "work=${"%.2f".format(e.serviceWorkMs)} det=${"%.2f".format(e.detectInferMs)} " +
                    "spoof=${"%.2f".format(e.spoofMs)} align=${"%.2f".format(e.alignMs)} " +
                    "emb=${"%.2f".format(e.embedMs)} faces=${e.faceCount} real=${e.liveness}"
        )
    }

    @Synchronized
    fun snapshot(): List<FrameEvent> = events.toList()

    @Synchronized
    fun size(): Int = events.size

    @Synchronized
    fun clear() = events.clear()

    /** 导出 CSV，返回文件；路径在 filesDir/metrics/ 下 */
    fun exportCsv(context: Context, tag: String = ""): File? {
        val list = snapshot()
        if (list.isEmpty()) return null
        val dir = File(context.filesDir, "metrics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = tag.replace(Regex("[^A-Za-z0-9_=.-]"), "_")
        val file = File(dir, if (safe.isEmpty()) "frames_$stamp.csv" else "frames_${safe}_$stamp.csv")
        val header = "requestId,seq,mode,status,faces,liveness," +
                "e2e_ms,convert_ms,ipc_ms,queue_wait_ms,service_work_ms," +
                "detect_pre_ms,detect_infer_ms,detect_post_ms,spoof_ms,align_ms,embed_ms," +
                "capture_uptime_ms\n"
        val sb = StringBuilder(header)
        for (e in list) {
            sb.append(e.requestId).append(',')
                .append(e.seq).append(',')
                .append(e.mode).append(',')
                .append(e.status).append(',')
                .append(e.faceCount).append(',')
                .append(if (e.liveness) 1 else 0).append(',')
                .append(fmt(e.e2eMs)).append(',')
                .append(fmt(e.convertMs)).append(',')
                .append(fmt(e.ipcRoundMs)).append(',')
                .append(fmt(e.queueWaitMs)).append(',')
                .append(fmt(e.serviceWorkMs)).append(',')
                .append(fmt(e.detectPreMs)).append(',')
                .append(fmt(e.detectInferMs)).append(',')
                .append(fmt(e.detectPostMs)).append(',')
                .append(fmt(e.spoofMs)).append(',')
                .append(fmt(e.alignMs)).append(',')
                .append(fmt(e.embedMs)).append(',')
                .append(e.captureNs / 1_000_000)
                .append('\n')
        }
        return try {
            file.writeText(sb.toString())
            Log.i(TAG, "已导出 ${list.size} 条记录到 $file")
            file
        } catch (t: Throwable) {
            Log.e(TAG, "导出失败", t)
            null
        }
    }

    /** 生成一段可放进报告的摘要文本 */
    fun summaryText(): String {
        val list = snapshot()
        if (list.isEmpty()) return "暂无数据"
        val e2e = LatencyStats().apply { list.forEach { add(it.e2eMs) } }
        val ipc = LatencyStats().apply { list.forEach { add(it.ipcRoundMs) } }
        val work = LatencyStats().apply { list.forEach { add(it.serviceWorkMs) } }
        return buildString {
            appendLine("样本数：${list.size}")
            appendLine("端到端  avg=${"%.1f".format(e2e.avg())}  P50=${"%.1f".format(e2e.percentile(0.5f))}  P95=${"%.1f".format(e2e.percentile(0.95f))}  max=${"%.1f".format(e2e.max())}")
            appendLine("IPC往返 avg=${"%.1f".format(ipc.avg())}  P50=${"%.1f".format(ipc.percentile(0.5f))}  P95=${"%.1f".format(ipc.percentile(0.95f))}")
            appendLine("服务端处理 avg=${"%.1f".format(work.avg())}  P50=${"%.1f".format(work.percentile(0.5f))}  P95=${"%.1f".format(work.percentile(0.95f))}")
        }
    }

    private fun fmt(v: Float): String = if (v == 0f) "0" else String.format(Locale.US, "%.2f", v)

    fun uptimeMs(): Long = SystemClock.uptimeMillis()
}
