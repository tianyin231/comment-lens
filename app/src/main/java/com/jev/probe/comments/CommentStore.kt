package com.jev.probe.comments

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** 仅保存用户确认的样本和结果，不保存截图；同一次采样在服务重启后可继续。 */
class CommentStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "comment-session.json"))

    fun load(): CommentSession {
        if (!file.baseFile.exists()) return CommentSession()
        return file.openRead().bufferedReader(Charsets.UTF_8).use { CommentCodec.decode(it.readText()) }
    }

    fun save(session: CommentSession) {
        val bytes = CommentCodec.encode(session).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}
