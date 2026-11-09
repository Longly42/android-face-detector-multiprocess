# 模型说明

本项目所有推理均在设备端离线完成，不依赖云端接口。

## 已集成

### 人脸检测：SCRFD-500M-KPS


| 项 | 内容 |
|---|---|
| 文件 | `app/src/main/assets/scrfd_500m_bnkps_shape640x640.onnx` |
| 体积 | 2.52 MB（2,524,817 字节） |
| 来源 | InsightFace 官方模型库 `buffalo_s.zip`（release v0.7）中的 `det_500m.onnx` |
| 下载地址 | https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_s.zip |
| 输入 | `input.1`，`1 × 3 × 640 × 640`，float32，RGB，归一化 `(x - 127.5) / 128` |
| 输出 | 9 个张量：`(12800/3200/800) × (1 分数 / 4 框偏移 / 10 关键点)` |
| 结构 | stride 8/16/32 三个尺度，每个位置 2 个锚点，共 5 个人脸关键点 |
| 许可 | 模型版权归 InsightFace 项目所有，仅用于课程实验，不再分发 |

原始模型输入为动态尺寸，本项目将其固定为 640×640 以便端侧按固定张量推理。
转换前后在同一输入上的输出最大差异为 1.43e-06，可视为等价。

转换方式（`onnx` 库直接改写图的输入维度，不改变任何算子）：

```python
import onnx
m = onnx.load("det_500m.onnx")
dims = m.graph.input[0].type.tensor_type.shape.dim
dims[2].dim_value = 640
dims[3].dim_value = 640
onnx.save(m, "scrfd_500m_bnkps_shape640x640.onnx")
```

### 人脸识别：MobileFaceNet (w600k_mbf)

| 项 | 内容 |
|---|---|
| 文件 | `app/src/main/assets/w600k_mbf_112x112.onnx` |
| 体积 | 13.62 MB（13,616,099 字节） |
| 来源 | InsightFace 官方模型库 `buffalo_s.zip`（release v0.7）中的 `w600k_mbf.onnx` |
| 下载地址 | https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_s.zip |
| 输入 | `input.1`，`1 × 3 × 112 × 112`，float32，RGB，归一化 `(x - 127.5) / 127.5` |
| 输出 | `516`，`1 × 512` 特征向量 |
| 对齐模板 | ArcFace 标准 5 点（`FaceAligner.REF`），相似变换，双线性采样 |
| 判别方式 | L2 归一化后取点积 = 余弦相似度，阈值 0.5 |
| 许可 | 模型版权归 InsightFace 项目所有，仅用于课程实验，不再分发 |

原始模型输入为动态 batch `[?, 3, 112, 112]`，本项目固定为 `1 × 3 × 112 × 112`，
固定前后同一输入输出最大差异为 0（完全一致）。

**阈值标定依据**（`t1.jpg` 六人合照，PC 与端侧结果一致）：

| 情形 | 余弦相似度实测 |
|---|---|
| 同一人，原图自比对 | 1.000 |
| 同一人，旋转 ±5°~8° | 0.94 ~ 0.97 |
| 同一人，亮度 ×0.6 / ×1.4 | 0.86 ~ 0.98 |
| 同一人，轻度高斯模糊 | 0.93 ~ 0.98 |
| 不同人，共 15 组两两比对 | -0.04 ~ 0.23 |

同人与不同人之间存在 0.23 ~ 0.86 的明显间隔，故取阈值 **0.5**（偏严，宁可拒识不误识）。

### 活体检测：MiniFASNetV2 + MiniFASNetV1SE（双模型融合）

| 项 | 内容 |
|---|---|
| 文件 | `app/src/main/assets/antispoof_minifasnetv2_80x80.onnx`（1.74 MB）、`antispoof_minifasnetv1se_80x80.onnx`（1.74 MB） |
| 来源 | minivision-ai/Silent-Face-Anti-Spoofing（MIT 许可），`resources/anti_spoof_models/` 下的 `2.7_80x80_MiniFASNetV2.pth` 与 `4_0_0_80x80_MiniFASNetV1SE.pth` |
| 地址 | https://github.com/minivision-ai/Silent-Face-Anti-Spoofing |
| 输入 | `input`，`1 × 3 × 80 × 80`，float32，**BGR、0~255 原始亮度（不归一化）** |
| 输出 | `logits`，3 类 → softmax，官方约定 argmax==1 为真脸 |
| 融合 | 两个模型的 softmax 概率逐类相加后取 argmax，分数除以模型数 |
| 裁剪 | 按模型文件名里的 scale（2.7 / 4.0）以人脸框中心向外扩，resize 到 80×80，用 cv2 语义的 INTER_LINEAR（中心对齐、无抗锯齿） |
| 许可 | MIT（代码），模型权重随工程发布，仅用于课程实验 |

**三个最容易踩的坑**（都已实测踩过）：

1. **输入不归一化**。官方 `src/data_io/functional.py` 的 `to_tensor` 被改为不除 255
   （源码注释 "modify by zkx"）。按 0~1 输入时官方样例三张全判错；按 0~255 输入后
   官方样例判定全部与官方一致。
2. **裁剪缩放不能用平台默认双线性**。Android `createScaledBitmap(filter=true)` 与
   PIL 的 BILINEAR（自带抗锯齿）行为不同，会显著改变模型置信度。必须手写
   cv2 的中心对齐 INTER_LINEAR：`src = (dst + 0.5) * srcSize / dstSize - 0.5`。
3. **双模型融合必须累加**。softmax 结果逐类相加后 argmax；若后一个模型覆盖前一个，
   分数会大幅偏离（实测 0.998 → 0.497）。

**导出方式**：`torch.onnx.export(..., opset_version=11, dynamo=False)`。
注意 torch 2.9+ 默认的 dynamo 导出器产出 opset 18 / IR 10 的模型，在
ORT Android 1.20 上以字节流方式加载会报 `model_path must not be empty`
（optimizer 阶段初始化 TensorProto 走了文件路径分支），改用传统 TorchScript
导出器（opset 11 / IR 6，与本项目另两个模型一致）后正常。

**阈值标定**（官方样例 + 实拍，PC 与端侧一致）：

| 样例 | 官方预期 | 判定 | score |
|---|---|---|---|
| image_T1.jpg（真脸） | Real | REAL | 0.998 |
| image_F1.jpg（打印照片攻击） | Fake | FAKE | 0.724 |
| image_F2.jpg（屏幕翻拍攻击） | Fake | FAKE | 0.996 |

判定规则直接沿用官方：argmax==1 为真脸，无额外阈值；两次类别"翻转"
（0/2）都算假脸，这比二分类阈值更保守。

## 计划接入

| 用途 | 模型 | 备注 |
|---|---|---|
| 人脸识别 | EdgeFace-XXS（首选）或 MobileFaceNet `w600k_mbf.onnx` | `w600k_mbf.onnx` 13.6 MB 已随 `buffalo_s` 一同获取 |
| 活体检测 | MiniFASNetV2 | 来自 Silent-Face-Anti-Spoofing 工程，需另行转换 |
