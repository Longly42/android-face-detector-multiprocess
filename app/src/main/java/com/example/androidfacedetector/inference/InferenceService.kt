package com.example.androidfacedetector.inference

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SharedMemory
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.example.androidfacedetector.ipc.FaceResultParcel
import com.example.androidfacedetector.ipc.IFaceInference
import com.example.androidfacedetector.ipc.ServiceStatsParcel
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 独立推理进程（android:process=":inference"）里的 Binder 服务。
 *
 * 三个 OS 机制集中在这一层：
 *  1. **进程隔离**：模型与特征库只在该进程内，UI 进程崩溃不影响它，反之亦然；
 *     代价是多一次 Binder 往返。
 *  2. **线程池（ExecutorService）**：Binder 线程只负责收请求，真正的推理丢给
 *     固定大小线程池（线程名 infer-0/1/...），池大小可运行期调整（1/2/4 对照实验）。
 *  3. **Semaphore 限流（生产者—消费者）**：主进程是生产者、这里是消费者。
 *     许可数 = 允许同时"在途"的任务上限（含排队），拿不到许可就立刻丢弃这一帧并回
 *     一个 DROPPED 结果——这是背压信号，主进程据此统计丢帧率。
 *     许可在 finally 里释放，超时和异常路径同样会还回去，不会泄漏。
 *
 * 注意：这是**进程内**的 Java 信号量，只约束本进程的并发额度，
 * 不是跨进程共享信号量；跨进程的协作靠 Binder 同步调用本身。
 */
class InferenceService : Service() {

    companion object {
        private const val TAG = "InferenceService"
        /** 等许可的最长时间：超过就判定为"来不及处理"，直接丢帧 */
        private const val PERMIT_WAIT_MS = 60L
        /** 单帧推理的硬超时：防止异常任务把 Binder 线程永久挂住 */
        private const val WORK_TIMEOUT_MS = 3000L
    }

    @Volatile
    private var engine: InferenceEngine? = null

    // ---- 线程池 ----
    private val threadIndex = AtomicInteger(0)
    @Volatile
    private var poolSize = 2
    @Volatile
    private var pool: ThreadPoolExecutor = createPool(2)

    // ---- 信号量 ----
    @Volatile
    private var maxPermits = 3
    @Volatile
    private var permits = Semaphore(3)

    // ---- 统计 ----
    private val total = AtomicLong(0)
    private val processed = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val timeouts = AtomicLong(0)
    private val errors = AtomicLong(0)
    private val inFlight = AtomicInteger(0)
    private val queueWaitSum = AtomicLong(0)
    private val queueWaitCount = AtomicLong(0)
    private val queueWaitMax = AtomicLong(0)
    private val workSum = AtomicLong(0)

    private fun createPool(n: Int): ThreadPoolExecutor {
        threadIndex.set(0)
        return ThreadPoolExecutor(
            n, n, 0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        ) { r ->
            Thread(r, "infer-${threadIndex.getAndIncrement()}").apply {
                priority = Thread.NORM_PRIORITY - 1   // 略低于 UI 线程，避免抢帧
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "服务创建, pid=${Process.myPid()} tid=${Process.myTid()}")
        engine = InferenceEngine(this)
    }

    override fun onDestroy() {
        Log.i(TAG, "服务销毁")
        engine?.close()
        engine = null
        pool.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IFaceInference.Stub() {

        override fun analyze(
            frame: SharedMemory,
            width: Int,
            height: Int,
            requestId: Long,
            captureNs: Long
        ): FaceResultParcel = handle(frame, width, height, requestId, captureNs, null)

        override fun register(
            frame: SharedMemory,
            width: Int,
            height: Int,
            requestId: Long,
            name: String
        ): FaceResultParcel = handle(frame, width, height, requestId, 0L, name)

        override fun getStats(): ServiceStatsParcel {
            // queueWaitSum / workSum 存的是纳秒，展示要换成毫秒
            val avgQueue = if (queueWaitCount.get() > 0)
                queueWaitSum.get().toFloat() / queueWaitCount.get() / 1_000_000f else 0f
            val avgWork = if (processed.get() > 0)
                workSum.get().toFloat() / processed.get() / 1_000_000f else 0f
            return ServiceStatsParcel(
                pid = Process.myPid(),
                totalRequests = total.get(),
                processed = processed.get(),
                dropped = dropped.get(),
                timeouts = timeouts.get(),
                errors = errors.get(),
                inFlight = inFlight.get(),
                availablePermits = permits.availablePermits(),
                maxPermits = maxPermits,
                queueLength = pool.queue.size,
                poolSize = poolSize,
                activeThreads = pool.activeCount,
                avgQueueWaitMs = avgQueue,
                maxQueueWaitMs = queueWaitMax.get() / 1_000_000f,
                avgWorkMs = avgWork,
                dbSize = engine?.dbSize ?: 0,
                threadNames = Thread.getAllStackTraces().keys
                    .filter { it.isAlive && it.name.startsWith("infer-") }
                    .map { "${it.name}(tid=${it.id})" }
            )
        }

        override fun resetStats() {
            total.set(0); processed.set(0); dropped.set(0)
            timeouts.set(0); errors.set(0)
            queueWaitSum.set(0); queueWaitCount.set(0); queueWaitMax.set(0); workSum.set(0)
            Log.i(TAG, "统计已清零")
        }

        override fun configure(threads: Int, permitCount: Int) {
            val t = threads.coerceIn(1, 4)
            val p = permitCount.coerceIn(1, 8)
            if (inFlight.get() > 0) {
                Log.w(TAG, "有在途任务(${inFlight.get()})，延后生效")
            }
            pool.shutdown()
            pool = createPool(t)
            poolSize = t
            permits = Semaphore(p)
            maxPermits = p
            Log.i(TAG, "配置更新: 线程数=$t, 许可数=$p")
        }

        override fun setDetectResolution(size: Int) {
            engine?.setDetectResolution(size)
        }

        override fun clearDatabase() {
            engine?.clearDatabase()
        }

        override fun listNames(): List<String> = engine?.names ?: emptyList()

        override fun crashForTest() {
            Log.w(TAG, "收到崩溃指令，立即退出进程 pid=${Process.myPid()}")
            // 直接杀自己：模拟"推理服务意外终止"，验证主进程的断连提示与重连
            Process.killProcess(Process.myPid())
        }
    }

    /**
     * 请求处理主流程。注意 finally 里一定 release 许可——这是"异常与超时路径
     * 也要归还同步资源"的关键，否则跑几次超时就把额度耗光了。
     */
    private fun handle(
        frame: SharedMemory,
        width: Int,
        height: Int,
        requestId: Long,
        captureNs: Long,
        registerName: String?
    ): FaceResultParcel {
        val tArrive = System.nanoTime()
        total.incrementAndGet()

        // ① 取许可（限流）。取不到说明在途任务已满 -> 丢弃这一帧，快速返回
        val acquired = try {
            permits.tryAcquire(PERMIT_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            dropped.incrementAndGet()
            closeQuietly(frame)
            Trace.beginSection("frame.dropped"); Trace.endSection()
            return FaceResultParcel(
                requestId = requestId,
                status = FaceResultParcel.STATUS_DROPPED,
                statusMessage = "在途任务已满，丢弃",
                serviceReceiveNs = tArrive,
                serviceDoneNs = System.nanoTime(),
                servicePid = Process.myPid(),
                serviceTid = Process.myTid()
            )
        }

        inFlight.incrementAndGet()
        val tAcquired = System.nanoTime()
        val queueWaitMs = (tAcquired - tArrive) / 1_000_000f
        queueWaitSum.addAndGet((tAcquired - tArrive))
        queueWaitCount.incrementAndGet()
        queueWaitMax.updateAndGet { m -> maxOf(m, tAcquired - tArrive) }

        var bitmap: android.graphics.Bitmap? = null
        try {
            // ② 从共享内存读像素（不经过 Binder 事务体）
            Trace.beginSection("svc.readFrame")
            bitmap = readFrame(frame, width, height)
            Trace.endSection()
            closeQuietly(frame)

            // ③ 丢给线程池执行，本 Binder 线程在此等待结果
            val eng = engine ?: throw IllegalStateException("引擎未初始化")
            val task = pool.submit<FaceResultParcel> {
                val t0 = System.nanoTime()
                Trace.beginSection("svc.infer")
                try {
                    if (registerName != null) eng.register(bitmap, registerName, requestId)
                    else eng.analyze(bitmap, requestId, captureNs)
                } finally {
                    Trace.endSection()
                    workSum.addAndGet(System.nanoTime() - t0)
                }
            }

            val r = task.get(WORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            processed.incrementAndGet()
            return r.copy(queueWaitMs = queueWaitMs)
        } catch (e: TimeoutException) {
            timeouts.incrementAndGet()
            Log.e(TAG, "推理超时 requestId=$requestId")
            return FaceResultParcel(
                requestId = requestId,
                status = FaceResultParcel.STATUS_TIMEOUT,
                statusMessage = "推理超时 > ${WORK_TIMEOUT_MS}ms",
                serviceReceiveNs = tArrive,
                serviceDoneNs = System.nanoTime(),
                servicePid = Process.myPid(),
                serviceTid = Process.myTid(),
                queueWaitMs = queueWaitMs
            )
        } catch (t: Throwable) {
            errors.incrementAndGet()
            Log.e(TAG, "推理异常 requestId=$requestId", t)
            return FaceResultParcel(
                requestId = requestId,
                status = FaceResultParcel.STATUS_ERROR,
                statusMessage = "推理异常: ${t.javaClass.simpleName}",
                serviceReceiveNs = tArrive,
                serviceDoneNs = System.nanoTime(),
                servicePid = Process.myPid(),
                serviceTid = Process.myTid(),
                queueWaitMs = queueWaitMs
            )
        } finally {
            // ★ 无论是正常、超时还是异常，许可都要还回去
            inFlight.decrementAndGet()
            permits.release()
            bitmap?.recycle()
        }
    }

    /**
     * 从共享内存映射（mmap）直接读出像素还原成 Bitmap。
     * 全程没有一次 1.2MB 的字节拷贝，只有 Bitmap 内部的一次填充。
     */
    private fun readFrame(shm: SharedMemory, width: Int, height: Int): android.graphics.Bitmap {
        val buf = shm.mapReadOnly()
        try {
            buf.order(ByteOrder.nativeOrder())
            buf.rewind()
            val bmp = android.graphics.Bitmap.createBitmap(
                width, height, android.graphics.Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buf)
            return bmp
        } finally {
            SharedMemory.unmap(buf)
        }
    }

    /** 服务端持有的是 Parcel 反序列化出的新 fd，用完必须关，否则每帧漏一个 fd */
    private fun closeQuietly(shm: SharedMemory) {
        try {
            shm.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭共享内存失败", e)
        }
    }
}
