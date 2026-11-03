package com.jev.probe.bilibili

import com.jev.probe.comments.LiveDecision
import com.jev.probe.comments.LiveText
import com.jev.probe.comments.Sentiment
import com.jev.probe.jev.ApiException

enum class BilibiliAnalysisStatus { NOT_STARTED, NO_TEXT, NEEDS_CONFIG, RUNNING, COMPLETE, PARTIAL, FAILED, CANCELLED }

data class BilibiliAnalysisResult(
    val load: BilibiliLoadResult,
    val status: BilibiliAnalysisStatus,
    val decisions: Map<String, LiveDecision> = emptyMap(),
    val reason: String = ""
) {
    val textCount get() = load.comments.count { it.text.isNotBlank() }
    fun count(sentiment: Sentiment) = decisions.values.count { it.sentiment == sentiment }
    fun percent(sentiment: Sentiment) = if (decisions.isEmpty()) 0 else count(sentiment) * 100 / decisions.size
}

/** 仅对实际取得的顶层评论判断；同文一次上传，结果分别归到各自评论 ID。 */
class BilibiliAnalysis(
    private val source: BilibiliCommentSource,
    private val judge: ((List<LiveText>) -> Map<String, LiveDecision>)?
) {
    fun run(
        request: BilibiliRequest,
        cancelled: () -> Boolean = { false },
        progress: (BilibiliAnalysisResult) -> Unit = {}
    ): BilibiliAnalysisResult {
        val load = BilibiliCommentLoader(source).load(request, cancelled)
        val decisions = linkedMapOf<String, LiveDecision>()
        fun result(status: BilibiliAnalysisStatus, reason: String = "") =
            BilibiliAnalysisResult(load, status, decisions.toMap(), reason)
        if (cancelled() || load.status == BilibiliLoadStatus.CANCELLED)
            return result(BilibiliAnalysisStatus.CANCELLED, "已取消。")
        if (load.status in setOf(BilibiliLoadStatus.BLOCKED, BilibiliLoadStatus.FAILED, BilibiliLoadStatus.EMPTY))
            return result(BilibiliAnalysisStatus.NOT_STARTED, load.reason)
        val groups = load.comments.filter { it.text.isNotBlank() }.groupBy { it.text.trim() }
        if (groups.isEmpty()) return result(BilibiliAnalysisStatus.NO_TEXT, "已读取的评论没有可判断的文字，未发送 Jev 请求。")
        val analyze = judge ?: return result(BilibiliAnalysisStatus.NEEDS_CONFIG, "请先在实时情绪设置中配置 Jev，再读取并分析。")
        val texts = groups.keys.mapIndexed { index, text -> LiveText("c${index + 1}", text) }
        progress(result(BilibiliAnalysisStatus.RUNNING))
        for (batch in texts.chunked(8)) {
            if (cancelled()) return result(BilibiliAnalysisStatus.CANCELLED, "已取消。")
            val answers = try {
                analyze(batch).also { answer ->
                    require(answer.keys == batch.map { it.id }.toSet()) { "Jev 判断数量不完整。" }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return result(BilibiliAnalysisStatus.CANCELLED, "已取消。")
            } catch (error: Exception) {
                if (cancelled()) return result(BilibiliAnalysisStatus.CANCELLED, "已取消。")
                return result(if (decisions.isEmpty()) BilibiliAnalysisStatus.FAILED else BilibiliAnalysisStatus.PARTIAL,
                    judgeFailure(error))
            }
            if (cancelled()) return result(BilibiliAnalysisStatus.CANCELLED, "已取消。")
            batch.forEach { item -> groups.getValue(item.text).forEach { comment -> decisions[comment.id] = answers.getValue(item.id) } }
            progress(result(BilibiliAnalysisStatus.RUNNING))
        }
        return if (cancelled()) result(BilibiliAnalysisStatus.CANCELLED, "已取消。")
            else result(BilibiliAnalysisStatus.COMPLETE)
    }

    private fun judgeFailure(error: Exception): String = when (error) {
        is ApiException -> when (error.status) {
            401, 403 -> "Jev 拒绝认证（HTTP ${error.status}），请检查接口与密钥配置。"
            402 -> "Jev 返回付费或额度限制（HTTP 402），本次已停止后续判断。"
            429, 529 -> "Jev 当前限流或繁忙（HTTP ${error.status}），本次未继续请求。"
            null -> "Jev 连接失败，本次未继续请求，请检查网络与接口设置。"
            else -> "Jev 请求失败（HTTP ${error.status}），本次未继续请求。"
        }
        is IllegalArgumentException -> "Jev 返回的判断不完整或格式无效，本次未继续请求。"
        else -> "Jev 判断失败，已保留实际读取的评论和已完成的结果。"
    }
}
