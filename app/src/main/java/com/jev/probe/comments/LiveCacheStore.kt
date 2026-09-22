package com.jev.probe.comments

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** 与旧版手动采样文件分开，更新时原子替换；所有写入由服务的单线程队列按顺序执行。 */
class LiveCacheStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "live-post-cache.json"))

    fun load(): List<LiveSnapshot> = if (!file.baseFile.exists()) emptyList() else
        file.openRead().bufferedReader(Charsets.UTF_8).use { LiveCacheCodec.decode(it.readText()) }

    fun save(posts: List<LiveSnapshot>) {
        val bytes = LiveCacheCodec.encode(posts).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes); file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output); throw error
        }
    }
}
