package com.jev.probe.comments

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommentContextTest {
    private fun node(id: String, text: String = "", visible: Boolean = true, vararg children: CommentNode) =
        CommentNode("com.max.xiaoheihe:id/$id", text, visible, children = children.toList())
    private fun page(vararg rows: CommentNode) = node("root", children = arrayOf(
        node("title_name", "测试作者"), node("post_content_container", visible = false, children = arrayOf(
            node("tv_title", "新版本卡顿修好了吗？", false), node("tv_desc", "讨论新版本帧率和价格", false))),
        node("tv_all_comment", "评论 30"), *rows))
    private fun main(text: String, visible: Boolean = true) = node("vg_main_comment", children = arrayOf(
        node("bbs_name", "甲"), node("bbs_desc", "昨天·上海"), node("text", "100"), node("tv_comment", text, visible)))

    @Test fun onlyCommentBodiesAreSamplesAndRepliesKeepTheirOwnParents() {
        val tree = page(node("vg_comments_detail", children = arrayOf(main("优化还是很差"),
            node("rv_sub_comments", children = arrayOf(
                node("tv_sub_comment_content", "乙: 我这里流畅了 昨天14:49·云南\uFEFF"),
                node("tv_sub_comment_content", "丙 回复\u00a0乙: 我也一样 2天前·山东\uFEFF"))))))
        val result = CommentPageParser.parse("com.max.xiaoheihe", tree)
        assertTrue(result.recognized)
        assertEquals(listOf("优化还是很差", "我这里流畅了", "我也一样"), result.comments.map { it.text })
        assertEquals("优化还是很差", result.comments[1].parentText)
        assertEquals("我这里流畅了", result.comments[2].parentText)
        assertEquals("优化还是很差", result.comments[2].threadRoot)
        assertTrue(result.comments.none { it.contextMissing })
        assertEquals("讨论新版本帧率和价格", result.postBody)
        assertTrue(result.postKey.isNotBlank())
    }

    @Test fun invisibleCommentsAreContextOnlyAndMissingTargetsAreFlagged() {
        val result = CommentPageParser.parse("com.max.xiaoheihe", page(node("vg_comments_detail", children = arrayOf(
            main("主评论", false), node("rv_sub_comments", children = arrayOf(
                node("tv_sub_comment_content", "乙 回复 不在本屏的人: 你说得不对 昨天12:00·广东"),
                node("tv_sub_comment_content", "丙: 不可见", false)))))))
        assertEquals(1, result.comments.size)
        assertEquals("你说得不对", result.comments.single().text)
        assertTrue(result.comments.single().contextMissing)
        assertEquals("", result.comments.single().parentText)
        assertEquals("主评论", result.comments.single().threadRoot)
    }

    @Test fun commentsWithPicturesDoNotPretendTheirContextIsComplete() {
        val result = CommentPageParser.parse("com.max.xiaoheihe", page(node("vg_comments_detail", children = arrayOf(
            node("vg_main_comment", children = arrayOf(node("tv_comment", "就是这个"),
                node("ll_pic", children = arrayOf(node("iv_image")))))))))
        assertTrue(result.comments.single().contextMissing)
    }

    @Test fun scrollingBetweenPostAndCommentsKeepsTheSamePostIdentity() {
        val content = node("post_like", children = arrayOf(node("bbs_name", "同一作者"),
            node("post_content_container", children = arrayOf(node("tv_title", "同一标题"), node("tv_desc", "同一正文")))))
        val top = node("root", children = arrayOf(content, node("title_name", "")))
        val scrolled = node("root", children = arrayOf(content.copy(visible = false), node("title_name", "同一作者")))
        val topPage = CommentPageParser.parse("com.max.xiaoheihe", top)
        val scrolledPage = CommentPageParser.parse("com.max.xiaoheihe", scrolled)
        assertTrue(topPage.postKey.isNotBlank())
        assertTrue(scrolledPage.postKey.isNotBlank())
        assertEquals(topPage.postKey, scrolledPage.postKey)
    }

    @Test fun sameTextUnderDifferentParentsIsNotMerged() {
        val first = CommentSession().appendCaptured(listOf(CapturedComment("确实", "很好"), CapturedComment("确实", "很差")), "source", 1)
        assertEquals(2, first.added)
        assertEquals(0, first.session.appendCaptured(listOf(CapturedComment("确实", "很好")), "source", 2).added)
        assertEquals(first.session, CommentCodec.decode(CommentCodec.encode(first.session)))
    }

    @Test fun changingPostContextInvalidatesOldJudgmentsAndRejectsAnotherPost() {
        val session = CommentSession(title = "标题", context = "原正文", sourcePackage = "source", postKey = "post-a")
            .append(listOf("确实"), "source", 1).session.applyVerdicts(mapOf(1 to verdict()))
        val edited = session.withContext("标题", "补全了原文", true, "post-a", "source")
        assertEquals(1, edited.pending.size)
        assertThrows(IllegalArgumentException::class.java) { session.withContext("另一个标题", "正文", true, "post-b", "source") }
    }

    @Test fun jevReceivesPostAndExplicitThreadWithoutOtherSampleLeakage() {
        val session = CommentSession(title = "新版本评测", context = "新版本提升了帧率")
            .appendCaptured(listOf(CapturedComment("不同意", "已经不卡了", "新版本很好"), CapturedComment("无关内容")), "source", 1).session
        val body = JevCommentClient.request(session, listOf(session.samples[0]), "jev")
        val state = body.getJSONObject("state")
        assertEquals("新版本提升了帧率", state.getString("background"))
        assertTrue(state.getBoolean("post_context_confirmed"))
        val comment = state.getJSONArray("comments").getJSONObject(0)
        assertEquals("已经不卡了", comment.getString("parent_comment"))
        assertEquals("新版本很好", comment.getString("thread_root"))
        assertFalse(body.toString().contains("无关内容"))
        assertTrue(body.getJSONObject("questions").getJSONObject("c1_sentiment").getString("instructions").contains("explicit parent_comment"))
    }

    @Test fun incompleteContextCannotCountAsConfidentPositive() {
        val session = CommentSession(context = "已确认帖子内容").appendCaptured(listOf(CapturedComment("确实", contextMissing = true)), "source", 1).session
        val guarded = CommentContext.guard(session, session.samples, mapOf(1 to verdict())).getValue(1)
        assertEquals(Sentiment.UNCERTAIN, guarded.sentiment)
        assertEquals(Sentiment.UNCERTAIN, CommentContext.guard(session.copy(contextConfirmed = false), session.samples.map { it.copy(contextMissing = false) }, mapOf(1 to verdict())).getValue(1).sentiment)
    }

    @Test fun legacySamplesRequireContextReviewInsteadOfKeepingOldStatistics() {
        val old = CommentSession(context = "背景").append(listOf("不错"), "source", 1).session.applyVerdicts(mapOf(1 to verdict()))
        val json = JSONObject(CommentCodec.encode(old)).put("version", 1).apply { remove("contextConfirmed") }
        val restored = CommentCodec.decode(json.toString())
        assertFalse(restored.contextConfirmed)
        assertTrue(restored.samples.single().contextMissing)
        assertTrue(restored.analyzed.isEmpty())
    }

    @Test fun manualRectangleExcludesOutsideAndPartlyClippedNodes() {
        val region = NodeArea(100, 200, 900, 500)
        val tree = CommentNode(children = listOf(
            CommentNode(text = "昵称", area = NodeArea(10, 20, 90, 40)),
            CommentNode(text = "正文", area = NodeArea(100, 250, 800, 300)),
            CommentNode(text = "底栏", area = NodeArea(100, 480, 800, 550))))
        assertEquals(listOf("正文"), CommentPageParser.inRegion(tree, region).map { it.text })
        assertFalse(CommentPageParser.parse("tv.danmaku.bili", tree).recognized)
    }

    private fun verdict() = CommentVerdict(Sentiment.POSITIVE, "性能", "产品", "测试", CommentEngine.JEV)
}
