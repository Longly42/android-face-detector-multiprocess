package com.example.androidfacedetector.detector

/**
 * 一张人脸的检测结果（坐标都在"摆正后的原图"坐标系里，单位是像素）。
 */
class Detection(
    val score: Float,          // 置信度 0~1
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val landmarks: FloatArray  // 5 个关键点，共 10 个数：[x0,y0, x1,y1, ...] 左眼、右眼、鼻尖、左嘴角、右嘴角
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
}

/**
 * 一次检测的完整返回：结果 + 三个阶段的耗时（毫秒）。
 * 这三个数字后面要写进实验报告，所以从一开始就分开记。
 */
class DetectResult(
    val detections: List<Detection>,
    val preMs: Float,    // 预处理：缩放 + 归一化
    val inferMs: Float,  // 模型推理
    val postMs: Float    // 后处理：解码 + NMS
) {
    /** 只保留置信度最高的那张脸，供后面的识别/活体用 */
    fun largest(): Detection? =
        detections.maxByOrNull { it.width * it.height }
}
