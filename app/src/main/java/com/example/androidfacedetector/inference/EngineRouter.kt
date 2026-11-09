package com.example.androidfacedetector.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.androidfacedetector.ipc.FaceClient
import com.example.androidfacedetector.ipc.FaceResultParcel
import com.example.androidfacedetector.ipc.ServiceStatsParcel
import java.util.concurrent.atomic.AtomicInteger
import java.io.Closeable

/** 两种运行配置，用于第 7 周"单进程 / 双进程"对照实验 */
enum class RunMode(val label: String) {
    DUAL("双进程 + Binder IPC"),
    SINGLE("单进程直调")
}

/**
 * 在"单进程直调"与"双进程 Binder"之间切换。
 *
 * 关键设计：两种模式下**算法实现完全相同**（同一个 InferenceEngine），
 * 唯一变量是调用是否跨进程。否则对照实验不成立。
 *
 * 切换时会释放另一侧：单进程模式关掉 Binder 连接；双进程模式释放本地引擎，
 * 避免两份模型同时占内存（三个模型约 19MB 权重，加上 ORT 运行时更多）。
 */
class EngineRouter(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "EngineRouter"
    }

    @Volatile
    var mode: RunMode = RunMode.DUAL
        private set

    /**
     * 跳帧倍率：1 = 每帧都分析；2 = 每两帧分析一次。
     * 第 7 周系统侧优化项，用来观察"降低分析频率换稳定性"的取舍。
     */
    @Volatile
    var frameSkip: Int = 1

    /** 当前检测输入边长，用于给导出数据打标签 */
    @Volatile
    var detectSize: Int = 640
        private set

    /** 形如 DUAL_det640_skip1，导出 CSV 时拼进文件名 */
    fun configTag(): String = "${mode.name}_det${detectSize}_skip${frameSkip}"

    @Volatile
    var localReady: Boolean = false
        private set

    @Volatile
    var localLoading: Boolean = false
        private set

    private var client: FaceClient? = null
    private var local: InferenceEngine? = null

    /** UI 回调先存在这里：设置时 client 可能还没创建（首次进入界面就是这种情况） */
    private var pendingListener: FaceClient.Listener? = null

    /** 绑定状态回调，设置时立即应用，之后创建的 client 也会带上 */
    var listener: FaceClient.Listener?
        get() = pendingListener
        set(v) {
            pendingListener = v
            client?.listener = v
        }

    /** 确保 client 存在，并把已保存的回调挂上去 */
    private fun ensureClient(): FaceClient {
        val c = client ?: FaceClient(context).also {
            client = it
            it.listener = pendingListener
        }
        return c
    }

    val clientRef: FaceClient? get() = client

    /** 切换到某个模式；onReady 在本地引擎就绪后回到调用线程（IO 线程） */
    fun setMode(newMode: RunMode, onLocalReady: (() -> Unit)? = null) {
        if (newMode == mode && (newMode != RunMode.SINGLE || localReady)) {
            // 已经是目标模式：仍要确保双进程侧的连接存在（首次进入时 client 还没建）
            if (newMode == RunMode.DUAL) ensureClient().bind()
            return
        }
        mode = newMode
        when (newMode) {
            RunMode.DUAL -> {
                // 释放本地引擎，避免双份内存
                local?.let {
                    Log.i(TAG, "释放本地引擎")
                    it.close()
                }
                local = null
                localReady = false
                localLoading = false

                ensureClient().bind()
            }
            RunMode.SINGLE -> {
                client?.unbind()
                if (local == null && !localLoading) {
                    localLoading = true
                    localReady = false
                    Log.i(TAG, "开始加载本地引擎")
                    local = InferenceEngine(context)
                    localReady = true
                    localLoading = false
                    Log.i(TAG, "本地引擎就绪")
                }
                onLocalReady?.invoke()
            }
        }
    }

    /** 单进程模式需要预热（加载三个模型约 2~3 秒），在 IO 线程调用 */
    fun warmUpLocal() {
        if (local != null || localLoading) return
        localLoading = true
        try {
            local = InferenceEngine(context)
            localReady = true
        } finally {
            localLoading = false
        }
    }

    fun analyze(bitmap: Bitmap, requestId: Long, captureNs: Long): FaceResultParcel {
        return when (mode) {
            RunMode.DUAL -> {
                val c = client ?: return notConnected(requestId)
                c.analyze(bitmap, requestId, captureNs)
            }
            RunMode.SINGLE -> {
                val e = local ?: return loading(requestId)
                e.analyze(bitmap, requestId, captureNs)
            }
        }
    }

    fun register(bitmap: Bitmap, name: String, requestId: Long): FaceResultParcel {
        return when (mode) {
            RunMode.DUAL -> {
                val c = client ?: return notConnected(requestId)
                c.register(bitmap, name, requestId)
            }
            RunMode.SINGLE -> {
                val e = local ?: return loading(requestId)
                e.register(bitmap, name, requestId)
            }
        }
    }

    fun stats(): ServiceStatsParcel? = when (mode) {
        RunMode.DUAL -> client?.stats()
        RunMode.SINGLE -> local?.let {
            ServiceStatsParcel(
                pid = android.os.Process.myPid(),
                totalRequests = 0,
                processed = 0,
                dbSize = it.dbSize,
                threadNames = Thread.getAllStackTraces().keys
                    .filter { t -> t.isAlive && t.name.startsWith("infer-") }
                    .map { t -> "${t.name}(tid=${t.id})" }
            )
        }
    }

    fun names(): List<String> = when (mode) {
        RunMode.DUAL -> client?.listNames() ?: emptyList()
        RunMode.SINGLE -> local?.names ?: emptyList()
    }

    fun setDetectResolution(size: Int) {
        detectSize = size
        when (mode) {
            RunMode.DUAL -> client?.setDetectResolution(size)
            RunMode.SINGLE -> local?.setDetectResolution(size)
        }
    }

    fun clearDatabase() {
        when (mode) {
            RunMode.DUAL -> client?.clearDatabase()
            RunMode.SINGLE -> local?.clearDatabase()
        }
    }

    fun configure(threads: Int, permits: Int) {
        client?.configure(threads, permits)
    }

    fun crashServiceForTest() {
        client?.crashServiceForTest()
    }

    /**
     * 并发压测：同时提交 n 帧，用来观察"有界并发 + 丢帧"的行为。
     *
     * 为什么需要它：单相机场景下 CameraX 的 Analyzer 是串行回调的，
     * 永远只有 1 帧在途，Semaphore 根本不会满。要比较"无限流"与"有界并发"，
     * 必须人为制造并发，否则实验数据是 0 丢帧、看不出差别。
     */
    fun stressTest(n: Int, width: Int = 480, height: Int = 640): String {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(n)
        val latch = java.util.concurrent.CountDownLatch(n)
        val dropped = AtomicInteger(0)
        val ok = AtomicInteger(0)
        val timeout = AtomicInteger(0)
        val t0 = System.nanoTime()
        repeat(n) { i ->
            pool.execute {
                try {
                    val r = analyze(bmp, 9_000_000L + i, System.nanoTime())
                    when (r.status) {
                        FaceResultParcel.STATUS_DROPPED -> dropped.incrementAndGet()
                        FaceResultParcel.STATUS_TIMEOUT -> timeout.incrementAndGet()
                        else -> ok.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "压测帧异常", t)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        pool.shutdownNow()
        val cost = (System.nanoTime() - t0) / 1_000_000f
        bmp.recycle()
        return "并发 $n 帧：完成 $ok，丢弃 ${dropped.get()}，超时 ${timeout.get()}，总耗时 ${"%.0f".format(cost)}ms"
    }

    fun resetStats() {
        client?.resetStats()
    }

    private fun notConnected(id: Long) = FaceResultParcel(
        requestId = id,
        status = FaceResultParcel.STATUS_ERROR,
        statusMessage = "推理服务未连接"
    )

    private fun loading(id: Long) = FaceResultParcel(
        requestId = id,
        status = FaceResultParcel.STATUS_ERROR,
        statusMessage = if (localLoading) "本地模型加载中…" else "本地引擎未就绪"
    )

    override fun close() {
        client?.close()
        client = null
        local?.close()
        local = null
    }
}
