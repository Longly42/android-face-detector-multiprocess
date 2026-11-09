package com.example.androidfacedetector.ipc

import android.os.Parcel
import android.os.Parcelable

/**
 * 推理服务运行状态快照，给调试页用。
 *
 * 关于信号量的措辞（答辩会问）：这是**进程内**的 java.util.concurrent.Semaphore，
 * 只约束推理进程内部的并发额度，不是跨进程共享信号量。跨进程的协作由 Binder
 * 同步调用本身承担，二者不是一回事。
 */
data class ServiceStatsParcel(
    val pid: Int = 0,
    val totalRequests: Long = 0,
    val processed: Long = 0,
    val dropped: Long = 0,
    val timeouts: Long = 0,
    val errors: Long = 0,
    val inFlight: Int = 0,
    val availablePermits: Int = 0,
    val maxPermits: Int = 0,
    val queueLength: Int = 0,
    val poolSize: Int = 0,
    val activeThreads: Int = 0,
    val avgQueueWaitMs: Float = 0f,
    val maxQueueWaitMs: Float = 0f,
    val avgWorkMs: Float = 0f,
    val dbSize: Int = 0,
    val threadNames: List<String> = emptyList()
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(pid)
        dest.writeLong(totalRequests)
        dest.writeLong(processed)
        dest.writeLong(dropped)
        dest.writeLong(timeouts)
        dest.writeLong(errors)
        dest.writeInt(inFlight)
        dest.writeInt(availablePermits)
        dest.writeInt(maxPermits)
        dest.writeInt(queueLength)
        dest.writeInt(poolSize)
        dest.writeInt(activeThreads)
        dest.writeFloat(avgQueueWaitMs)
        dest.writeFloat(maxQueueWaitMs)
        dest.writeFloat(avgWorkMs)
        dest.writeInt(dbSize)
        dest.writeStringList(threadNames)
    }

    private constructor(p: Parcel) : this(
        pid = p.readInt(),
        totalRequests = p.readLong(),
        processed = p.readLong(),
        dropped = p.readLong(),
        timeouts = p.readLong(),
        errors = p.readLong(),
        inFlight = p.readInt(),
        availablePermits = p.readInt(),
        maxPermits = p.readInt(),
        queueLength = p.readInt(),
        poolSize = p.readInt(),
        activeThreads = p.readInt(),
        avgQueueWaitMs = p.readFloat(),
        maxQueueWaitMs = p.readFloat(),
        avgWorkMs = p.readFloat(),
        dbSize = p.readInt(),
        threadNames = p.createStringArrayList() ?: emptyList()
    )

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<ServiceStatsParcel> {
            override fun createFromParcel(parcel: Parcel) = ServiceStatsParcel(parcel)
            override fun newArray(size: Int) = arrayOfNulls<ServiceStatsParcel>(size)
        }
    }
}
