package com.jev.probe.comments

import java.util.UUID

enum class SampleKind(val label: String) {
    COMMENT("视频 / 帖子评论"), DANMAKU("视频弹幕"), LIVE("直播弹幕")
}

enum class CommentEngine(val label: String) {
    JEV("Jev 快速判断"), GENERATIVE("通用模型分析")
}

enum class CommentCaptureMode(val label: String) { MANUAL("手动"), AUTO("自动") }

/** 引用只服务于本条判断，不作为额外的评论样本计数。 */
data class CapturedComment(
    val text: String,
    val parentText: String = "",
    val threadRoot: String = "",
    val contextMissing: Boolean = false,
    val contextNote: String = ""
) {
    val fingerprint get() = listOf(text, parentText, threadRoot).map(CommentText::fingerprint)
}

object CommentTopics {
    val defaults = listOf("画面与音效", "性能与稳定性", "玩法与功能", "价格与付费", "内容与观点", "主播表现", "直播音画", "互动与节奏")
    fun parse(text: String): List<String> {
        val topics = text.split(Regex("[,，、\\n]+" )).map { it.trim() }
            .filter { it.isNotEmpty() && it != "其他" && it != "非评论内容" }.distinct()
        require(topics.isNotEmpty() && topics.size <= 12 && topics.all { it.length <= 20 }) {
            "请填写 1–12 个话题，每个最多 20 字"
        }
        return topics
    }
}

enum class Sentiment(val wire: String, val label: String, val color: String) {
    POSITIVE("positive", "正面", "#15803D"),
    NEGATIVE("negative", "负面", "#C2410C"),
    NEUTRAL("neutral", "中性", "#475569"),
    MIXED("mixed", "褒贬混合", "#7C3AED"),
    UNCERTAIN("uncertain", "无法判断", "#64748B")
}

data class CommentVerdict(
    val sentiment: Sentiment,
    val topic: String,
    val target: String,
    val reason: String,
    val engine: CommentEngine = CommentEngine.GENERATIVE,
    val confidence: Double? = null,
    val probabilities: Map<String, Double> = emptyMap(),
    val modelSentiment: Sentiment? = null
)

data class CommentSample(
    val id: Int,
    val text: String,
    val capturedAt: Long,
    val batch: Int,
    val verdict: CommentVerdict? = null,
    val parentText: String = "",
    val threadRoot: String = "",
    val contextMissing: Boolean = false,
    val contextNote: String = ""
) {
    fun captured() = CapturedComment(text, parentText, threadRoot, contextMissing, contextNote)
}

/** 一个视频或帖子对应一次采样；时间只记录采集时刻，不冒充评论发布时间。 */
data class CommentSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "未命名采样",
    val kind: SampleKind = SampleKind.COMMENT,
    val context: String = "",
    val sourcePackage: String = "",
    val samples: List<CommentSample> = emptyList(),
    val topics: List<String> = CommentTopics.defaults,
    val postKey: String = "",
    val contextConfirmed: Boolean = context.isNotBlank()
) {
    val analyzed: List<CommentSample> get() = samples.filter { it.verdict != null }
    val pending: List<CommentSample> get() = samples.filter { it.verdict == null }

    fun append(texts: List<String>, source: String, now: Long): AppendResult {
        return appendCaptured(texts.map { CapturedComment(it) }, source, now)
    }

    fun appendCaptured(comments: List<CapturedComment>, source: String, now: Long): AppendResult {
        require(sourcePackage.isBlank() || sourcePackage == source) { "请为另一个平台新建采样" }
        val seen = samples.map { it.captured().fingerprint }.toMutableSet()
        val cleaned = comments.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
        require(cleaned.all { it.text.length <= MAX_TEXT_LENGTH && it.parentText.length <= MAX_TEXT_LENGTH && it.threadRoot.length <= MAX_TEXT_LENGTH }) {
            "单条评论及引用各最多 ${MAX_TEXT_LENGTH} 字，请核对分段"
        }
        val unique = cleaned.filter { seen.add(it.fingerprint) }
        require(samples.size + unique.size <= MAX_SAMPLES) { "本次最多 ${MAX_SAMPLES} 条样本，请新建采样" }
        val nextId = (samples.maxOfOrNull { it.id } ?: 0) + 1
        val batch = (samples.maxOfOrNull { it.batch } ?: 0) + 1
        val added = unique.mapIndexed { index, item -> CommentSample(nextId + index, item.text, now, batch,
            parentText = item.parentText, threadRoot = item.threadRoot, contextMissing = item.contextMissing, contextNote = item.contextNote) }
        return AppendResult(
            copy(sourcePackage = if (added.isEmpty()) sourcePackage else source, samples = samples + added),
            added.size, cleaned.size - unique.size
        )
    }

    fun withContext(title: String, context: String, confirmed: Boolean, key: String, source: String): CommentSession {
        require(postKey.isBlank() || key.isBlank() || postKey == key) { "检测到另一个帖子，请新建采样" }
        require(sourcePackage.isBlank() || sourcePackage == source) { "请为另一个平台新建采样" }
        val changed = this.title != title || this.context != context || contextConfirmed != confirmed
        return copy(title = title, context = context, contextConfirmed = confirmed,
            postKey = key.ifBlank { postKey }, sourcePackage = source,
            samples = if (changed) samples.map { it.copy(verdict = null) } else samples)
    }

    fun applyVerdicts(verdicts: Map<Int, CommentVerdict>): CommentSession {
        require(verdicts.keys.all { key -> samples.any { it.id == key } }) { "分析结果包含未知样本" }
        return copy(samples = samples.map { sample ->
            verdicts[sample.id]?.let { sample.copy(verdict = it) } ?: sample
        })
    }

    fun remove(id: Int): CommentSession = copy(samples = samples.filterNot { it.id == id })

    companion object {
        const val MAX_SAMPLES = 500
        const val MAX_TEXT_LENGTH = 2000
    }
}

data class AppendResult(val session: CommentSession, val added: Int, val duplicates: Int)

data class TopicCount(val topic: String, val samples: List<CommentSample>)

/** 分母含“无法判断”；未分析样本不参与比例，界面另列其数量。 */
class CommentStats(samples: List<CommentSample>) {
    val analyzed = samples.filter { it.verdict != null }
    val counts = Sentiment.entries.associateWith { sentiment -> analyzed.count { it.verdict?.sentiment == sentiment } }
    fun percent(sentiment: Sentiment): Double =
        if (analyzed.isEmpty()) 0.0 else counts.getValue(sentiment) * 100.0 / analyzed.size

    val topics: List<TopicCount> = analyzed.groupBy { it.verdict!!.topic }
        .map { (topic, items) -> TopicCount(topic, items) }
        .sortedWith(compareByDescending<TopicCount> { it.samples.size }.thenBy { it.topic })
}

object CommentSource {
    fun label(pkg: String): String = when (pkg) {
        "tv.danmaku.bili", "tv.danmaku.bilibilihd", "com.bilibili.app.in" -> "B 站"
        "com.max.xiaoheihe" -> "小黑盒"
        "" -> "尚未采集"
        else -> pkg
    }
}
