package com.example.androidfacedetector.ipc;

import android.os.SharedMemory;
import com.example.androidfacedetector.ipc.FaceResultParcel;
import com.example.androidfacedetector.ipc.ServiceStatsParcel;

/**
 * 主进程（UI/摄像头） -> 推理进程（:inference）的 Binder 接口。
 *
 * 帧数据不走 Binder 事务体：主进程把 ARGB 像素写进 ashmem（SharedMemory），
 * 只把这个 Parcelable 描述符交给服务端（Parcel 内部只传一个 fd），
 * 服务端 mapReadOnly() 后直接从同一块物理页读取，避免 1.2MB/帧的数据拷贝。
 */
interface IFaceInference {

    /** 分析一帧：检测 -> 活体 -> 识别 */
    FaceResultParcel analyze(
            in SharedMemory frame,
            int width,
            int height,
            long requestId,
            long captureNs);

    /** 用这一帧的人脸特征注册一个新身份 */
    FaceResultParcel register(
            in SharedMemory frame,
            int width,
            int height,
            long requestId,
            String name);

    /** 服务端运行状态：PID、请求计数、信号量、队列、时延 */
    ServiceStatsParcel getStats();

    void resetStats();

    /**
     * 运行期改配置。
     * @param threads 推理线程池大小（1/2/4）
     * @param permits 同时允许的在途任务上限（含排队）
     */
    void configure(int threads, int permits);

    /** 切换检测输入分辨率（640 / 320），第 7 周模型侧对照实验 */
    void setDetectResolution(int size);

    void clearDatabase();

    List<String> listNames();

    /** 故意让服务进程崩溃，用于验证断连提示与重连（演示用） */
    void crashForTest();
}
