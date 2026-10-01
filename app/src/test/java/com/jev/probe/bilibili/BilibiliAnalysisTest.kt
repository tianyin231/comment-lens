package com.jev.probe.bilibili

import com.jev.probe.comments.JevCommentConfig
import com.jev.probe.comments.JevSentimentClient
import com.jev.probe.comments.LiveDecision
import com.jev.probe.comments.LiveText
import com.jev.probe.comments.Sentiment
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic comment fixtures and localhost Jev responses, never production B站 data. */
class BilibiliAnalysisTest {
    private val request = BilibiliRequest(BilibiliLink.Video("BV1xx411c7mD"), 30, BilibiliSort.HOT)
    private val positive = LiveDecision(Sentiment.POSITIVE, .9)
    private val negative = LiveDecision(Sentiment.NEGATIVE, .9)
    private fun comments(count: Int) = (1..count).map { BilibiliComment(it.toString(), "合成测试评论 $it") }
    private class Source(private val comments: List<BilibiliComment>,
        override val availability: BilibiliAvailability = BilibiliAvailability.Available) : BilibiliCommentSource {
        var calls = 0
        override fun readPage(request: BilibiliRequest, cursor: String?): BilibiliCommentPage {
            calls++
            return BilibiliCommentPage(comments, request.sort, false)
        }
    }

    @Test fun blockedWorkflowCallsNeitherSourceNorJudgeAndReportsNoAnalysis() {
        val source = Source(comments(3), BilibiliAvailability.Blocked("合成未授权来源"))
        var judgeCalls = 0
        val result = BilibiliAnalysis(source) { judgeCalls++; error("must not analyze") }.run(request)
        assertEquals(0, source.calls)
        assertEquals(0, judgeCalls)
        assertEquals(BilibiliLoadStatus.BLOCKED, result.load.status)
        assertEquals(BilibiliAnalysisStatus.NOT_STARTED, result.status)
        assertTrue(result.load.comments.isEmpty())
        assertTrue(result.decisions.isEmpty())
        assertEquals(0, result.textCount)
        assertNull(result.load.actualSort)
        assertNull(result.load.reportedTotal)
    }

    @Test fun productionDefaultAlsoCannotInvokeJudge() {
        var judgeCalls = 0
        val result = BilibiliAnalysis(UnavailableBilibiliSource) { judgeCalls++; error("must not analyze") }.run(request)
        assertEquals(0, judgeCalls)
        assertEquals(BilibiliLoadStatus.BLOCKED, result.load.status)
        assertTrue(result.decisions.isEmpty())
    }

    @Test fun blankImageCommentBodiesCountAsReadButNeverGoToJudge() {
        var judgeCalls = 0
        val result = BilibiliAnalysis(Source(listOf(BilibiliComment("1", ""), BilibiliComment("2", " \n")))) {
            judgeCalls++; error("must not analyze blank text")
        }.run(request)
        assertEquals(2, result.load.comments.size)
        assertEquals(0, result.textCount)
        assertEquals(0, judgeCalls)
        assertEquals(BilibiliAnalysisStatus.NO_TEXT, result.status)
        assertTrue(result.decisions.isEmpty())
    }

    @Test fun emptySourceDoesNotInvokeJudgeOrClaimAnEmotionRatio() {
        var calls = 0
        val result = BilibiliAnalysis(Source(emptyList())) { calls++; emptyMap() }.run(request)
        assertEquals(BilibiliLoadStatus.EMPTY, result.load.status)
        assertEquals(BilibiliAnalysisStatus.NOT_STARTED, result.status)
        assertEquals(0, calls)
        assertEquals(0, result.percent(Sentiment.POSITIVE))
        assertTrue(result.decisions.isEmpty())
    }

    @Test fun missingJudgeConfigurationRetainsFetchedTextWithoutDecisions() {
        val result = BilibiliAnalysis(Source(comments(2)), null).run(request)
        assertEquals(BilibiliAnalysisStatus.NEEDS_CONFIG, result.status)
        assertEquals(2, result.load.comments.size)
        assertTrue(result.decisions.isEmpty())
    }

    @Test fun equalTextsUploadOnceButBothCommentIdsParticipateInStatistics() {
        val source = Source(listOf(BilibiliComment("1", " 相同合成文字 "), BilibiliComment("2", "相同合成文字"),
            BilibiliComment("3", "另一条合成文字"), BilibiliComment("4", "")))
        val sent = mutableListOf<LiveText>()
        val result = BilibiliAnalysis(source) { items ->
            sent += items
            items.associate { it.id to if (it.text == "相同合成文字") positive else negative }
        }.run(request)
        assertEquals(BilibiliAnalysisStatus.COMPLETE, result.status)
        assertEquals(listOf("相同合成文字", "另一条合成文字"), sent.map { it.text })
        assertEquals(setOf("1", "2", "3"), result.decisions.keys)
        assertEquals(2, result.count(Sentiment.POSITIVE))
        assertEquals(1, result.count(Sentiment.NEGATIVE))
        assertEquals(66, result.percent(Sentiment.POSITIVE))
        assertEquals(33, result.percent(Sentiment.NEGATIVE))
        assertEquals(4, result.load.comments.size)
        assertEquals(3, result.textCount)
    }

    @Test fun partialSourceFailureCanAnalyzeOnlyPreviouslyAcquiredComments() {
        val source = object : BilibiliCommentSource {
            override val availability = BilibiliAvailability.Available
            override fun readPage(request: BilibiliRequest, cursor: String?): BilibiliCommentPage =
                if (cursor == null) BilibiliCommentPage(comments(2), request.sort, true, "next")
                else throw BilibiliSourceException("合成读取阻碍")
        }
        val sent = mutableListOf<LiveText>()
        val result = BilibiliAnalysis(source) { items -> sent += items; items.associate { it.id to positive } }.run(request)
        assertEquals(BilibiliLoadStatus.PARTIAL, result.load.status)
        assertEquals(BilibiliAnalysisStatus.COMPLETE, result.status)
        assertEquals(2, sent.size)
        assertEquals(2, result.decisions.size)
        assertEquals("合成读取阻碍", result.load.reason)
    }

    @Test fun malformedJudgeBatchStopsAndKeepsOnlyEarlierCompletedDecisions() {
        var calls = 0
        val result = BilibiliAnalysis(Source(comments(20))) { items ->
            calls++
            if (calls == 1) items.associate { it.id to positive } else emptyMap()
        }.run(request)
        assertEquals(BilibiliAnalysisStatus.PARTIAL, result.status)
        assertEquals(2, calls)
        assertEquals((1..8).map { it.toString() }.toSet(), result.decisions.keys)
        assertEquals(20, result.load.comments.size)
    }

    @Test fun httpPaymentAndRateLimitsStopFurtherBatchesAndKeepEarlierResults() {
        listOf(402, 429).forEach { status ->
            val server = MockWebServer()
            val answers = JSONObject()
            (1..8).forEach { n -> answers.put("c$n", JSONObject().put("type", "choice")
                .put("choice", "positive").put("confidence", .9)) }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("answers", answers).toString()))
            server.enqueue(MockResponse().setResponseCode(status).setBody("synthetic private provider detail"))
            server.start()
            try {
                val client = JevSentimentClient(JevCommentConfig(server.url("/v1/systemone").toString(),
                    "synthetic-test-token", "synthetic-test-model"))
                val result = BilibiliAnalysis(Source(comments(20)), client::analyze).run(request)
                assertEquals(BilibiliAnalysisStatus.PARTIAL, result.status)
                assertEquals(2, server.requestCount)
                assertEquals(8, result.decisions.size)
                assertEquals(20, result.load.comments.size)
                assertTrue(result.reason.contains(status.toString()))
                assertFalse(result.reason.contains("private provider detail"))
                assertEquals(100, result.percent(Sentiment.POSITIVE))
            } finally { server.shutdown() }
        }
    }

    @Test fun cancellationDuringJudgeDiscardsLateAnswersWithoutPublishingThem() {
        var cancelled = false
        var calls = 0
        val progress = mutableListOf<BilibiliAnalysisResult>()
        val result = BilibiliAnalysis(Source(comments(20))) { items ->
            calls++; cancelled = true; items.associate { it.id to positive }
        }.run(request, { cancelled }, progress::add)
        assertEquals(BilibiliAnalysisStatus.CANCELLED, result.status)
        assertEquals(1, calls)
        assertTrue(result.decisions.isEmpty())
        assertTrue(progress.all { it.decisions.isEmpty() })
    }

    @Test fun cancellationBeforeNextBatchPreservesFinishedResultsAndStopsNewRequests() {
        var cancelled = false
        var calls = 0
        val result = BilibiliAnalysis(Source(comments(20))) { items ->
            calls++; items.associate { it.id to positive }
        }.run(request, { cancelled }) { value -> if (value.decisions.size == 8) cancelled = true }
        assertEquals(BilibiliAnalysisStatus.CANCELLED, result.status)
        assertEquals(1, calls)
        assertEquals(8, result.decisions.size)
    }

    @Test fun cancellationBeforeWorkflowCallsNeitherSourceNorJudge() {
        val source = Source(comments(2))
        var calls = 0
        val result = BilibiliAnalysis(source) { calls++; emptyMap() }.run(request, { true })
        assertEquals(BilibiliAnalysisStatus.CANCELLED, result.status)
        assertEquals(0, source.calls)
        assertEquals(0, calls)
    }
}
