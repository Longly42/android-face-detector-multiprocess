package com.example.androidfacedetector.face

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 人脸特征库：注册过的名字 -> 512 维特征。
 *
 * 只存特征向量，不存照片。这也是端侧人脸方案的标准做法：
 * 万一特征库泄露，别人也没法从一串 512 个浮点数还原出你的脸。
 *
 * 数据放在应用私有目录 filesDir，卸载即消失，其他应用读不到。
 */
class FaceDatabase(context: Context) {

    data class Entry(val name: String, val feature: FloatArray)

    /** @param similarity 与库中最相似者的余弦相似度，是否判定为同一人由调用方按阈值决定 */
    data class Match(val name: String, val similarity: Float)

    private companion object {
        const val TAG = "FaceDatabase"
        const val MAGIC = "FDB1"
        const val FILE_NAME = "faces.bin"
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val entries = ArrayList<Entry>()

    init {
        load()
        Log.i(TAG, "特征库加载完成，已有 ${entries.size} 人：${entries.map { it.name }}")
    }

    val size: Int
        get() = synchronized(this) { entries.size }

    fun names(): List<String> = synchronized(this) { entries.map { it.name } }

    /** 注册或覆盖同名条目 */
    @Synchronized
    fun register(name: String, feature: FloatArray) {
        val trimmed = name.trim().ifEmpty { "未命名" }
        entries.removeAll { it.name == trimmed }
        entries.add(Entry(trimmed, feature.copyOf()))
        save()
        Log.i(TAG, "已注册 $trimmed，当前共 ${entries.size} 人")
    }

    /**
     * 在库里找最相似的人。
     * @return 库为空时返回 null；否则返回最相似条目（哪怕很相似度很低，由调用方判断）
     */
    @Synchronized
    fun match(feature: FloatArray): Match? {
        var best: Entry? = null
        var bestSim = -1f
        for (e in entries) {
            if (e.feature.size != feature.size) continue
            val sim = FaceEmbedder.cosine(e.feature, feature)
            if (sim > bestSim) {
                bestSim = sim
                best = e
            }
        }
        return best?.let { Match(it.name, bestSim) }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        save()
        Log.i(TAG, "特征库已清空")
    }

    // ---------------- 持久化 ----------------

    private fun save() {
        try {
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeUTF(MAGIC)
                out.writeInt(entries.size)
                for (e in entries) {
                    out.writeUTF(e.name)
                    out.writeInt(e.feature.size)
                    for (v in e.feature) out.writeFloat(v)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "保存特征库失败", t)
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != MAGIC) return
                val count = input.readInt()
                repeat(count) {
                    val name = input.readUTF()
                    val dim = input.readInt()
                    val f = FloatArray(dim) { input.readFloat() }
                    entries.add(Entry(name, f))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "读取特征库失败，按空库处理", t)
            entries.clear()
        }
    }
}
