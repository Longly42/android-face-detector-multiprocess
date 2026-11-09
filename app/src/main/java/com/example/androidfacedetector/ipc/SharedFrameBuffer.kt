package com.example.androidfacedetector.ipc

import android.graphics.Bitmap
import android.os.SharedMemory
import java.io.Closeable
import java.nio.ByteOrder

/**
 * 跨进程传帧用的共享内存（ashmem）缓冲区。
 *
 * 为什么不把像素塞进 Binder：Binder 单次事务体上限约 1MB，而 640x480 的 ARGB
 * 一帧就是 1.2MB，直接塞会 TransactionTooLargeException；即使塞得下，
 * 内核也要整块拷贝两次。改为「共享内存 + 只传描述符」后，Binder 事务体里
 * 只是一个 fd，像素由两端直接读写同一块物理页。
 *
 * 缓冲区复用：只在尺寸变大时重建，避免每帧 create/close ashmem。
 */
class SharedFrameBuffer : Closeable {

    private var shm: SharedMemory? = null
    private var capacity = 0

    /** 最近一次分配大小，调试用 */
    val size: Int get() = capacity

    /** 把 Bitmap 写进共享内存，返回可跨进程传递的描述符 */
    fun write(bitmap: Bitmap): SharedMemory {
        val need = bitmap.byteCount
        if (shm == null || capacity < need) {
            shm?.close()
            shm = SharedMemory.create("face-frame", need)
            capacity = need
        }
        val s = shm!!

        // 注意：不要用 setProtect 把区域降成 PROT_READ 后再改回 PROT_READ|PROT_WRITE，
        // Android 10+ 的 ashmem/memfd 会拒绝这种"再提权"，实测报 mmap EPERM。
        // 保持默认可读写，服务端只调用 mapReadOnly()（语义上只读）。
        val mapped = s.mapReadWrite()
        try {
            mapped.order(ByteOrder.nativeOrder())
            mapped.rewind()
            bitmap.copyPixelsToBuffer(mapped)
        } finally {
            SharedMemory.unmap(mapped)
        }
        return s
    }

    override fun close() {
        shm?.close()
        shm = null
        capacity = 0
    }
}
