package com.jev.probe.comments

import org.json.JSONArray
import org.json.JSONObject

object CommentCodec {
    fun encode(session: CommentSession): String = JSONObject().apply {
        put("version", 2)
        put("id", session.id)
        put("title", session.title)
        put("kind", session.kind.name)
        put("context", session.context)
        put("postKey", session.postKey)
        put("contextConfirmed", session.contextConfirmed)
        put("sourcePackage", session.sourcePackage)
        put("topics", JSONArray(session.topics))
        put("samples", JSONArray().also { array ->
            session.samples.forEach { sample ->
                array.put(JSONObject().apply {
                    put("id", sample.id)
                    put("text", sample.text)
                    put("capturedAt", sample.capturedAt)
                    put("batch", sample.batch)
                    put("parentText", sample.parentText)
                    put("threadRoot", sample.threadRoot)
                    put("contextMissing", sample.contextMissing)
                    put("contextNote", sample.contextNote)
                    sample.verdict?.let { put("verdict", encodeVerdict(it)) }
                })
            }
        })
    }.toString()

    fun decode(text: String): CommentSession {
        val root = JSONObject(text)
        require(root.getInt("version") in 1..2) { "采样文件版本不支持" }
        val array = root.getJSONArray("samples")
        val legacy = root.getInt("version") == 1
        return CommentSession(
            id = root.getString("id"), title = root.getString("title"),
            kind = SampleKind.valueOf(root.getString("kind")), context = root.optString("context"),
            sourcePackage = root.optString("sourcePackage"),
            postKey = root.optString("postKey"), contextConfirmed = root.optBoolean("contextConfirmed", false),
            topics = root.optJSONArray("topics")?.let { topics ->
                (0 until topics.length()).map { topics.getString(it) }
            } ?: CommentTopics.defaults,
            samples = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                CommentSample(item.getInt("id"), item.getString("text"), item.getLong("capturedAt"),
                    item.getInt("batch"), if (legacy) null else item.optJSONObject("verdict")?.let { decodeVerdict(it, saved = true) },
                    item.optString("parentText"), item.optString("threadRoot"),
                    legacy || item.optBoolean("contextMissing"), if (legacy) "旧版样本未记录回复关系，请核对后重新分析" else item.optString("contextNote"))
            }
        )
    }

    /** 整批校验后再保存，缺项、重复 ID 或未知标签不能被默认为“中性”。 */
    fun parseAnalysis(content: String, expectedIds: Set<Int>): Map<Int, CommentVerdict> {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        require(start >= 0 && end > start) { "模型未返回 JSON，请重试或更换模型" }
        val array = JSONObject(content.substring(start, end + 1)).getJSONArray("comments")
        val out = linkedMapOf<Int, CommentVerdict>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val id = item.getInt("id")
            require(id in expectedIds && id !in out) { "模型返回了未知或重复的样本编号" }
            out[id] = decodeVerdict(item)
        }
        require(out.keys == expectedIds) { "模型漏掉了部分样本，本批尚未计入，请重试" }
        return out
    }

    private fun encodeVerdict(v: CommentVerdict): JSONObject = JSONObject()
        .put("sentiment", v.sentiment.wire).put("topic", v.topic).put("target", v.target).put("reason", v.reason)
        .put("engine", v.engine.name).put("confidence", v.confidence)
        .put("probabilities", JSONObject(v.probabilities)).put("modelSentiment", v.modelSentiment?.wire)

    private fun decodeVerdict(item: JSONObject, saved: Boolean = false): CommentVerdict {
        val sentiment = Sentiment.entries.firstOrNull { it.wire == item.getString("sentiment") }
            ?: error("模型返回了无法识别的情绪标签")
        fun field(name: String, max: Int): String {
            val value = item.getString(name).trim()
            require(value.isNotBlank() && value.length <= max) { "模型返回的 ${name} 字段无效" }
            return value
        }
        val verdict = CommentVerdict(sentiment, field("topic", 30), field("target", 60), field("reason", 240))
        if (!saved) return verdict
        return verdict.copy(
            engine = CommentEngine.valueOf(item.optString("engine", CommentEngine.GENERATIVE.name)),
            confidence = if (item.has("confidence") && !item.isNull("confidence")) item.getDouble("confidence") else null,
            probabilities = item.optJSONObject("probabilities")?.let { values ->
                values.keys().asSequence().associateWith { values.getDouble(it) }
            } ?: emptyMap(),
            modelSentiment = Sentiment.entries.firstOrNull { it.wire == item.optString("modelSentiment") }
        )
    }
}
