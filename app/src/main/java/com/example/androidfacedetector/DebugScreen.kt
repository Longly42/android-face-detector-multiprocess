package com.example.androidfacedetector

import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidfacedetector.inference.RunMode
import com.example.androidfacedetector.ipc.ServiceStatsParcel
import com.example.androidfacedetector.perf.MetricsRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "系统运行状态/调试"页。做成覆盖层而不是独立 Activity：
 * 独立 Activity 会让 MainActivity 进入 stop，CameraX 停止出帧，
 * 页面上的队列长度/信号量许可会立刻变成 0，失去观测意义。
 */
@Composable
fun DebugScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val router = (context.applicationContext as FaceApp).router
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf<ServiceStatsParcel?>(null) }
    var threads by remember { mutableStateOf(listOf<String>()) }
    var mode by remember { mutableStateOf(router.mode) }
    var note by remember { mutableStateOf("") }
    var ipcText by remember { mutableStateOf("") }
    var metricCount by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            stats = withContext(Dispatchers.IO) { runCatching { router.stats() }.getOrNull() }
            threads = withContext(Dispatchers.IO) {
                Thread.getAllStackTraces().keys
                    .filter { it.isAlive }
                    .map { "${it.name}  tid=${it.id}" }
                    .sorted()
            }
            metricCount = MetricsRecorder.size()
            summary = MetricsRecorder.summaryText()
            ipcText = router.clientRef?.let { c ->
                "调用 ${c.ipcCalls.get()}  失败 ${c.ipcFailures.get()}  " +
                        "avg ${"%.2f".format(c.avgIpcMs)}ms  max ${"%.2f".format(c.maxIpcMs)}ms"
            } ?: "（单进程模式，无 IPC）"
            mode = router.mode
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("系统运行状态", color = Color.White, fontSize = 20.sp)
            Button(onClick = onClose) { Text("返回", fontSize = 12.sp) }
        }
        Text(
            "UI 主线程 tid=${Process.myTid()}（本页所在进程 pid=${Process.myPid()}）",
            color = Color.LightGray, fontSize = 12.sp
        )

        Section("进程与 IPC") {
            Line("运行模式", mode.label)
            Line("UI 进程 PID", "${Process.myPid()}")
            Line(
                "推理进程 PID",
                (router.clientRef?.connectedPid ?: -1).let { if (it > 0) "$it" else "未连接" }
            )
            Line("IPC 时延", ipcText)
            Line("服务端 PID（stats）", stats?.pid?.toString() ?: "—")
        }

        Section("同步与队列（推理进程）") {
            Line("可用许可 / 总许可", "${stats?.availablePermits ?: 0} / ${stats?.maxPermits ?: 0}")
            Line("在途任务数", "${stats?.inFlight ?: 0}")
            Line(
                "队列长度",
                "${stats?.queueLength ?: 0}  （线程池 ${stats?.poolSize ?: 0} 线程，活跃 ${stats?.activeThreads ?: 0}）"
            )
            Line("已处理 / 总请求", "${stats?.processed ?: 0} / ${stats?.totalRequests ?: 0}")
            Line("丢弃（背压）", "${stats?.dropped ?: 0}")
            Line("超时 / 异常", "${stats?.timeouts ?: 0} / ${stats?.errors ?: 0}")
            Line(
                "平均排队 / 最大排队",
                "${"%.1f".format(stats?.avgQueueWaitMs ?: 0f)}ms / ${"%.1f".format(stats?.maxQueueWaitMs ?: 0f)}ms"
            )
            Line("单帧服务端耗时", "${"%.1f".format(stats?.avgWorkMs ?: 0f)}ms")
            Line("特征库人数", "${stats?.dbSize ?: 0}")
        }

        Section("线程（UI 进程）") {
            Text(
                threads.joinToString("\n"),
                color = Color(0xFF9CCC65), fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }

        Section("推理进程工作线程") {
            Text(
                (stats?.threadNames ?: emptyList()).joinToString("\n").ifEmpty { "—" },
                color = Color(0xFF9CCC65), fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }

        Section("运行配置") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        note = "切换到双进程…"
                        withContext(Dispatchers.IO) { router.setMode(RunMode.DUAL) }
                        mode = router.mode
                        note = "已切换：${router.mode.label}"
                    }
                }) { Text("双进程", fontSize = 12.sp) }

                OutlinedButton(onClick = {
                    scope.launch {
                        note = "正在加载本地模型（约 2~3 秒）…"
                        withContext(Dispatchers.IO) { router.setMode(RunMode.SINGLE) }
                        mode = router.mode
                        note = "已切换：${router.mode.label}（本进程直接推理）"
                    }
                }) { Text("单进程", fontSize = 12.sp) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in listOf(1, 2, 4)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    // 许可数 = 线程数 + 1，留一个排队位
                                    router.configure(n, n + 1)
                                }
                                note = "线程池=$n, 许可=${n + 1}"
                            }
                        },
                        modifier = Modifier.width(84.dp)
                    ) { Text("${n}线程", fontSize = 12.sp) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in listOf(1, 2, 4)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { router.configure(stats?.poolSize ?: 2, p) }
                                note = "许可数=$p（越小越容易触发丢帧，可观察背压）"
                            }
                        },
                        modifier = Modifier.width(84.dp)
                    ) { Text("许可$p", fontSize = 12.sp) }
                }
            }
        }

        Section("动作与统计") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { router.resetStats() }
                        note = "统计已清零"
                    }
                }) { Text("清零", fontSize = 12.sp) }

                OutlinedButton(onClick = {
                    scope.launch {
                        note = "已通知推理进程自杀，观察上方连接状态与自动重连…"
                        withContext(Dispatchers.IO) { router.crashServiceForTest() }
                    }
                }) { Text("崩溃服务", fontSize = 12.sp) }

                OutlinedButton(onClick = {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { MetricsRecorder.exportCsv(context) }
                        note = if (f != null) "已导出：${f.absolutePath}" else "暂无数据可导出"
                    }
                }) { Text("导出CSV", fontSize = 12.sp) }
            }

            // 并发压测：单相机场景下 CameraX 回调是串行的，只有人为制造并发
            // 才能看出"有界并发 + 丢帧"与"无限流"的差别
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        note = "压测中…"
                        note = withContext(Dispatchers.IO) { router.stressTest(8) }
                    }
                }) { Text("并发压测 x8", fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    scope.launch {
                        note = "压测中…"
                        note = withContext(Dispatchers.IO) { router.stressTest(16) }
                    }
                }) { Text("并发压测 x16", fontSize = 12.sp) }
            }
            Line("已记录帧数", "$metricCount")
            Text(
                summary,
                color = Color(0xFFB0BEC5), fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }

        if (note.isNotEmpty()) Text(note, color = Color(0xFFFFD54F), fontSize = 12.sp)

        Text(
            "取数据：adb pull /sdcard/Android/data/com.example.androidfacedetector/files/metrics",
            color = Color.Gray, fontSize = 10.sp
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B2228), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = Color(0xFF4FC3F7), fontSize = 14.sp)
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
