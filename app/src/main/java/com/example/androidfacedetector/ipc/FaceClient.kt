package com.example.androidfacedetector.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import com.example.androidfacedetector.inference.InferenceService
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 主进程侧的 Binder 客户端。
 *
 * 负责三件事：
 *  1. 绑定/解绑 :inference 进程的服务，并把连接状态回调给 UI；
 *  2. 把 Bitmap 写进共享内存后发起同步 AIDL 调用，记录**IPC 往返时延**；
 *  3. 服务进程意外死亡时（binder 死亡回调 / onServiceDisconnected）通知 UI 并自动重连。
 */
class FaceClient(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "FaceClient"
    }

    interface Listener {
        fun onServiceConnected(pid: Int)
        fun onServiceDisconnected(reason: String)
    }

    @Volatile
    var listener: Listener? = null

    private var remote: IFaceInference? = null
    private var wantBound = false

    /**
     * 共享内存缓冲池。正常只有一个采集线程在用，但压测会有多个线程并发提交，
     * 同一块 ashmem 不能被同时写，所以做成小池子按需借还。
     */
    private val bufferPool = java.util.concurrent.ArrayBlockingQueue<SharedFrameBuffer>(8).apply {
        repeat(4) { put(SharedFrameBuffer()) }
    }

    // ---- IPC 统计（客户端视角）----
    val ipcCalls = AtomicLong(0)
    val ipcFailures = AtomicLong(0)
    private val ipcSumNs = AtomicLong(0)
    private val ipcMaxNs = AtomicLong(0)

    @Volatile
    var connectedPid: Int = -1
        private set

    val isConnected: Boolean get() = remote != null

    val avgIpcMs: Float
        get() = if (ipcCalls.get() > 0) ipcSumNs.get() / 1_000_000f / ipcCalls.get() else 0f

    val maxIpcMs: Float get() = ipcMaxNs.get() / 1_000_000f

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            val iface = IFaceInference.Stub.asInterface(service)
            remote = iface
            connectedPid = try {
                iface.asBinder().let { 0 }  // 占位，下面用 stats 拿真实 pid
                iface.stats.pid
            } catch (t: Throwable) {
                Log.w(TAG, "读取服务 PID 失败", t)
                -1
            }
            // binder 死亡回调：进程被 kill 时比 onServiceDisconnected 更可靠
            try {
                service.linkToDeath({
                    Log.w(TAG, "Binder 死亡回调触发")
                    remote = null
                    connectedPid = -1
                    listener?.onServiceDisconnected("推理进程已终止（binder 死亡）")
                }, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "linkToDeath 失败", t)
            }
            Log.i(TAG, "已连接推理服务 pid=$connectedPid")
            listener?.onServiceConnected(connectedPid)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected")
            remote = null
            connectedPid = -1
            listener?.onServiceDisconnected("推理服务连接断开")
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "onBindingDied")
            remote = null
            connectedPid = -1
            listener?.onServiceDisconnected("服务绑定失效")
        }
    }

    /** 绑定服务；重复调用安全 */
    fun bind() {
        if (wantBound) return
        wantBound = true
        val intent = Intent(context, InferenceService::class.java)
        try {
            val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService -> $ok")
            if (!ok) {
                wantBound = false
                listener?.onServiceDisconnected("绑定失败（服务不可用）")
            }
        } catch (t: Throwable) {
            wantBound = false
            Log.e(TAG, "bindService 异常", t)
            listener?.onServiceDisconnected("绑定异常: ${t.message}")
        }
    }

    /** 解绑，供切换到单进程模式时调用 */
    fun unbind() {
        if (!wantBound) return
        wantBound = false
        try {
            context.unbindService(conn)
        } catch (e: Exception) {
            Log.w(TAG, "unbindService 异常", e)
        }
        remote = null
        connectedPid = -1
    }

    /** 分析一帧；未连接时返回 CONNECTION_LOST */
    fun analyze(bitmap: Bitmap, requestId: Long, captureNs: Long): FaceResultParcel {
        val r = remote ?: return FaceResultParcel(
            requestId = requestId,
            status = FaceResultParcel.STATUS_ERROR,
            statusMessage = "未连接推理服务"
        )
        val buf = bufferPool.poll() ?: SharedFrameBuffer()
        try {
            return call(requestId) {
                r.analyze(buf.write(bitmap), bitmap.width, bitmap.height, requestId, captureNs)
            }
        } finally {
            if (!bufferPool.offer(buf)) buf.close()
        }
    }

    fun register(bitmap: Bitmap, name: String, requestId: Long): FaceResultParcel {
        val r = remote ?: return FaceResultParcel(
            requestId = requestId,
            status = FaceResultParcel.STATUS_ERROR,
            statusMessage = "未连接推理服务"
        )
        val buf = bufferPool.poll() ?: SharedFrameBuffer()
        try {
            return call(requestId) {
                r.register(buf.write(bitmap), bitmap.width, bitmap.height, requestId, name)
            }
        } finally {
            if (!bufferPool.offer(buf)) buf.close()
        }
    }

    fun stats(): ServiceStatsParcel? = try {
        remote?.stats
    } catch (t: Throwable) {
        Log.w(TAG, "getStats 失败", t)
        null
    }

    fun resetStats() {
        try {
            remote?.resetStats()
        } catch (t: Throwable) {
            Log.w(TAG, "resetStats 失败", t)
        }
        ipcCalls.set(0)
        ipcFailures.set(0)
        ipcSumNs.set(0)
        ipcMaxNs.set(0)
    }

    fun configure(threads: Int, permits: Int) {
        try {
            remote?.configure(threads, permits)
        } catch (t: Throwable) {
            Log.w(TAG, "configure 失败", t)
        }
    }

    fun clearDatabase() {
        try {
            remote?.clearDatabase()
        } catch (t: Throwable) {
            Log.w(TAG, "clearDatabase 失败", t)
        }
    }

    fun listNames(): List<String> = try {
        remote?.listNames() ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    /** 让推理进程自杀，用来演示断连与重连 */
    fun crashServiceForTest() {
        try {
            remote?.crashForTest()
        } catch (t: Throwable) {
            Log.w(TAG, "crashForTest 调用异常（进程可能已经死了）", t)
        }
    }

    private inline fun call(requestId: Long, block: () -> FaceResultParcel): FaceResultParcel {
        val t0 = System.nanoTime()
        return try {
            val r = block()
            val dt = System.nanoTime() - t0
            ipcCalls.incrementAndGet()
            ipcSumNs.addAndGet(dt)
            ipcMaxNs.updateAndGet { m -> maxOf(m, dt) }
            r
        } catch (t: Throwable) {
            // 典型：DeadObjectException（服务进程已死）、TransactionTooLargeException
            ipcFailures.incrementAndGet()
            Log.e(TAG, "IPC 调用失败 requestId=$requestId", t)
            FaceResultParcel(
                requestId = requestId,
                status = FaceResultParcel.STATUS_ERROR,
                statusMessage = "IPC 失败: ${t.javaClass.simpleName}"
            )
        }
    }

    override fun close() {
        unbind()
        while (true) {
            val b = bufferPool.poll() ?: break
            b.close()
        }
    }
}
