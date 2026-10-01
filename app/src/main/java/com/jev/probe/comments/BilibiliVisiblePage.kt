package com.jev.probe.comments

/** 只适配已验证的视频详情与 Compose 评论；编号来自当前简介，不继承上一个视频。 */
object BilibiliVisiblePageParser {
    private const val namespace = "tv.danmaku.bili:id/"
    private const val view = "android.view.View"
    private const val button = "android.widget.Button"
    private const val source = "B站可见评论"
    private const val identityNotice = "请先在简介页点一下视频标题，展开简介后再查看评论。"
    private val videoId = Regex("BV[1-9A-HJ-NP-Za-km-z]{10}")

    fun parse(root: CommentNode): CommentPage {
        val rejected = CommentPage(postTextAvailable = false, postSource = source,
            notice = "当前未能确定 B站视频详情，请打开单个视频。")
        if (root.find("video_area").singleOrNull() == null) return rejected
        val tabs = root.find("tabs").singleOrNull() ?: return rejected
        val introTab = tabs.find("tab_title").filter { it.text.trim() == "简介" }.singleOrNull() ?: return rejected
        val commentTab = tabs.find("tab_title").filter { it.text.trim() == "评论" }.singleOrNull() ?: return rejected
        val pager = root.find("pager").singleOrNull() ?: return rejected
        if (pager.className != "androidx.viewpager.widget.ViewPager") return rejected

        // fl_intro_container 在真实树中是空占位，与简介 recycler 同级。
        // 隐藏简介仍保留编号；只沿当前 pager 的直接子页寻找，排除广告和嵌套推荐。
        val intro = pager.children.filter { it.direct("fl_intro_container").isNotEmpty() }.singleOrNull() ?: return rejected
        if (intro.direct("fl_intro_container").size != 1) return rejected
        val recycler = intro.direct("recycler").singleOrNull() ?: return rejected
        if (recycler.className != "androidx.recyclerview.widget.RecyclerView") return rejected
        val titleRow = recycler.children.filter {
            it.direct("title").isNotEmpty() && it.direct("arrow").isNotEmpty()
        }.singleOrNull() ?: return rejected
        val title = titleRow.direct("title").singleOrNull() ?: return rejected
        if (title.className != "android.widget.TextView" || title.text.isBlank() || titleRow.direct("arrow").size != 1)
            return rejected
        val info = recycler.children.filter {
            listOf("views", "danmakus", "time").all { name -> it.direct(name).isNotEmpty() }
        }.singleOrNull() ?: return rejected
        if (listOf("views", "danmakus", "time").any { info.direct(it).size != 1 }) return rejected
        val description = info.direct("desc").singleOrNull() ?: return rejected
        if (description.className != "android.widget.TextView") return rejected
        val key = description.text.lineSequence().firstOrNull()?.trim().orEmpty().takeIf(videoId::matches).orEmpty()
        val page = CommentPage(postTitle = title.text.trim(), postKey = key, postTextAvailable = false, postSource = source,
            notice = "当前未能识别 B站评论区；仅读取本屏已加载评论文字。")
        if (key.isEmpty()) return page.copy(needsIdentity = true, notice = identityNotice)
        if (!commentTab.selected || introTab.selected) return page.copy(notice = "请打开视频评论页，读取本屏已加载评论文字。")

        val container = pager.find("comment_container").singleOrNull() ?: return page
        if (!container.visible || !container.area.hasSize()) return page
        val compose = container.children.singleOrNull() ?: return page
        if (compose.className != "androidx.compose.ui.platform.ComposeView" || !compose.visible) return page
        val list = compose.visibleNodes().filter { node -> node.children.any { it.visible && it.isMainRow() } }
            .singleOrNull() ?: return page
        val comments = mutableListOf<CapturedComment>()
        var thread = ""
        for (row in list.children) {
            if (!row.visible || !row.area.intersects(container.area)) { thread = ""; continue }
            if (row.isMainRow()) {
                // 只取直系语义正文；头像、日期、赞数都在其他包装节点中。
                val body = row.children.filter { it.isBody() }.singleOrNull()
                thread = body?.takeIf { it.visible && it.area.intersects(container.area) }?.description?.trim().orEmpty()
                if (thread.isNotEmpty()) comments += CapturedComment(thread)
                continue
            }
            val body = row.children.singleOrNull()?.takeIf { it.isBody() }
            if (row.className == view && row.clickable && body != null && thread.isNotEmpty()) {
                if (body.visible && body.area.intersects(container.area)) comments += CapturedComment(
                    body.description.trim(), threadRoot = thread, contextMissing = true,
                    contextNote = "回复预览未提供直接回复对象；描述可能包含折叠的完整文字")
            } else thread = ""
        }
        return page.copy(comments = comments.distinctBy { CommentText.fingerprint(it.text) }, recognized = true,
            notice = "已读取 B站本屏已加载评论文字；折叠预览的描述可能包含全文。")
    }

    private fun CommentNode.direct(name: String) = children.filter { it.id == namespace + name }
    private fun CommentNode.find(name: String) = all().filter { it.id == namespace + name }
    private fun CommentNode.isMainRow() = className == view && clickable && children.count {
        it.className == button && it.clickable && it.text.isNotBlank() && it.text.trim() == it.description.trim()
    } == 1
    private fun CommentNode.isBody() = className == view && !clickable && description.isNotBlank()
    private fun CommentNode.visibleNodes(): Sequence<CommentNode> = sequence {
        if (visible) { yield(this@visibleNodes); children.forEach { yieldAll(it.visibleNodes()) } }
    }
    private fun NodeArea.hasSize() = right > left && bottom > top
    private fun NodeArea.intersects(other: NodeArea) = hasSize() && other.hasSize() &&
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}
