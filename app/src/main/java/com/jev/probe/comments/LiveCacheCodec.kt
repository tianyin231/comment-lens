package com.jev.probe.comments

import org.json.JSONArray
import org.json.JSONObject

/** 缓存只有帖文、评论和情绪结果；不存密钥、截图或额外上下文。顺序为最近访问的帖子在末尾。 */
object LiveCacheCodec {
    fun encode(posts: List<LiveSnapshot>): String = JSONObject().put("version", 1)
        .put("posts", JSONArray().apply {
            posts.forEach { post ->
                put(JSONObject().put("key", post.postKey).put("title", post.title)
                    .put("post", post.post?.let(::encodeText) ?: JSONObject.NULL)
                    .put("comments", JSONArray().apply { post.comments.forEach { put(encodeText(it)) } }))
            }
        }).toString()

    fun decode(value: String): List<LiveSnapshot> {
        val root = JSONObject(value)
        require(root.getInt("version") == 1) { "缓存版本不兼容" }
        val posts = root.getJSONArray("posts")
        return (0 until posts.length()).map { index ->
            val post = posts.getJSONObject(index)
            val comments = post.getJSONArray("comments")
            LiveSnapshot(post.getString("title"), post.optJSONObject("post")?.let(::decodeText),
                (0 until comments.length()).map { decodeText(comments.getJSONObject(it)) }, post.getString("key"))
        }
    }

    private fun encodeText(item: LiveText) = JSONObject().put("id", item.id).put("text", item.text)
        .put("decision", item.decision?.let {
            JSONObject().put("sentiment", it.sentiment.wire).put("confidence", it.confidence).put("original", it.original.wire)
        } ?: JSONObject.NULL)

    private fun decodeText(item: JSONObject): LiveText {
        val decision = item.optJSONObject("decision")?.let { value ->
            LiveDecision(Sentiment.entries.first { it.wire == value.getString("sentiment") },
                value.getDouble("confidence"), Sentiment.entries.first { it.wire == value.getString("original") })
        }
        return LiveText(item.getString("id"), item.getString("text"), decision)
    }
}
