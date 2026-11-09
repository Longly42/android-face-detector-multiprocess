# android-face-detector-multiprocess

《操作系统》综合实验：**端侧多进程人脸检测 / 识别 / 活体流水线**。

Android 端完全离线运行三个深度学习模型，并围绕这一真实负载分析进程、线程、IPC、同步与 I/O 调度行为。

当前进度：**第 2 周完成**（前摄预览 + 帧转换 + SCRFD 人脸检测已跑通）。识别与活体模块、双进程 IPC、性能实验按周推进，见文末「进度」。

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
| minSdk | 26（Android 8.0） |
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

Logcat 过滤关键字：`Scrfd`、`AntiSpoof`、`FaceEmbedder`、`FaceDatabase`、`CameraDebug`。

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

---

## 四、目录结构

```
app/src/main/
├── assets/
│   ├── scrfd_500m_bnkps_shape640x640.onnx     # SCRFD-500M-KPS，输入固定 640x640
│   └── w600k_mbf_112x112.onnx                 # MobileFaceNet，输入固定 112x112
└── java/com/example/androidfacedetector/
    ├── MainActivity.kt                        # 权限、相机绑定、检测+识别调度、叠加层、注册 UI
    ├── detector/
    │   ├── Detection.kt                       # 检测结果数据结构（框/关键点/置信度/三段耗时）
    │   └── ScrfdDetector.kt                   # 预处理 → 推理 → 锚点解码 → NMS
    └── face/
        ├── FaceAligner.kt                     # 5 点相似变换对齐到 112x112
        ├── FaceEmbedder.kt                    # MobileFaceNet 特征提取 + 余弦相似度
        ├── FaceDatabase.kt                    # 特征库：注册 / 匹配 / 持久化
        └── AntiSpoofDetector.kt               # 活体检测：MiniFASNet 双模型融合
MODELS.md                                      # 模型来源、许可、输入输出规格、转换方式、阈值标定
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

- **单进程单线程推理**：推理当前运行在摄像头分析线程（`cam-analysis`）内，尚未拆分独立推理线程，也未引入 Binder IPC。双进程与 Semaphore 限流按计划在第 5 周实施。
- **帧调度为临时方案**：使用 `STRATEGY_KEEP_ONLY_LATEST` + `AtomicBoolean` 防重入，语义等价于「在途任务数 = 1」，尚未使用信号量。
- **每帧全量推理**：无跳帧策略，真机帧率约 13–25 FPS。
- **APK 体积 105 MB**：ONNX Runtime 默认打包 4 种 ABI 的 so，加上四个模型共约 19 MB。发布版可通过 `abiFilters` 仅保留 `arm64-v8a` 降至约 45 MB。
- **每帧都做"检测 + 对齐 + 特征"**：识别模型每帧重算，耗时随之增加。第 7 周会做跳帧与缓存对照实验。
- **对齐在 Kotlin 层逐像素采样**：模拟器上 7~20 ms，真机会明显更快，但仍是可优化点（可改 RenderScript/NEON 或直接交给 OpenCV）。
- **未做量化**：当前为 FP32 模型，INT8 / FP16 与线程数对照实验在第 7 周进行。
- **模拟器无真人脸**：虚拟摄像头为合成场景，检测结果恒为 0，属预期行为。

---

## 七、进度

| 周次 | 任务 | 状态 |
|---|---|---|
| 1–2 | 前摄采集、格式转换、SCRFD 检测与结果展示 | 完成 |
| 3 | 5 点对齐 + 特征提取 + 注册 / 识别 / 拒识 | 完成 |
| 4 | 活体检测 + 三模型串联流水线 | 完成 |
| 5 | AIDL 双进程 + Executor + Semaphore + 调试页 | 待开始 |
| 6 | 摄像头 I/O 事件链埋点 + Trace 分析 | 待开始 |
| 7 | 量化 / 线程数 / 跳帧对照实验 | 待开始 |
| 8 | 清理、打包、报告与答辩材料 | 待开始 |
