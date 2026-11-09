# 实验要求 → 实现对照表

> 用途：答辩/写 PPT 时的索引，一眼看清"哪条要求对应哪段代码、怎么验证的"。
> 工程：`E:\Android-code` · 仓库：https://github.com/Longly42/android-face-detector-multiprocess

---

## 一、要求拆成三类

| 大类 | 要求要的东西 | 说白了是什么 |
|---|---|---|
| **A. 功能** | 三项 AI 能力 + 交互 | 能不能真的检测、认人、防照片 |
| **B. 操作系统机制** | 进程、线程、信号量、I/O 事件链 | 这些东西在系统里怎么跑、怎么被观察 |
| **C. 工程交付** | Git 历史、环境说明、报告、原始数据 | 别人能不能复现你的结论 |

---

## 二、A 类：三项 AI 功能

| 要求原文要点 | 实现 | 位置 | 验证方式 |
|---|---|---|---|
| 前摄采集，处理权限/旋转/镜像/生命周期 | CameraX `Preview` + `ImageAnalysis` 双 UseCase；按 `rotationDegrees` 转正、前摄镜像；分析线程随界面销毁 | `MainActivity.kt` | 屏幕缩略图与预览方向一致 |
| 部署真实深度学习检测模型 | SCRFD-500M-KPS，2.52MB ONNX，ORT 1.20 | `detector/ScrfdDetector.kt` | 启动日志打印 9 个输出的名字与形状 |
| 输入预处理 | letterbox 等比缩放 + `(x-127.5)/128` + NCHW 三平面 | `ScrfdDetector.preprocess` | 先用 Python 同算法复现，再移植 Kotlin |
| 解码 + 置信度筛选 + NMS + 绘制 | 3 尺度 × 3 输出 = 9 张量，锚点距离解码，IoU 0.4 的 NMS | `ScrfdDetector.decode/nms` | 绿框与 5 个红点位置与 PC 端一致 |
| 展示检测框/关键点/置信度/延迟 | 叠加层 + HUD（FPS、人数、置信度、三段耗时） | `MainActivity.kt` | 真机出框 |
| 人脸注册与识别 | MobileFaceNet（13.62MB，112×112，512 维）+ 余弦匹配，阈值 0.5 | `face/FaceEmbedder.kt`、`FaceDatabase.kt` | 端侧一致性测试；同人 0.86~0.98 / 不同人 -0.04~0.23 |
| 5 点对齐 | 相似变换到 112×112 模板，二维闭式解（不用 SVD）+ 双线性采样 | `face/FaceAligner.kt` | 与 PC 端 SVD 版差 1e-15；对齐图肉眼核验 |
| 活体检测 | MiniFASNetV2 + V1SE 双模型融合，各 1.74MB | `face/AntiSpoofDetector.kt` | 官方三张样例判定全对，端侧与 PC 分数差 < 0.002 |
| 三模块融合流水线 | 检测 → 活体 →（通过才）识别，翻拍短路 | `inference/InferenceEngine.kt` | 手机翻拍：红框"疑似翻拍"，识别行消失 |
| 异常处理 | 无权限、模型加载失败、服务崩溃重连、切后台/旋屏 | 各处 | 崩溃服务实测 1.6s 重连 |

踩坑（可作"失败案例"讲）：活体模型输入不归一化（0~255 + BGR）、双模型融合写成覆盖而非累加、`createScaledBitmap` 与 cv2 缩放语义不同。

---

## 三、B 类：操作系统机制

| 要求原文要点 | 实现 | 说明 |
|---|---|---|
| 双进程 + Binder IPC | 主进程做采集与 UI，`:inference` 进程装模型；AIDL 接口 `IFaceInference` | 推理挂了不影响界面，界面挂了不影响推理 |
| 大数据跨进程传输 | 像素写入 **SharedMemory（ashmem）**，事务体只传一个 fd | 一帧 1.2MB，超 Binder 1MB 上限 |
| 线程区分：UI / 采集 / 推理 | `main`、`cam-analysis-0/1/2`、`infer-0/1`（可配 1/2/4） | 三类活三种线程，均已命名 |
| Executor 线程池 | 推理进程 `ThreadPoolExecutor`，线程名 `infer-N` | 不在每帧新建线程 |
| Semaphore 限流 + 异常释放 | 许可数 = 在途上限，取不到即返回 DROPPED；`finally` 归还 | 压测许可=1 时提交 8 帧 → 完成 3、丢弃 5 |
| 调试/状态页 | 覆盖层展示两进程 PID、线程列表与 TID、许可、在途、队列、丢弃/超时、avg/P50/P95 | 覆盖层而非独立 Activity，否则相机停帧、数据归零 |
| I/O 事件链埋点 | `frame.convert`、`ipc.analyze`、`infer.detect/spoof/align/embed`、`svc.readFrame` | 每段打时间戳，可还原单帧时间线 |
| Trace 分析 | `atrace -a 包名 sched view gfx binder_driver` + `tools/analyze_trace.py` | 埋点被系统 Trace 捕获 |

**关键结论**：`ipc.analyze` 31.83ms ≈ `infer.detect` 31.75ms，跨进程只花 0.03ms；瓶颈是帧转换（80ms，占端到端一半），不是 IPC。

---

## 四、C 类：实验数据

| 配置 | 样本 | 端到端 avg | P95 | 检测推理 |
|---|---|---|---|---|
| 双进程 / 640 / 不跳帧（基线） | 264 | 95.43 | 142.87 | 27.81 |
| 双进程 / **320** / 不跳帧（模型侧） | 384 | **65.54** | 108.84 | **8.61** |
| 双进程 / 640 / **跳帧2**（系统侧） | 265 | **76.30** | **113.41** | 24.75 |
| **单进程** / 640 / 不跳帧（进程对照） | 236 | 106.43 | 130.13 | 21.78 |

结论：分辨率 640→320 使检测推理快 3.2 倍、端到端 -31%；跳帧 2 使端到端 -20%、P95 -21%（价值在稳定性）；双进程平均快 10% 但方差更大。

**注意**：以上均为模拟器（x86_64 + 软件渲染）数据，真机需按 `EXPERIMENT.md` 第 7.1 节条件重测。

---

## 五、C 类：交付物

| 要求 | 现状 |
|---|---|
| 源码 + 模型 | 71 个文件，3366 行 Kotlin；模型随仓库分发 |
| Git 有效历史 | 14 个提交，按真实演进切分，每个都能编译 |
| 环境与运行说明 | `README.md` |
| 模型来源与许可 | `MODELS.md`（含转换脚本、输入输出规格、阈值标定） |
| 实验报告 | `EXPERIMENT.md`（9 章） |
| 原始数据 | 设备 `files/metrics/` 的 CSV + `tools/` 两个分析脚本 |
| APK | release 48.3MB（仅 arm64）/ debug 105MB（四 ABI） |

---

## 六、答辩开场（三句话）

> 我们做了一个端侧三模型流水线：SCRFD 检测、MiniFASNet 活体、MobileFaceNet 识别，全部离线跑在手机上。
> 围绕这条真实负载，把推理拆到独立进程，用 Binder + 共享内存传帧、用信号量做背压，并做调试页和 atrace 埋点来观测。
> 通过事件链分析发现瓶颈其实是帧转换而不是 IPC 或推理，据此做分辨率与跳帧两组对照，端到端降了 31%。

三句话分别对应：功能 → 机制 → 数据驱动优化。
