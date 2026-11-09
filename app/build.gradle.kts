plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.androidfacedetector"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.androidfacedetector"
        // 27：跨进程传帧用 android.os.SharedMemory（API 27 起提供）
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 关闭 R8 代码裁剪：AIDL 生成的 Stub 与手写 Parcelable 的 CREATOR
            // 依赖反射式实例化，裁剪后一旦缺规则会在运行期才崩，而本项目缺少
            // arm64 真机做验收，因此这里只做 ABI 裁剪，不冒险做代码优化。
            optimization {
                enable = false
            }
            // 交付用：ONNX Runtime 默认把 4 种 ABI 的 so 都打进来（约 80MB），
            // 真机基本都是 arm64，裁掉其余三种可省一半体积。
            // 注意：只含 arm64-v8a，x86_64 模拟器装不上，调试仍用 debug 包。
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            // 用 debug 签名，保证产物可直接 adb install 演示；正式发布应换成自己的 keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // ONNX Runtime：在手机上跑 .onnx 模型的官方推理库
    implementation(libs.onnxruntime.android)
}