# android-face-detector-multiprocess

《操作系统》综合实验：**端侧多进程人脸检测 / 识别 / 活体流水线**。

Android 端完全离线运行三个深度学习模型，并围绕这一真实负载分析进程、线程、IPC、同步与 I/O 调度行为。

当前进度：**第 7 周完成**。三项 AI 功能、双进程 Binder 架构、信号量限流、调试页、事件链埋点与优化对照实验均已实现并实测，见 [`EXPERIMENT.md`](EXPERIMENT.md)。

---

## 一、环境要求

| 项 | 版本 / 说明 |
|---|---|
| Android Studio | Narwhal 或更新（需支持 AGP 9.x） |
| JDK | 17+（推荐使用 Android Studio 内置 JBR） |
| Gradle | 由 Wrapper 提供，无需单独安装；**不建议**用离线模式首次构建 |
| Android Gradle Plugin | 9.5.1 |
| Kotlin | 2.2.10 |
| compileSdk / targetSdk | 37 |
| minSdk | 27（Android 8.1，跨进程传帧使用 `SharedMemory`） |
| 设备 | 需带**前置摄像头**。真机优先；模拟器可验证流程，但虚拟摄像头无真人脸，检测结果恒为 0 |

首次构建需要联网下载依赖（主要是 ONNX Runtime Android，约 20 MB）。

---

## 二、构建与运行

```bash
git clone https://github.com/Longly42/android-face-detector-multiprocess.git
cd android-face-detector-multiprocess

# Windows
gradlew.bat :app:assembleDebug
# macOS / Linux
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

安装并运行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.androidfacedetector/.MainActivity
```

首次启动需授予**相机权限**。授予后立即开始前置摄像头预览与逐帧检测。

> 模型文件已随仓库提交（`app/src/main/assets/`），clone 后无需额外下载即可运行。

---

## 三、当前功能

- 前置摄像头预览（CameraX `Preview` + `ImageAnalysis` 双 UseCase 生命周期绑定）
- YUV_420_888 帧按 `rotationDegrees` 转正 + 前摄水平镜像，输出与预览视觉一致的 `Bitmap`
- SCRFD-500M-KPS 逐帧人脸检测：边界框、5 个关键点、置信度
- 检测结果经 Canvas 按 `FIT_CENTER` 等比换算叠加到预览画面
- 左上角信息面板：FPS、人脸数、最高置信度、预处理 / 推理 / 后处理三段耗时
- Logcat 打印模型体检信息（输入输出名称与形状），便于排查。

Logcat 过滤关键字：`Scrfd`、`AntiSpoof`、`FaceEmbedder`、`FaceDatabase`、`FaceClient`、`InferenceService`、`Metrics`、`CameraDebug`。

### 人脸注册与识别

- 5 点对齐：按 ArcFace 标准模板做相似变换 + 双线性采样，输出 112×112 摆正人脸
- MobileFaceNet 提取 512 维特征并 L2 归一化，余弦相似度比对，阈值 0.5
- 底部注册区：输入姓名 → 点"注册当前人脸"，把画面中最大那张脸存进特征库
- 特征库只存向量不存照片，持久化在应用私有目录 `filesDir/faces.bin`，可一键清空
- 识别结果叠加在画面：绿框 + 姓名 + 相似度；未过阈值显示"未知"，库为空时不报错

阈值标定与相似度实测数据见 [`MODELS.md`](MODELS.md)。

### 活体检测（三模型串联流水线）

- MiniFASNetV2（scale 2.7）+ MiniFASNetV1SE（scale 4.0）双模型融合，判定沿用官方 argmax==1 规则
- 活体未通过时直接短路：不做对齐和特征提取，翻拍的脸不允许进入注册/识别环节
- 颜色语义：活体通过且识别到 = 绿框；活体通过未识别 = 橙框；判为翻拍 = 红框 + "疑似翻拍"
- 官方三张样例图判定全部与官方一致，端侧与 PC 端分数差 < 0.002（见 MODELS.md 标定表）

### 双进程架构与系统状态页

- 主进程负责采集与 UI，`:inference` 进程负责三个模型与特征库，通过 AIDL + Binder 通信
- 帧数据走 **共享内存（ashmem）**：Binder 事务体里只有一个 fd，不走 1.2MB 的像素拷贝
- 推理进程用 **线程池 + Semaphore** 控制并发额度，取不到许可立即返回 DROPPED 作为背压信号
- 右上角「调试」进入系统状态页，实时显示两个进程的 PID、线程列表与 tid、IPC 调用次数与时延、
  可用许可/在途任务/队列长度/已处理/丢弃/超时、平均与最大排队、单帧耗时
- 状态页可在线切换：单进程/双进程、线程池 1/2/4、许可数 1/2/4、检测分辨率 640/320、跳帧 1/2/4，
  并提供「并发压测」「崩溃服务」「导出CSV」用于实验与故障验证

### 实验数据采集

```bash
# 帧级数据（每帧一行，含各阶段耗时与状态）
adb shell run-as com.example.androidfacedetector ls files/metrics/
adb exec-out run-as com.example.androidfacedetector cat files/metrics/xxx.csv > data.csv
python tools/analyze_metrics.py data.csv

# 系统 Trace（自定义埋点：frame.convert / ipc.analyze / infer.* / svc.*）
adb shell atrace -t 5 -a com.example.androidfacedetector sched view gfx binder_driver -b 8192 > trace.txt
python tools/analyze_trace.py trace.txt
```

---

## 四、目录结构

```
app/src/main/
├── aidl/com/example/androidfacedetector/ipc/
│   ├── IFaceInference.aidl                    # 主进程 -> 推理进程的 Binder 接口
│   ├── FaceResultParcel.aidl
│   └── ServiceStatsParcel.aidl
├── assets/
│   ├── scrfd_500m_bnkps_shape640x640.onnx     # 检测，输入 640（默认）
│   ├── scrfd_500m_bnkps_shape320x320.onnx     # 检测，输入 320（分辨率对照实验）
│   ├── antispoof_minifasnetv2_80x80.onnx      # 活体，输入 80（scale 2.7）
│   ├── antispoof_minifasnetv1se_80x80.onnx    # 活体，输入 80（scale 4.0）
│   └── w600k_mbf_112x112.onnx                 # 识别，输入 112
└── java/com/example/androidfacedetector/
    ├── MainActivity.kt                        # 权限、相机绑定、帧调度、叠加层、注册 UI
    ├── DebugScreen.kt                         # 系统状态/调试覆盖层（PID/TID/队列/许可/耗时）
    ├── FaceApp.kt                             # 全局 EngineRouter 持有者
    ├── detector/
    │   ├── Detection.kt                       # 检测结果数据结构
    │   └── ScrfdDetector.kt                   # 预处理 → 推理 → 锚点解码 → NMS
    ├── face/
    │   ├── FaceAligner.kt                     # 5 点相似变换对齐到 112x112
    │   ├── FaceEmbedder.kt                    # MobileFaceNet 特征提取 + 余弦相似度
    │   ├── FaceDatabase.kt                    # 特征库：注册 / 匹配 / 持久化
    │   └── AntiSpoofDetector.kt               # 活体检测：MiniFASNet 双模型融合
    ├── inference/
    │   ├── InferenceEngine.kt                 # 三模型 + 特征库（单/双进程共用）
    │   ├── InferenceService.kt                # :inference 进程的 Binder 服务 + 线程池 + 信号量
    │   └── EngineRouter.kt                    # 单进程/双进程切换、跳帧、配置标签
    ├── ipc/
    │   ├── FaceClient.kt                      # 主进程侧 Binder 客户端（连接/重连/IPC 统计）
    │   ├── SharedFrameBuffer.kt               # ashmem 帧缓冲
    │   ├── FaceResultParcel.kt                # 手写 Parcelable
    │   └── ServiceStatsParcel.kt
    └── perf/
        └── Metrics.kt                          # 时延统计（avg/P50/P95）与帧事件记录/CSV 导出
tools/
├── analyze_metrics.py                          # 解析帧级 CSV，按模式/状态输出 P50/P95
└── analyze_trace.py                            # 解析 atrace，统计埋点耗时并还原单帧事件链
MODELS.md                                       # 模型来源、许可、规格、转换方式、阈值标定
EXPERIMENT.md                                   # 实验报告：架构、IPC、信号量、事件链、优化对照
```

`app/src/androidTest/` 下有一个端侧一致性测试，用于核对 Kotlin 实现与 PC 端 Python
参考实现的输出是否一致。测试图片不入库，需自备放入 `androidTest/assets/t1.jpg`，
缺图时自动跳过。

---

## 五、模型

三个模型均来自 InsightFace 官方发布的开放模型包，许可允许再分发。详细来源链、校验信息与输入输出规格见 [`MODELS.md`](MODELS.md)。

| 模型 | 用途 | 体积 | 状态 |
|---|---|---|---|
| SCRFD-500M-KPS | 人脸检测 + 5 关键点 | 2.5 MB | 已集成 |
| MobileFaceNet (`w600k_mbf`) | 人脸特征提取（512 维） | 13.6 MB | 已集成 |
| MiniFASNetV2 + V1SE | 活体检测（双模型融合） | 1.74 MB ×2 | 已集成 |

---

## 六、已知限制

- **性能数据来自模拟器**（x86_64 + 软件渲染），不能代表真机；真机需按 `EXPERIMENT.md` 第 7.1 节条件重测。
- **精度未量化评估**：缺少带标注测试集，识别与活体只有定性结论与阈值标定数据。
- **单相机场景下 Semaphore 不会自然触发**：CameraX 的 Analyzer 是串行回调，天然只有 1 帧在途；
  压测数据来自调试页的并发压测入口，与真实使用存在差距。
- **检测分辨率 320 有精度代价**：最小可检人脸尺寸翻倍，远处小脸会漏检，未做检出率对比。
- **帧转换是当前最大瓶颈**：`ImageProxy.toBitmap()` 是纯 Java 实现，模拟器上约 48ms（占端到端一半）。
- **特征库为单文件二进制**：设计上保证同一时刻只有一个引擎实例持有，未做文件锁。
- **APK 体积**：debug 包 105 MB（ONNX Runtime 含 4 种 ABI 的 so + 19 MB 模型）；release 包已用 `abiFilters` 裁到 `arm64-v8a`，为 **48.3 MB**（见 `app/build.gradle.kts`）。
- **模拟器无真人脸**：虚拟摄像头为合成场景，检测结果恒为 0，属预期行为。

---

## 七、进度

| 周次 | 任务 | 状态 |
|---|---|---|
| 1–2 | 前摄采集、格式转换、SCRFD 检测与结果展示 | 完成 |
| 3 | 5 点对齐 + 特征提取 + 注册 / 识别 / 拒识 | 完成 |
| 4 | 活体检测 + 三模型串联流水线 | 完成 |
| 5 | AIDL 双进程 + Executor + Semaphore + 调试页 | 完成 |
| 6 | 摄像头 I/O 事件链埋点 + Trace 分析 | 完成 |
| 7 | 分辨率 / 跳帧 / 线程数对照实验 | 完成（模拟器数据） |
| 8 | 清理、打包、报告与答辩材料 | 报告完成，PPT 另做 |
