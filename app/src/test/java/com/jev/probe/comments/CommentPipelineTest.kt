package com.jev.probe.comments

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CommentPipelineTest {
    private val positive = CommentVerdict(Sentiment.POSITIVE, "画面表现", "游戏画面", "评论认可画面")
    private val negative = CommentVerdict(Sentiment.NEGATIVE, "游戏优化", "游戏性能", "评论抱怨掉帧")

    @Test fun repeatedScreensDoNotIncreaseSampleCount() {
        val first = CommentSession().append(listOf("画面不错", "掉帧严重", "画面不错"), "tv.danmaku.bili", 100)
        assertEquals(2, first.added)
        assertEquals(1, first.duplicates)
        val second = first.session.append(listOf("  掉帧严重  ", "希望修复"), "tv.danmaku.bili", 200)
        assertEquals(1, second.added)
        assertEquals(1, second.duplicates)
        assertEquals(listOf(1, 2, 3), second.session.samples.map { it.id })
        assertEquals(listOf(1, 1, 2), second.session.samples.map { it.batch })
        assertEquals(listOf(100L, 100L, 200L), second.session.samples.map { it.capturedAt })
    }

    @Test fun differentPlatformsCannotSilentlyMerge() {
        val session = CommentSession().append(listOf("不错"), "tv.danmaku.bili", 1).session
        assertThrows(IllegalArgumentException::class.java) { session.append(listOf("一般"), "com.max.xiaoheihe", 2) }
    }

    @Test fun statsIncludeUncertainButExcludePending() {
        val session = CommentSession().append(listOf("掉帧", "看不懂这个梗", "未分析"), "source", 1).session
            .applyVerdicts(mapOf(1 to negative, 2 to CommentVerdict(Sentiment.UNCERTAIN, "其他", "对象不明", "缺少上下文")))
        val stats = CommentStats(session.samples)
        assertEquals(2, stats.analyzed.size)
        assertEquals(50.0, stats.percent(Sentiment.NEGATIVE), 0.001)
        assertEquals(50.0, stats.percent(Sentiment.UNCERTAIN), 0.001)
        assertEquals(1, session.pending.size)
    }

    @Test fun removalUpdatesBothDenominatorAndTopics() {
        val session = CommentSession().append(listOf("画面不错", "掉帧严重"), "source", 1).session
            .applyVerdicts(mapOf(1 to positive, 2 to negative)).remove(2)
        val stats = CommentStats(session.samples)
        assertEquals(100.0, stats.percent(Sentiment.POSITIVE), 0.001)
        assertEquals(listOf("画面表现"), stats.topics.map { it.topic })
    }

    @Test fun sessionRoundTripPreservesBackgroundKindAndCompletedResults() {
        val original = CommentSession(title = "某游戏评测", kind = SampleKind.DANMAKU, context = "关注性能")
            .append(listOf("画面不错", "待分析文本"), "tv.danmaku.bili", 1720000000000L).session
            .applyVerdicts(mapOf(1 to positive))
        assertEquals(original, CommentCodec.decode(CommentCodec.encode(original)))
        assertEquals(listOf(2), CommentCodec.decode(CommentCodec.encode(original)).pending.map { it.id })
    }

    @Test fun parserAcceptsFencedJsonAndBindsResultsById() {
        val result = CommentCodec.parseAnalysis("```json\n${response(2, 1)}\n```", setOf(1, 2))
        assertEquals(setOf(1, 2), result.keys)
        assertEquals(Sentiment.POSITIVE, result.getValue(1).sentiment)
    }

    @Test fun incompleteDuplicateOrInventedIdsAreRejected() {
        listOf(response(1), response(1, 1), response(1, 7)).forEach { content ->
            assertThrows(IllegalArgumentException::class.java) { CommentCodec.parseAnalysis(content, setOf(1, 2)) }
        }
    }

    @Test fun invalidLabelIsNeverSilentlyCountedAsNeutral() {
        val content = response(1).replace("positive", "happy")
        assertThrows(IllegalStateException::class.java) { CommentCodec.parseAnalysis(content, setOf(1)) }
    }

    @Test fun uiNoiseIsRemovedButShortDanmakuRemains() {
        assertEquals("666\n这画面真不错", CommentText.cleanBlock(listOf("评论", "5分钟前", "666", "这画面真不错", "展开 3 条回复")))
    }

    @Test fun reviewSplitsOnlyAtBlankLines() {
        assertEquals(listOf("第一条\n还没说完", "第二条"), CommentText.splitSamples("第一条\r\n还没说完\r\n \t\r\n第二条"))
        assertNotEquals(CommentText.fingerprint("a b"), CommentText.fingerprint("ab"))
    }

    @Test fun emptyStatsHaveNoNaNAndMixedCountsOnce() {
        assertEquals(0.0, CommentStats(emptyList()).percent(Sentiment.NEGATIVE), 0.0)
        val sample = CommentSample(1, "主播讲得好但游戏优化差", 1, 1,
            CommentVerdict(Sentiment.MIXED, "游戏优化", "主播 / 游戏", "对主播正面，对游戏负面"))
        val stats = CommentStats(listOf(sample))
        assertEquals(1, stats.counts.values.sum())
        assertEquals(100.0, stats.percent(Sentiment.MIXED), 0.0)
    }

    @Test fun oversizedSamplesFailWithoutMutatingTheSession() {
        val session = CommentSession()
        assertThrows(IllegalArgumentException::class.java) {
            session.append(listOf("字".repeat(CommentSession.MAX_TEXT_LENGTH + 1)), "source", 1)
        }
        assertTrue(session.samples.isEmpty())
    }

    @Test fun realHttpClientSendsOnlyTheBatchAndParsesProviderResponse() {
        val server = MockWebServer()
        val output = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", JSONObject().put("content", response(2)))))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(output.toString()))
        server.start()
        try {
            val session = CommentSession(title = "测试视频", context = "游戏性能")
                .append(listOf("第一条", "第二条"), "source", 1).session
            val client = CommentClient(CommentApiConfig(server.url("/v1").toString(), "test-token", "test-model"))
            val result = client.analyze(session, listOf(session.samples[1]), listOf("画面表现"))
            assertEquals(setOf(2), result.keys)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            val sent = JSONObject(request.body.readUtf8())
            assertEquals("test-model", sent.getString("model"))
            val input = JSONObject(sent.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals("游戏性能", input.getString("background"))
            assertEquals(1, input.getJSONArray("comments").length())
            assertEquals(2, input.getJSONArray("comments").getJSONObject(0).getInt("id"))
        } finally { server.shutdown() }
    }

    private fun response(vararg ids: Int): String = JSONObject().put("comments", JSONArray().also { a ->
        ids.forEach { id -> a.put(JSONObject().put("id", id).put("sentiment", "positive")
            .put("topic", "画面表现").put("target", "游戏画面").put("reason", "评论认可画面")) }
    }).toString()
}
