package com.jev.probe.comments

import java.security.MessageDigest

data class NodeArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(other: NodeArea) = other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

/** 平台解析器只读取正文节点；保留树结构以确定回复所属的楼。 */
data class CommentNode(
    val id: String = "", val text: String = "", val visible: Boolean = true,
    val area: NodeArea = NodeArea(0, 0, 0, 0), val children: List<CommentNode> = emptyList()
) {
    fun all(): Sequence<CommentNode> = sequence { yield(this@CommentNode); children.forEach { yieldAll(it.all()) } }
    fun named(name: String) = all().filter { it.id.substringAfterLast('/') == name }
    fun value(name: String) = named(name).firstOrNull { it.text.isNotBlank() }?.text?.trim().orEmpty()
}

data class CommentPage(
    val comments: List<CapturedComment> = emptyList(),
    val postTitle: String = "", val postBody: String = "", val postKey: String = "",
    val recognized: Boolean = false,
    val notice: String = "此页面尚未识别出评论控件。请框选评论正文，手动核对后加入。"
)

object CommentPageParser {
    fun parse(pkg: String, root: CommentNode): CommentPage = when (pkg) {
        "com.max.xiaoheihe" -> xiaoheihe(root)
        else -> CommentPage()
    }

    private val metadata = Regex("\\s+(?:(?:刚刚|\\d+\\s*(?:秒|分钟|小时|天)前)|(?:昨天|前天)(?:\\d{1,2}:\\d{2})?|\\d{1,2}[-/]\\d{1,2}(?:\\s+\\d{1,2}:\\d{2})?)·[^\\s·]+[\\uFEFF\\s]*$")
    private val reply = Regex("^(.*?)\\s+回复\\s*(.+)$")
    private data class Reply(val author: String, val to: String, val text: String)

    private fun readReply(text: String): Reply? {
        val clean = text.replace('\u00a0', ' ').replace("\uFEFF", "").trim()
        val separator = clean.indexOfFirst { it == ':' || it == '：' }
        if (separator < 1) return null
        val prefix = clean.substring(0, separator).trim()
        val match = reply.matchEntire(prefix)
        return Reply(match?.groupValues?.get(1) ?: prefix, match?.groupValues?.get(2).orEmpty(),
            clean.substring(separator + 1).replace(metadata, "").trim())
    }

    private fun xiaoheihe(root: CommentNode): CommentPage {
        // 首页卡片也可能复用正文控件；必须同时有详情页的标题栏或评论区标志。
        // 标志和正文可以滚出可视区域，但仍留在无障碍树中，不能按 visible 丢弃。
        val detail = root.named("title_name").any() || root.named("tv_all_comment").any()
        val post = root.named("post_content_container").toList().singleOrNull()
        if (!detail || post == null) return CommentPage(notice = "当前未能确定小黑盒帖子正文，请打开单篇帖子。")
        val title = post.value("tv_title")
        val body = post.named("tv_desc").map { it.text.trim() }.filter { it.isNotBlank() }.joinToString("\n")
        // 标题栏作者会随滚动出现或消失，不能作为稳定帖标识。
        val author = root.named("post_like").toList().singleOrNull()?.value("bbs_name").orEmpty()
        // 长文可能没有独立标题。用完整正文生成标识，不能把文章的相同开头当作同一帖。
        val key = if (title.isBlank() && body.isBlank()) "" else MessageDigest.getInstance("SHA-256")
            .digest("$author\u0000$title\u0000$body".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val rows = root.named("vg_comments_detail").toList()
        val comments = mutableListOf<CapturedComment>()
        rows.forEach { row ->
            val main = row.named("vg_main_comment").firstOrNull()
            val mainNode = main?.named("tv_comment")?.firstOrNull()
            val mainText = mainNode?.text?.trim().orEmpty()
            val mainAuthor = main?.value("bbs_name").orEmpty()
            val images = main?.named("ll_pic")?.any { it.children.isNotEmpty() } == true
            if (mainNode?.visible == true && mainText.isNotBlank()) comments += CapturedComment(mainText,
                contextMissing = images, contextNote = if (images) "评论含图片，尚未补充图片含义" else "")
            val nodes = row.named("rv_sub_comments").flatMap { it.named("tv_sub_comment_content") }.toList()
            val parsed = nodes.mapNotNull { node -> readReply(node.text)?.let { node to it } }
            parsed.filter { it.first.visible }.forEach replies@{ (_, item) ->
                if (item.text.isBlank()) return@replies
                val parent = if (item.to.isBlank() || item.to == mainAuthor) mainText else
                    parsed.map { it.second }.filter { it.author == item.to }.singleOrNull()?.text.orEmpty()
                val missing = parent.isBlank() || mainText.isBlank() || images
                comments += CapturedComment(item.text, parent, mainText, missing,
                    when { images -> "主评论含图片，尚未补充图片含义"
                        parent.isBlank() -> "直接回复的父评论不在本屏，需要补全"
                        mainText.isBlank() -> "该楼主评论不在本屏，需要补全"
                        else -> "" })
            }
        }
        val recognized = rows.isNotEmpty() && root.named("tv_all_comment").any()
        return CommentPage(comments.distinctBy { it.fingerprint }, title, body, key, recognized,
            if (recognized) "已读取小黑盒评论正文及回复关系" else "当前未发现小黑盒帖子评论区，请打开帖子评论。")
    }

    fun inRegion(root: CommentNode, area: NodeArea): List<CapturedComment> = root.all()
        .filter { it.visible && it.text.isNotBlank() && area.contains(it.area) && it.children.none { child -> child.text.isNotBlank() } }
        .sortedBy { it.area.top }.map { CapturedComment(it.text.trim(), contextMissing = true, contextNote = "框选内容，需核对正文及回复关系") }
        .distinctBy { it.text }.toList()
}
