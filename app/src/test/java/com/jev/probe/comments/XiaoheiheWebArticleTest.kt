package com.jev.probe.comments

import org.junit.Assert.*
import org.junit.Test

/** 使用合成 DOM，保留真实布局的层级和角色，不含用户文章或抓屏。 */
class XiaoheiheWebArticleTest {
    private val viewport = NodeArea(0, 20, 100, 200)
    private val titleArea = NodeArea(10, 30, 90, 55)

    private fun text(value: String, role: String = "genericContainer", area: NodeArea = NodeArea(10, 80, 90, 100),
                     visible: Boolean = true) = CommentNode(text = value, visible = visible, area = area,
        className = "android.widget.TextView", role = role)

    private fun container(vararg children: CommentNode) = CommentNode(children = children.toList(),
        className = "android.view.View", role = "genericContainer")

    private fun article(vararg paragraphs: CommentNode, date: String = "10-01", level: String = "Lv.9",
                        follow: String = "关注") = container(
        text(""), text("合成长文章标题", area = titleArea),
        container(CommentNode(text = "头像噪音", className = "android.widget.ImageView", role = "image")),
        text("合成作者"), container(text(level)), text(date, "paragraph"), text("·合成地区", "paragraph"),
        CommentNode(text = follow, className = "android.widget.Button", role = "button"),
        container(text("合成话题")), text("合成辅助标记"), *paragraphs, text("原创"))

    private fun web(article: CommentNode, visible: Boolean = true, area: NodeArea = viewport) = CommentNode(
        visible = visible, area = area, className = "android.webkit.WebView", children = listOf(CommentNode(
            visible = true, area = area, className = "android.webkit.WebView", children = listOf(CommentNode(
                id = "app", className = "android.view.View", role = "genericContainer",
                children = listOf(container(article)))))))

    @Test fun visibleParagraphsAndHeadingsAreClippedWithoutMetadataOrImages() {
        val paragraph = text("可见正文", "paragraph", NodeArea(-10, 150, 110, 250))
        val heading = text("正文小标题", "heading", NodeArea(10, 120, 90, 140))
        val image = CommentNode(text = "图片文字", role = "image", className = "android.view.View",
            children = listOf(text("图片内的文字", "paragraph")))
        val parsed = XiaoheiheWebArticleParser.parse(web(article(heading, paragraph, image,
            text("屏幕外正文", "paragraph", NodeArea(10, -60, 90, -20)),
            text("不可见正文", "paragraph", visible = false),
            text("无效坐标正文", "paragraph", NodeArea(10, 220, 90, 200)))))!!
        assertEquals("合成长文章标题", parsed.title)
        assertEquals("正文小标题\n可见正文", parsed.body)
        assertEquals(listOf(titleArea, NodeArea(10, 120, 90, 140), NodeArea(0, 150, 100, 200)), parsed.textAreas)
        assertEquals(64, parsed.key.length)
    }

    @Test fun bodyAndCommentTabsKeepTheFullArticleIdentity() {
        val tree = web(article(text("可见正文", "paragraph"),
            text("未显示的完整尾段", "paragraph", visible = false)))
        val body = XiaoheiheWebArticleParser.parse(tree)!!
        // 模拟评论页：外层不可见且宽度为零，内层仍错误报告可见。
        val comments = XiaoheiheWebArticleParser.parse(tree.copy(visible = false,
            area = NodeArea(0, 20, 0, 200)))!!
        assertEquals(body.key, comments.key)
        assertEquals(body.title, comments.title)
        assertEquals("", comments.body)
        assertTrue(comments.textAreas.isEmpty())
        val zeroWidth = XiaoheiheWebArticleParser.parse(tree.copy(area = NodeArea(0, 20, 0, 200)))!!
        assertEquals(body.key, zeroWidth.key)
        assertTrue(zeroWidth.textAreas.isEmpty())
        assertEquals("", zeroWidth.body)
    }

    @Test fun differentHiddenTailChangesIdentityEvenAfterALongSharedPrefix() {
        val shared = "合成共同段落".repeat(1000)
        fun parse(tail: String) = XiaoheiheWebArticleParser.parse(web(article(
            text("相同可见正文", "paragraph"), text(shared + tail, "paragraph", visible = false))))!!
        val first = parse("第一篇尾段")
        val second = parse("第二篇尾段")
        assertEquals(first.title, second.title)
        assertEquals(first.body, second.body)
        assertNotEquals(first.key, second.key)
    }

    @Test fun dateLevelAndFollowStateDoNotChangeTextOrIdentity() {
        val paragraph = text("真正正文", "paragraph")
        val first = XiaoheiheWebArticleParser.parse(web(article(paragraph)))!!
        listOf("已关注", "互相关注").forEach { follow ->
            val changed = XiaoheiheWebArticleParser.parse(web(article(paragraph,
                date = "09-01", level = "Lv.99", follow = follow)))!!
            assertEquals(first, changed)
        }
        assertEquals("真正正文", first.body)
        val noise = article(paragraph).let { node -> node.copy(children = node.children + listOf(
            text("额外元数据"), CommentNode(text = "图片噪音", className = "android.widget.ImageView", role = "image"))) }
        assertEquals(first, XiaoheiheWebArticleParser.parse(web(noise)))
        val metadataOnly = web(article(paragraph).let { node ->
            node.copy(children = node.children.filterNot { it.text == "合成作者" })
        })
        assertNull("缺失作者时不能使用日期生成身份", XiaoheiheWebArticleParser.parse(metadataOnly))
    }

    @Test fun missingAppOrAmbiguousArticleStructureIsRejected() {
        val article = article(text("合成正文", "paragraph"))
        val valid = web(article)
        assertNull(XiaoheiheWebArticleParser.parse(valid.copy(children = listOf(article))))
        assertNull(XiaoheiheWebArticleParser.parse(valid.copy(children = valid.children + valid.children)))
        assertNull(XiaoheiheWebArticleParser.parse(web(container(article, article))))
        assertNull(XiaoheiheWebArticleParser.parse(web(article.copy(children = article.children.filterNot {
            it.className == "android.widget.Button"
        }))))
        assertNull(XiaoheiheWebArticleParser.parse(web(article.copy(children = article.children +
            CommentNode(text = "关注", className = "android.widget.Button", role = "button")))))
    }
}
