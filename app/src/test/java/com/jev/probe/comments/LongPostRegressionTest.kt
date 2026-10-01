package com.jev.probe.comments

import org.junit.Assert.*
import org.junit.Test

/** Synthetic accessibility trees: no account data, real posts, or provider requests. */
class LongPostRegressionTest {
    private val pkg = "com.max.xiaoheihe"
    private val positive = LiveDecision(Sentiment.POSITIVE, .9)

    private fun node(id: String, text: String = "", visible: Boolean = true, vararg children: CommentNode) =
        CommentNode("$pkg:id/$id", text, visible, children = children.toList())

    private fun content(title: String = "", vararg paragraphs: String) = node("post_content_container",
        children = arrayOf(node("tv_title", title), *paragraphs.map { node("tv_desc", it) }.toTypedArray()))

    private fun row(text: String) = node("vg_comments_detail", children = arrayOf(
        node("vg_main_comment", children = arrayOf(node("bbs_name", "评论作者"), node("tv_comment", text)))))

    private fun detail(content: CommentNode, vararg comments: String, author: String = "帖文作者") =
        node("root", children = arrayOf(node("title_name", author), content,
            node("tv_all_comment", "全部评论"), *comments.map(::row).toTypedArray()))

    private fun parse(tree: CommentNode) = CommentPageParser.parse(pkg, tree)

    @Test fun rootLevelUntitledLongBodyCanBeFollowedBeforeAnyCommentIsVisible() {
        val body = "合成的长文章正文。".repeat(400)
        val page = parse(detail(content("", body)))
        assertTrue("A readable body must establish a post without a title", page.postKey.isNotBlank())
        assertEquals(body, page.postBody)
        val stream = LiveSentiment()
        assertTrue(stream.follow(pkg, page, false))
        assertEquals(body.take(3000), stream.snapshot.post!!.text)
        assertEquals(listOf("p"), stream.pending().texts.map { it.id })
    }

    @Test fun twoUntitledBodiesDoNotShareCommentsOrJudgments() {
        val first = parse(detail(content("", "第一篇无标题正文"), "第一帖评论"))
        val second = parse(detail(content("", "第二篇无标题正文"), "第二帖评论"))
        assertTrue(first.postKey.isNotBlank())
        assertNotEquals(first.postKey, second.postKey)
        val stream = LiveSentiment()
        stream.follow(pkg, first, true)
        val old = stream.pending()
        stream.follow(pkg, second, true)
        assertFalse(stream.apply(old, old.texts.associate { it.id to positive }))
        assertEquals(listOf("第二帖评论"), stream.snapshot.comments.map { it.text })
        assertTrue(stream.snapshot.judged.isEmpty())
        assertNull(stream.snapshot.post!!.decision)
    }

    @Test fun sameAuthorAndTitleWithDifferentBodiesRemainSeparateAndRestoreOwnCache() {
        val first = parse(detail(content("相同标题", "第一篇的正文"), "第一帖评论"))
        val second = parse(detail(content("相同标题", "第二篇的正文"), "第二帖评论"))
        assertNotEquals(first.postKey, second.postKey)
        val stream = LiveSentiment()
        stream.follow(pkg, first, true)
        val batch = stream.pending()
        assertTrue(stream.apply(batch, batch.texts.associate { it.id to positive }))
        stream.follow(pkg, second, true)
        assertEquals(listOf("第二帖评论"), stream.snapshot.comments.map { it.text })
        assertTrue(stream.snapshot.judged.isEmpty())
        stream.follow(pkg, first, true)
        assertEquals(listOf("第一帖评论"), stream.snapshot.comments.map { it.text })
        assertTrue(stream.pending().texts.isEmpty())
    }

    @Test fun laterBodyParagraphsContributeToTextAndIdentity() {
        val first = parse(detail(content("相同标题", "相同首段", "后续段落一")))
        val second = parse(detail(content("相同标题", "相同首段", "后续段落二")))
        assertEquals("相同首段\n后续段落一", first.postBody)
        assertNotEquals(first.postKey, second.postKey)
    }

    @Test fun identityUsesFullLongBodyBeyondTheAnalysisLengthLimit() {
        val shared = "合成正文".repeat(1000)
        val first = parse(detail(content("", shared + "第一篇结尾"), "第一帖评论"))
        val second = parse(detail(content("", shared + "第二篇结尾"), "第二帖评论"))
        assertNotEquals(first.postKey, second.postKey)
        val stream = LiveSentiment()
        stream.follow(pkg, first, true)
        val old = stream.pending()
        val firstPostText = stream.snapshot.post!!.text
        stream.follow(pkg, second, true)
        assertEquals("Analysis text may be truncated while identities remain distinct",
            firstPostText, stream.snapshot.post!!.text)
        assertEquals(listOf("第二帖评论"), stream.snapshot.comments.map { it.text })
        assertFalse(stream.apply(old, old.texts.associate { it.id to positive }))
    }

    @Test fun detailWithNestedAndRootBodyCandidatesRejectsAmbiguousIdentity() {
        val tree = detail(node("post_like", children = arrayOf(node("bbs_name", "第一作者"),
            content("第一帖", "第一正文"))), "某帖评论").let { original ->
            original.copy(children = original.children + content("第二帖", "第二正文"))
        }
        val page = parse(tree)
        assertTrue(page.postKey.isBlank())
        assertFalse(page.recognized)
        assertTrue(page.comments.isEmpty())
    }

    @Test fun commentScrollRetainsHiddenPostIdentityResultsAndDeduplication() {
        val original = content("", "无标题帖文正文")
        val top = parse(detail(original, "已读评论"))
        val hidden = original.copy(visible = false, children = original.children.map { it.copy(visible = false) })
        val scrolled = parse(detail(hidden, "已读评论", "新增评论"))
        assertTrue(top.postKey.isNotBlank())
        assertEquals(top.postKey, scrolled.postKey)
        val stream = LiveSentiment()
        stream.follow(pkg, top, true)
        val batch = stream.pending()
        stream.apply(batch, batch.texts.associate { it.id to positive })
        assertFalse(stream.follow(pkg, scrolled, true))
        assertEquals(listOf("已读评论", "新增评论"), stream.snapshot.comments.map { it.text })
        assertEquals(1, stream.snapshot.judged.size)
        assertEquals(listOf("新增评论"), stream.pending().texts.map { it.text })
    }

    @Test fun bodyMetadataOutsideSelectorsIsExcludedFromPostText() {
        val body = content("标题", "正文").copy(children = content("标题", "正文").children + listOf(
            node("bbs_name", "昵称噪音"), node("bbs_desc", "昨天·上海"), node("tv_like", "999")))
        val page = parse(detail(body, "真实评论"))
        assertEquals("正文", page.postBody)
        assertEquals(listOf("真实评论"), page.comments.map { it.text })
    }

    @Test fun rootLevelHomePreviewWithoutDetailMarkerHasNoPostIdentity() {
        val home = node("root", children = arrayOf(content("推荐帖", "推荐卡片内容")))
        val page = parse(home)
        assertTrue("A home preview must not display a post bar", page.postKey.isBlank())
        assertFalse(page.recognized)
    }

    @Test fun singleHomeCardWithPostLikeIsNotMistakenForDetailPage() {
        val home = node("root", children = arrayOf(node("post_like", children = arrayOf(
            node("bbs_name", "推荐作者"), content("推荐标题", "推荐正文")))))
        val page = parse(home)
        assertTrue(page.postKey.isBlank())
        assertFalse(page.recognized)
    }

    @Test fun authorAndCommentRowsWithoutAnyPostTextDoNotInventIdentity() {
        val page = parse(detail(content("", ""), "仅评论可见"))
        assertTrue(page.postKey.isBlank())
        val stream = LiveSentiment()
        assertFalse(stream.follow(pkg, page, true))
        assertTrue(stream.snapshot.comments.isEmpty())
    }
}
