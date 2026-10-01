package com.jev.probe.capture

import com.jev.probe.comments.CommentNode
import com.jev.probe.comments.CommentPageParser
import com.jev.probe.comments.NodeArea
import org.junit.Assert.*
import org.junit.Test

class XiaoheiheNodeReaderTest {
    private val pkg = "com.max.xiaoheihe"
    private fun node(id: String, text: String = "", visible: Boolean = true, vararg children: CommentNode) =
        CommentNode("$pkg:id/$id", text, visible, children = children.toList())
    private fun body(title: String = "", vararg paragraphs: String) = node("post_content_container",
        children = arrayOf(node("tv_title", title), *paragraphs.map { node("tv_desc", it) }.toTypedArray()))
    private fun row(text: String, visible: Boolean = true) = node("vg_comments_detail", visible = visible,
        children = arrayOf(node("vg_main_comment", children = arrayOf(node("tv_comment", text)))))
    private fun detail(vararg children: CommentNode) = node("root", children = arrayOf(
        node("title_name", "作者"), node("tv_all_comment", "全部评论"), *children))

    private fun webArticle(): CommentNode {
        fun text(value: String, top: Int, role: String = "genericContainer") = CommentNode(text = value,
            area = NodeArea(10, top, 190, top + 25), className = "android.widget.TextView", role = role)
        val article = CommentNode(className = "android.view.View", children = listOf(
            text("合成长文标题", 30), text("合成作者", 65), text("10-01", 95, "paragraph"),
            CommentNode(text = "关注", className = "android.widget.Button", role = "button"),
            text("合成长文正文", 150, "paragraph")))
        return CommentNode(className = "android.webkit.WebView", area = NodeArea(0, 20, 200, 400),
            children = listOf(CommentNode(id = "app", className = "android.view.View", children = listOf(article))))
    }

    /** Each selector returns owned handles, just like an Android accessibility query. */
    private class Handle(val node: CommentNode)
    private class Access(private val failingText: String? = null, private val webNodes: List<CommentNode> = emptyList()) :
        XiaoheiheNodeReader.NodeAccess<Handle> {
        val acquired = mutableListOf<Handle>()
        val recycled = mutableListOf<Handle>()
        val queries = mutableListOf<Pair<String, String>>()
        var webQueries = 0
        override fun find(parent: Handle, id: String): List<Handle> {
            queries += parent.node.id.substringAfterLast('/') to id
            return parent.node.named(id).map { Handle(it) }.toList().also(acquired::addAll)
        }
        override fun snapshot(node: Handle): CommentNode {
            if (node.node.text == failingText) throw IllegalStateException("synthetic inaccessible node")
            return node.node.copy(children = emptyList())
        }
        override fun hasChildren(node: Handle) = node.node.children.isNotEmpty()
        override fun recycle(node: Handle) { recycled += node }
        override fun webViews(root: Handle) = webNodes.also { webQueries++ }
        fun assertAllReleased() {
            assertEquals(acquired.size, recycled.size)
            assertEquals(acquired.toSet(), recycled.toSet())
            assertEquals(recycled.size, recycled.toSet().size)
        }
    }

    @Test fun longBodyOutsidePostLikeIsReadByRootSelectorAndParsedWithoutTitle() {
        val tree = detail(node("post_like", children = arrayOf(node("bbs_name", "作者"))),
            node("article_layout", children = arrayOf(body("", "第一段", "第二段"))), row("评论"))
        val access = Access()
        val page = CommentPageParser.parse(pkg, XiaoheiheNodeReader.read(Handle(tree), access))
        assertEquals("第一段\n第二段", page.postBody)
        assertTrue(page.postKey.isNotBlank())
        assertTrue(page.recognized)
        assertEquals(listOf("评论"), page.comments.map { it.text })
        assertTrue(access.queries.contains("root" to "post_content_container"))
        assertFalse(access.queries.contains("post_like" to "post_content_container"))
        access.assertAllReleased()
    }

    @Test fun existingShortPostLayoutKeepsPostAuthorAndText() {
        val tree = detail(node("post_like", children = arrayOf(node("bbs_name", "作者"), body("短标题", "短正文"))))
        val access = Access()
        val snapshot = XiaoheiheNodeReader.read(Handle(tree), access)
        val page = CommentPageParser.parse(pkg, snapshot)
        assertEquals("短标题", page.postTitle)
        assertEquals("短正文", page.postBody)
        assertEquals("作者", snapshot.value("bbs_name"))
        assertTrue(page.postKey.isNotBlank())
        access.assertAllReleased()
    }

    @Test fun invisiblePostTextIsKeptWhileInvisibleCommentRowsAreSkipped() {
        val hiddenBody = body("", "滚出屏幕的正文").let { original ->
            original.copy(visible = false, children = original.children.map { it.copy(visible = false) })
        }
        val access = Access()
        val page = CommentPageParser.parse(pkg, XiaoheiheNodeReader.read(Handle(detail(
            hiddenBody, row("可见评论"), row("不可见评论", false))), access))
        assertEquals("滚出屏幕的正文", page.postBody)
        assertTrue(page.postKey.isNotBlank())
        assertEquals(listOf("可见评论"), page.comments.map { it.text })
        access.assertAllReleased()
    }

    @Test fun homeCardsWithoutDetailControlsStopBeforeBodyQueries() {
        val home = node("root", children = arrayOf(node("post_like", children = arrayOf(
            node("bbs_name", "推荐作者"), body("推荐帖", "卡片内容")))))
        val access = Access(webNodes = listOf(webArticle()))
        val page = CommentPageParser.parse(pkg, XiaoheiheNodeReader.read(Handle(home), access))
        assertTrue(page.postKey.isBlank())
        assertEquals(listOf("title_name", "tv_all_comment", "tv_tab_title"), access.queries.map { it.second })
        assertEquals(0, access.webQueries)
        access.assertAllReleased()
    }

    @Test fun authorToolbarKeepsWebArticleIdentityAndOcrWhenTabsDisappear() {
        val top = node("root", children = arrayOf(node("tv_tab_title", "正文"), node("tv_tab_title", "评论")))
        val scrolled = node("root", children = arrayOf(node("title_name", "合成作者")))
        val access = Access(webNodes = listOf(webArticle()))
        val first = CommentPageParser.parse(pkg, XiaoheiheNodeReader.read(Handle(top), access))
        val next = CommentPageParser.parse(pkg, XiaoheiheNodeReader.read(Handle(scrolled), access))
        assertTrue(first.postKey.isNotBlank())
        assertEquals("标签隐藏后仍应识别为同一篇长文", first.postKey, next.postKey)
        assertEquals("合成长文正文", next.postBody)
        assertTrue(next.postTextAvailable)
        assertTrue("滚动后的正文仍可进行 OCR", next.ocrAreas.isNotEmpty())
        assertEquals(2, access.webQueries)
        access.assertAllReleased()
    }

    @Test fun authorToolbarDoesNotAcceptAmbiguousOrUnrecognizedWebViews() {
        val scrolled = node("root", children = arrayOf(node("title_name", "合成作者")))
        listOf(listOf(webArticle(), webArticle()), listOf(webArticle().copy(children = emptyList()))).forEach { webs ->
            val access = Access(webNodes = webs)
            val page = CommentPageParser.parse(pkg, XiaoheiheNodeReader.read(Handle(scrolled), access))
            assertTrue(page.postKey.isBlank())
            assertTrue(page.ocrAreas.isEmpty())
            access.assertAllReleased()
        }
    }

    @Test fun multipleBodyCandidatesCannotSelectFirstPostAndAllHandlesAreReleased() {
        val tree = detail(body("第一帖", "第一正文"), body("第二帖", "第二正文"), row("某帖评论"))
        val access = Access()
        val page = CommentPageParser.parse(pkg, XiaoheiheNodeReader.read(Handle(tree), access))
        assertTrue(page.postKey.isBlank())
        assertFalse(page.recognized)
        assertTrue(page.comments.isEmpty())
        access.assertAllReleased()
    }

    @Test fun unrelatedRootDescriptionsDoNotLeakIntoArticle() {
        val tree = detail(body("", "真正正文"), node("recommendation", children = arrayOf(
            node("tv_desc", "推荐区噪音"))), node("bbs_desc", "昨天·上海"))
        val access = Access()
        val page = CommentPageParser.parse(pkg, XiaoheiheNodeReader.read(Handle(tree), access))
        assertEquals("真正正文", page.postBody)
        assertFalse(access.queries.contains("root" to "tv_desc"))
        access.assertAllReleased()
    }

    @Test fun selectorReleasesAllReturnedHandlesWhenSnapshotThrows() {
        val tree = node("root", children = arrayOf(node("title_name", "first"),
            node("title_name", "unreadable"), node("title_name", "last")))
        val access = Access("unreadable")
        assertThrows(IllegalStateException::class.java) { XiaoheiheNodeReader.read(Handle(tree), access) }
        assertEquals(3, access.acquired.size)
        access.assertAllReleased()
    }
}
