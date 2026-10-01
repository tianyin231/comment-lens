package com.jev.probe.comments

import java.security.MessageDigest

data class XiaoheiheWebArticle(
    val title: String, val body: String, val key: String, val textAreas: List<NodeArea>
)

/** 完整 DOM 用于识别文章，可见标题和正文区域用于本地 OCR。 */
object XiaoheiheWebArticleParser {
    fun parse(web: CommentNode): XiaoheiheWebArticle? {
        if (web.className != "android.webkit.WebView") return null
        val app = web.named("app").singleOrNull() ?: return null
        val article = app.all().filter { node -> node.children.any(::isFollowButton) }.singleOrNull() ?: return null
        val follow = article.children.withIndex().filter { isFollowButton(it.value) }.singleOrNull()?.index ?: return null
        val header = article.children.take(follow).filter {
            it.className == "android.widget.TextView" && it.text.isNotBlank()
        }
        val title = header.firstOrNull() ?: return null
        val author = header.getOrNull(1) ?: return null
        // 作者是标题后的直系文字；缺失作者时不能误用日期、地区或等级。
        if (author.role == "paragraph" || metadata.matches(author.text.trim())) return null
        val paragraphs = article.children.drop(follow + 1).asSequence().flatMap(::bodyNodes).toList()
        val completeBody = paragraphs.joinToString("\n") { it.text.trim() }
        val key = MessageDigest.getInstance("SHA-256")
            .digest("${author.text.trim()}\u0000${title.text.trim()}\u0000$completeBody".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        fun visibleArea(node: CommentNode): NodeArea? {
            // 评论页的外层 WebView 被移出屏幕，内层节点仍可能错误报告 visible=true。
            if (!web.visible || !node.visible) return null
            val clipped = NodeArea(maxOf(web.area.left, node.area.left), maxOf(web.area.top, node.area.top),
                minOf(web.area.right, node.area.right), minOf(web.area.bottom, node.area.bottom))
            return clipped.takeIf { it.right > it.left && it.bottom > it.top }
        }
        val visible = paragraphs.filter { visibleArea(it) != null }
        return XiaoheiheWebArticle(title.text.trim(), visible.joinToString("\n") { it.text.trim() }, key,
            (listOf(title) + visible).mapNotNull(::visibleArea).distinct())
    }

    private fun isFollowButton(node: CommentNode) = node.className == "android.widget.Button" &&
        node.text.trim() in listOf("关注", "已关注", "互相关注")

    private fun bodyNodes(node: CommentNode): Sequence<CommentNode> = sequence {
        if (node.role == "image" || node.className == "android.widget.ImageView") return@sequence
        if (node.role in listOf("paragraph", "heading") && node.text.isNotBlank()) yield(node)
        else node.children.forEach { yieldAll(bodyNodes(it)) }
    }

    private val metadata = Regex("(?:Lv\\.?\\s*\\d+|\\d{1,4}[-/]\\d{1,2}(?:[-/]\\d{1,2})?|[·•].*)",
        RegexOption.IGNORE_CASE)
}
