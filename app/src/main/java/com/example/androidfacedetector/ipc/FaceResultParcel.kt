package com.example.androidfacedetector.ipc

import android.os.Parcel
import android.os.Parcelable

/**
 * 推理服务返回的一帧分析结果。跨进程传递，必须 Parcelable。
 *
 * 手写 Parcelable（不用 kotlin-parcelize：AGP 9 内置 Kotlin 下该插件不可用）。
 * 字段顺序必须在 writeToParcel 与 Parcel 构造函数里严格一致。
 *
 * 字段分三组：
 *  - 业务结果：人脸数、框、关键点、活体、身份
 *  - 耗时分解：检测三段、活体、对齐、特征
 *  - 进程证据：请求编号、服务端 PID/TID、服务端收发时间戳、排队等待
 */
data class FaceResultParcel(
    val requestId: Long = 0,
    val status: Int = STATUS_OK,
    val statusMessage: String = "",
    val faceCount: Int = 0,
    val score: Float = 0f,
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val landmarks: FloatArray = FloatArray(0),
    val isReal: Boolean = true,
    val livenessScore: Float = 0f,
    val name: String? = null,
    val similarity: Float = 0f,
    val detectPreMs: Float = 0f,
    val detectInferMs: Float = 0f,
    val detectPostMs: Float = 0f,
    val spoofMs: Float = 0f,
    val alignMs: Float = 0f,
    val embedMs: Float = 0f,
    val serviceReceiveNs: Long = 0,
    val serviceDoneNs: Long = 0,
    val servicePid: Int = 0,
    val serviceTid: Int = 0,
    val queueWaitMs: Float = 0f
) : Parcelable {

    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
    val hasFace: Boolean get() = faceCount > 0

    /** 服务端内部处理耗时（不含排队、不含 IPC 往返） */
    val serviceWorkMs: Float
        get() = if (serviceDoneNs > serviceReceiveNs)
            (serviceDoneNs - serviceReceiveNs) / 1_000_000f else 0f

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(requestId)
        dest.writeInt(status)
        dest.writeString(statusMessage)
        dest.writeInt(faceCount)
        dest.writeFloat(score)
        dest.writeFloat(x1)
        dest.writeFloat(y1)
        dest.writeFloat(x2)
        dest.writeFloat(y2)
        dest.writeFloatArray(landmarks)
        dest.writeByte(if (isReal) 1 else 0)
        dest.writeFloat(livenessScore)
        dest.writeString(name)
        dest.writeFloat(similarity)
        dest.writeFloat(detectPreMs)
        dest.writeFloat(detectInferMs)
        dest.writeFloat(detectPostMs)
        dest.writeFloat(spoofMs)
        dest.writeFloat(alignMs)
        dest.writeFloat(embedMs)
        dest.writeLong(serviceReceiveNs)
        dest.writeLong(serviceDoneNs)
        dest.writeInt(servicePid)
        dest.writeInt(serviceTid)
        dest.writeFloat(queueWaitMs)
    }

    private constructor(p: Parcel) : this(
        requestId = p.readLong(),
        status = p.readInt(),
        statusMessage = p.readString() ?: "",
        faceCount = p.readInt(),
        score = p.readFloat(),
        x1 = p.readFloat(),
        y1 = p.readFloat(),
        x2 = p.readFloat(),
        y2 = p.readFloat(),
        landmarks = p.createFloatArray() ?: FloatArray(0),
        isReal = p.readByte().toInt() != 0,
        livenessScore = p.readFloat(),
        name = p.readString(),
        similarity = p.readFloat(),
        detectPreMs = p.readFloat(),
        detectInferMs = p.readFloat(),
        detectPostMs = p.readFloat(),
        spoofMs = p.readFloat(),
        alignMs = p.readFloat(),
        embedMs = p.readFloat(),
        serviceReceiveNs = p.readLong(),
        serviceDoneNs = p.readLong(),
        servicePid = p.readInt(),
        serviceTid = p.readInt(),
        queueWaitMs = p.readFloat()
    )

    companion object {
        const val STATUS_OK = 0
        const val STATUS_DROPPED = 1      // 信号量已满，主动丢弃（背压）
        const val STATUS_TIMEOUT = 2      // 等待推理超时
        const val STATUS_ERROR = 3        // 推理/IPC 异常
        const val STATUS_NO_FACE = 4      // 帧里没有人脸

        @JvmField
        val CREATOR = object : Parcelable.Creator<FaceResultParcel> {
            override fun createFromParcel(parcel: Parcel) = FaceResultParcel(parcel)
            override fun newArray(size: Int) = arrayOfNulls<FaceResultParcel>(size)
        }
    }

    /** 只按请求编号比较：每帧一条结果，便于 Compose 判断是否更新 */
    override fun equals(other: Any?): Boolean =
        this === other || (other is FaceResultParcel && other.requestId == requestId)

    override fun hashCode(): Int = requestId.hashCode()
}
