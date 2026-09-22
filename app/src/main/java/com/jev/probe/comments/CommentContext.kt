package com.jev.probe.comments

import org.json.JSONArray
import org.json.JSONObject

object CommentContext {
    fun state(session: CommentSession, batch: List<CommentSample>): JSONObject = JSONObject()
        .put("sampling_title", session.title).put("kind", session.kind.label)
        .put("background", session.context).put("post_context_confirmed", session.contextConfirmed)
        .put("comments", JSONArray().also { array ->
            batch.forEach { sample -> array.put(JSONObject().put("id", sample.id).put("text", sample.text)
                .put("parent_comment", sample.parentText).put("thread_root", sample.threadRoot)
                .put("context_missing", sample.contextMissing).put("context_note", sample.contextNote)) }
        })

    fun guard(session: CommentSession, batch: List<CommentSample>, verdicts: Map<Int, CommentVerdict>): Map<Int, CommentVerdict> =
        verdicts.mapValues { (id, verdict) ->
            val sample = batch.first { it.id == id }
            if (!session.contextConfirmed || session.context.isBlank() || sample.contextMissing) verdict.copy(
                sentiment = Sentiment.UNCERTAIN, target = "上下文不足",
                reason = "帖子背景、父评论或图片内容尚未补全，暂不计为正负面。" + sample.contextNote.take(100)
            ) else verdict
        }
}
