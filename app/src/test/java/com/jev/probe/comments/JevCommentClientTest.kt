package com.jev.probe.comments

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class JevCommentClientTest {
    private fun session() = CommentSession(title = "游戏评测", context = "关注画面和定价", topics = listOf("画面", "定价"))
        .append(listOf("这画面真不错", "定价太高了"), "tv.danmaku.bili", 100).session

    @Test fun oneDecisionsRequestCarriesThreeScopedQuestionsForEachComment() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response(1, 2).toString()))
        server.start()
        try {
            val input = session()
            val client = JevCommentClient(JevCommentConfig(server.url("/api/alpha/decisions").toString(), "test-token", "typesafe/jev-1.13"))
            val result = client.analyze(input, input.samples, emptyList())
            assertEquals(setOf(1, 2), result.keys)
            assertTrue(result.values.all { it.engine == CommentEngine.JEV })
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/alpha/decisions", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("typesafe/jev-1.13", body.getString("model"))
            assertFalse(body.has("messages"))
            assertEquals("关注画面和定价", body.getJSONObject("state").getString("background"))
            val questions = body.getJSONObject("questions")
            assertEquals(6, questions.length())
            assertEquals("choice", questions.getJSONObject("c2_sentiment").getString("type"))
            assertTrue(questions.getJSONObject("c2_sentiment").getString("instructions").contains("id is 2"))
            assertTrue(questions.getJSONObject("c1_topic").getJSONObject("criteria").getString("topic_1").contains("定价"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun lowConfidenceBecomesUncertainWithoutLosingOriginalDecision() {
        val body = response(1)
        body.getJSONObject("answers").getJSONObject("c1_sentiment").put("confidence", 0.2)
        val verdict = JevCommentClient.parse(body, session(), setOf(1), 0.5).getValue(1)
        assertEquals(Sentiment.UNCERTAIN, verdict.sentiment)
        assertEquals(Sentiment.POSITIVE, verdict.modelSentiment)
        assertEquals(0.2, verdict.confidence!!, 0.0)
        assertEquals(0.8, verdict.probabilities.getValue("positive"), 0.0)
        assertEquals(100.0, CommentStats(listOf(session().samples[0].copy(verdict = verdict))).percent(Sentiment.UNCERTAIN), 0.0)
    }

    @Test fun missingAnswersUnknownChoicesAndInvalidProbabilitiesAreRejected() {
        val missing = response(1).apply { getJSONObject("answers").remove("c1_topic") }
        val unknown = response(1).apply { getJSONObject("answers").getJSONObject("c1_sentiment").put("choice", "happy") }
        val invalid = response(1).apply { getJSONObject("answers").getJSONObject("c1_sentiment").getJSONObject("probabilities").put("positive", 1.4) }
        val extra = response(1, 2)
        listOf(missing, unknown, invalid, extra).forEach {
            assertThrows(IllegalArgumentException::class.java) { JevCommentClient.parse(it, session(), setOf(1), 0.5) }
        }
    }

    @Test fun unknownTopicsAndTargetsDoNotBecomeConfidentFacts() {
        val body = response(1)
        body.getJSONObject("answers").getJSONObject("c1_topic").put("confidence", 0.1)
        body.getJSONObject("answers").getJSONObject("c1_target").put("confidence", 0.1)
        val verdict = JevCommentClient.parse(body, session(), setOf(1), 0.5).getValue(1)
        assertEquals("其他", verdict.topic)
        assertEquals("对象不明", verdict.target)
        assertEquals(Sentiment.POSITIVE, verdict.sentiment)
    }

    @Test fun persistedJevResultsKeepConfidenceProbabilitiesAndTopicDefinitions() {
        val input = session()
        val result = input.applyVerdicts(JevCommentClient.parse(response(1, 2), input, setOf(1, 2), 0.5))
        assertEquals(result, CommentCodec.decode(CommentCodec.encode(result)))
        val legacy = JSONObject(CommentCodec.encode(input)).apply { remove("topics") }
        assertEquals(CommentTopics.defaults, CommentCodec.decode(legacy.toString()).topics)
    }

    @Test fun onlyRequestedSamplesAreSentAndCustomTopicsRemainStable() {
        val input = session()
        val body = JevCommentClient.request(input, listOf(input.samples[1]), "jev-latest")
        assertEquals(1, body.getJSONObject("state").getJSONArray("comments").length())
        assertFalse(body.getJSONObject("questions").has("c1_sentiment"))
        assertEquals(listOf("性能", "定价"), CommentTopics.parse("性能，定价,性能\n其他"))
        assertThrows(IllegalArgumentException::class.java) { CommentTopics.parse("其他") }
    }

    private fun response(vararg ids: Int): JSONObject {
        val answers = JSONObject()
        ids.forEach { id ->
            answers.put("c${id}_sentiment", choice("positive", linkedMapOf("positive" to 0.8, "negative" to 0.05, "neutral" to 0.05, "mixed" to 0.05, "uncertain" to 0.05)))
            answers.put("c${id}_topic", choice("topic_0", linkedMapOf("topic_0" to 1.0, "topic_1" to 0.0, "other" to 0.0, "non_comment" to 0.0)))
            answers.put("c${id}_target", choice("product", linkedMapOf("product" to 1.0, "creator" to 0.0, "content" to 0.0, "stream" to 0.0, "community" to 0.0, "multiple" to 0.0, "unknown" to 0.0)))
        }
        return JSONObject().put("model", "jev-1.13.0").put("answers", answers)
    }
    private fun choice(value: String, probabilities: Map<String, Double>): JSONObject = JSONObject()
        .put("type", "choice").put("choice", value).put("confidence", 0.9).put("probabilities", JSONObject(probabilities))
}
