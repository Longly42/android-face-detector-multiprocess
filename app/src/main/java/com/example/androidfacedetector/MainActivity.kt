package com.example.androidfacedetector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraScreen()
        }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 状态：是否拥有相机权限
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 状态：用于在屏幕上显示当前进度
    var statusText by remember { mutableStateOf("准备请求权限...") }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasPermission = granted
            statusText = if (granted) "权限已允许，启动相机..." else "权限被拒绝！"
        }
    )

    // 每次进入界面时检查权限
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            Log.d("CameraDebug", "发起权限请求")
            launcher.launch(Manifest.permission.CAMERA)
        } else {
            statusText = "已有权限，启动相机..."
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    Log.d("CameraDebug", "开始创建 PreviewView")
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        Log.d("CameraDebug", "成功获取 CameraProvider")

                        // 1. 预览用例
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        // 2. 图像分析用例（为后续接入模型准备）
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        val cameraExecutor = Executors.newSingleThreadExecutor()
                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            // 这里暂时只打印日志，后续这里将接入 SCRFD 模型
                            Log.d("CameraDebug", "拿到一帧图像: ${imageProxy.width}x${imageProxy.height}")
                            imageProxy.close()
                        }

                        try {
                            cameraProvider.unbindAll()
                            // 绑定前置摄像头，同时绑定预览和分析
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                preview,
                                imageAnalysis
                            )
                            Log.d("CameraDebug", "相机绑定成功！")
                            statusText = "相机已启动"

                        } catch (e: Exception) {
                            Log.e("CameraDebug", "相机绑定异常", e)
                            statusText = "相机启动失败: ${e.message}"
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 状态文字悬浮在左上角
        Text(
            text = statusText,
            color = Color.Green,
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}
