package com.example.androidfacedetector

import android.app.Application
import android.util.Log
import com.example.androidfacedetector.inference.EngineRouter

/**
 * 全局持有推理路由，让 MainActivity 与 DebugActivity 共用同一份连接/统计。
 * 进程名会打印出来，方便确认真的是两个进程（主进程 vs :inference）。
 */
class FaceApp : Application() {

    lateinit var router: EngineRouter
        private set

    override fun onCreate() {
        super.onCreate()
        router = EngineRouter(this)
        Log.i("FaceApp", "应用启动 pid=${android.os.Process.myPid()} process=${currentProcessName()}")
    }

    private fun currentProcessName(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: "unknown"
        }
    }
}
