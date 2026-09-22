package com.jev.probe.comments

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveSentimentTest {
    private fun page(key: String = "a", vararg texts: String) = CommentPage(
        comments = texts.map { CapturedComment(it, parentText = "不要上传的父评论", contextMissing = true) },
        postTitle = "帖文 $key", postBody = "独立正文", postKey = key, recognized = true)
    private val positive = LiveDecision(Sentiment.POSITIVE, .9)

    @Test fun repeatedScreenAndReplyContextDoNotCauseMoreRequests() {
        val stream = LiveSentiment()
        val screen = page("a", "很好", "不好")
        stream.follow("app", screen, true)
        val batch = stream.pending()
        assertEquals(3, batch.texts.size)
        stream.apply(batch, batch.texts.associate { it.id to positive })
        stream.follow("app", screen.copy(comments = screen.comments.map { it.copy(parentText = "父评论变化") }), true)
        assertTrue(stream.pending().texts.isEmpty())
        assertEquals(2, stream.snapshot.comments.size)
    }

    @Test fun switchingPostDiscardsLateAnswersEvenWhenCommentIdsRepeat() {
        val stream = LiveSentiment()
        stream.follow("app", page("a", "旧评论"), true)
        val old = stream.pending()
        stream.follow("app", page("b", "新评论"), true)
        assertFalse(stream.apply(old, old.texts.associate { it.id to positive }))
        assertEquals(listOf("新评论"), stream.snapshot.comments.map { it.text })
        assertTrue(stream.snapshot.judged.isEmpty())
        assertNull(stream.snapshot.post!!.decision)
    }

    @Test fun leavingAndReopeningSamePostRestoresResultsAndRejectsOldRequests() {
        val stream = LiveSentiment()
        stream.follow("app", page("a", "评论"), true)
        val old = stream.pending()
        stream.apply(old, old.texts.associate { it.id to positive })
        stream.invalidateRequests()
        assertFalse(stream.apply(old, old.texts.associate { it.id to positive }))
        stream.detach()
        assertTrue(stream.snapshot.comments.isEmpty())
        stream.follow("app", page("a", "评论"), true)
        assertTrue(stream.pending().texts.isEmpty())
        assertEquals(1, stream.snapshot.judged.size)
        assertFalse(stream.apply(old, old.texts.associate { it.id to positive }))
    }

    @Test fun statisticsIncludeAllJudgedCommentsWithoutMixingInPostOrPendingText() {
        val stream = LiveSentiment()
        stream.follow("app", page("a", *(1..60).map { "评论 $it" }.toTypedArray()), true)
        val batch = stream.pending(100)
        val answers = batch.texts.associate { item ->
            item.id to if (item.id == "p" || item.id.removePrefix("c").toInt() > 30)
                LiveDecision(Sentiment.NEGATIVE, .9) else positive
        }
        stream.apply(batch, answers)
        assertEquals(60, stream.snapshot.comments.size)
        assertEquals("评论 1", stream.snapshot.comments.first().text)
        assertEquals(50, stream.snapshot.percent(Sentiment.POSITIVE))
        assertEquals(50, stream.snapshot.percent(Sentiment.NEGATIVE))
        assertEquals(30, stream.snapshot.count(Sentiment.POSITIVE))
        assertEquals(30, stream.snapshot.count(Sentiment.NEGATIVE))
        stream.follow("app", page("a", "评论 1", "新评论"), true)
        assertEquals(listOf("新评论"), stream.pending().texts.map { it.text })
        assertEquals(61, stream.snapshot.comments.size)
        assertEquals(60, stream.snapshot.judged.size)
        assertEquals(50, stream.snapshot.percent(Sentiment.POSITIVE))
        assertEquals(50, stream.snapshot.percent(Sentiment.NEGATIVE))
    }

    @Test fun onlyFiveRecentlyVisitedPostsAreKeptAndReturningRefreshesRecency() {
        val stream = LiveSentiment()
        ('a'..'e').forEach { stream.follow("app", page(it.toString(), "评论 $it"), true) }
        stream.follow("app", page("a", "评论 a"), true)
        stream.follow("app", page("f", "评论 f"), true)
        assertEquals(listOf("app:c", "app:d", "app:e", "app:a", "app:f"), stream.cachedPosts().map { it.postKey })
        stream.follow("app", page("c", "评论 c", "新增"), true)
        assertEquals(listOf("评论 c", "新增"), stream.snapshot.comments.map { it.text })
    }

    @Test fun diskRoundTripRestoresDecisionsPendingWorkAndUniqueIdsWithoutSendingHistory() {
        val stream = LiveSentiment()
        stream.follow("app", page("a", "之前的评论"), true)
        val batch = stream.pending()
        stream.apply(batch, batch.texts.associate { it.id to LiveDecision(Sentiment.UNCERTAIN, .3, Sentiment.POSITIVE) })
        stream.follow("app", page("a", "还没判断"), true)
        val posts = LiveCacheCodec.decode(LiveCacheCodec.encode(stream.cachedPosts()))
        assertEquals(stream.cachedPosts(), posts)
        val restored = LiveSentiment(posts)
        restored.follow("app", page("a", "之前的评论", "刚读到的评论"), true)
        assertEquals(listOf("之前的评论", "还没判断", "刚读到的评论"), restored.snapshot.comments.map { it.text })
        assertEquals(3, restored.snapshot.comments.map { it.id }.distinct().size)
        assertEquals(setOf("还没判断", "刚读到的评论"), restored.pending().texts.map { it.text }.toSet())
        val request = JevSentimentClient.request(restored.pending().texts, "jev-latest").getJSONObject("state").toString()
        assertFalse(request.contains("之前的评论"))
        assertFalse(request.contains("独立正文"))
    }

    @Test fun aLargePendingBacklogIsRetainedAndEventuallyDrained() {
        val stream = LiveSentiment()
        stream.follow("app", page("a", *(1..65).map { "评论 $it" }.toTypedArray()), true)
        val visited = mutableSetOf<String>()
        while (stream.pending().texts.isNotEmpty()) {
            val batch = stream.pending()
            batch.texts.forEach { assertTrue(visited.add(it.id)) }
            stream.apply(batch, batch.texts.associate { it.id to positive })
        }
        assertEquals(66, visited.size)
        assertEquals(65, stream.snapshot.judged.size)
        assertEquals(65, stream.snapshot.count(Sentiment.POSITIVE))
        assertEquals(100, stream.snapshot.percent(Sentiment.POSITIVE))
    }

    @Test fun manualPageDetectionDoesNotCollectComments() {
        val stream = LiveSentiment()
        stream.follow("app", page("a", "很好"), false)
        assertTrue(stream.snapshot.comments.isEmpty())
        stream.follow("app", page("a", "很好"), true)
        assertEquals(1, stream.snapshot.comments.size)
    }

    @Test fun unknownAndMixedAreNotCountedAsPositiveOrNegative() {
        val stream = LiveSentiment()
        stream.follow("app", page("a", "正", "负", "中", "混", "未知"), true)
        val batch = stream.pending()
        val labels = Sentiment.entries
        stream.apply(batch, batch.texts.filter { it.id != "p" }.mapIndexed { index, item -> item.id to LiveDecision(labels[index], .9) }.toMap())
        assertEquals(20, stream.snapshot.percent(Sentiment.POSITIVE))
        assertEquals(20, stream.snapshot.percent(Sentiment.NEGATIVE))
        assertEquals(1, stream.snapshot.count(Sentiment.UNCERTAIN))
    }

    @Test fun realHttpShapeHasOneQuestionPerTextAndNoHistoryOrOtherTasks() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            JSONObject().put("answers", JSONObject().put("c1", choice("positive", .9))).toString()))
        server.start()
        try {
            val client = JevSentimentClient(JevCommentConfig(server.url("/v1/systemone").toString(), "test", "jev-latest"))
            assertEquals(Sentiment.POSITIVE, client.analyze(listOf(LiveText("c1", "很好"))).getValue("c1").sentiment)
            val request = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(setOf("texts"), request.getJSONObject("state").keys().asSequence().toSet())
            assertEquals(setOf("c1"), request.getJSONObject("questions").keys().asSequence().toSet())
            assertEquals("很好", request.getJSONObject("state").getJSONObject("texts").getString("c1"))
            assertFalse(request.has("messages"))
        } finally { server.shutdown() }
    }

    @Test fun lowConfidenceAndInvalidAnswersAreHandledWithoutGuessing() {
        val response = JSONObject().put("answers", JSONObject().put("c1", choice("positive", .2)))
        val answer = JevSentimentClient.parse(response, setOf("c1"), .5).getValue("c1")
        assertEquals(Sentiment.UNCERTAIN, answer.sentiment)
        assertEquals(Sentiment.POSITIVE, answer.original)
        assertThrows(IllegalArgumentException::class.java) { JevSentimentClient.parse(response, setOf("c2"), .5) }
        response.getJSONObject("answers").getJSONObject("c1").put("confidence", 1.1)
        assertThrows(IllegalArgumentException::class.java) { JevSentimentClient.parse(response, setOf("c1"), .5) }
    }

    @Test fun realtimeFailureDoesNotSpendRequestsRetryingAnOldBatch() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(529))
        server.start()
        try {
            val client = JevSentimentClient(JevCommentConfig(server.url("/v1/systemone").toString(), "test", "jev-latest"))
            assertThrows(RuntimeException::class.java) { client.analyze(listOf(LiveText("c1", "很好"))) }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun choice(value: String, confidence: Double) = JSONObject()
        .put("type", "choice").put("choice", value).put("confidence", confidence)
}
