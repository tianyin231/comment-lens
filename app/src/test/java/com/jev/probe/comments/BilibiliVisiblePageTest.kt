package com.jev.probe.comments

import org.junit.Assert.*
import org.junit.Test

/** 合成原生详情与 Compose 层级，不包含真实视频、评论或设备数据。 */
class BilibiliVisiblePageTest {
    private val firstId = "BV1AbCdEfGhJ"
    private val secondId = "BV1JkMnPqRsT"
    private val viewport = NodeArea(0, 200, 1000, 1800)

    private fun id(name: String) = "tv.danmaku.bili:id/$name"
    private fun native(name: String, text: String = "", className: String = "android.widget.TextView",
                       selected: Boolean = false) = CommentNode(id = id(name), text = text, className = className,
        area = viewport, selected = selected)

    private fun view(vararg children: CommentNode, description: String = "", clickable: Boolean = false,
                     visible: Boolean = true, area: NodeArea = viewport, className: String = "android.view.View") =
        CommentNode(className = className, children = children.toList(), description = description,
            clickable = clickable, visible = visible, area = area)

    private fun metadata(value: String) = view(view(description = value),
        CommentNode(text = value, className = "android.widget.TextView", area = viewport), clickable = true)

    private fun main(text: String?, author: String = "合成昵称", vararg extra: CommentNode): CommentNode {
        val body = text?.let { view(view(CommentNode(className = "android.widget.ImageView", area = viewport)),
            description = it) }
        return view(
            view(view(description = author), clickable = true),
            CommentNode(text = author, description = author, className = "android.widget.Button", clickable = true,
                area = viewport),
            // 部分主评论含另一个无文字按钮，不能把它当作第二个昵称。
            CommentNode(className = "android.widget.Button", clickable = true, area = viewport),
            *listOfNotNull(body).toTypedArray(), metadata("9月19日"), metadata("回复"), metadata("点赞，199"),
            metadata("点踩"), metadata("更多"), *extra, clickable = true)
    }

    private fun preview(text: String, visible: Boolean = true, area: NodeArea = viewport) = view(
        view(view(CommentNode(text = "127", className = "android.widget.TextView", area = viewport),
            view(description = "...\uFFFC", clickable = true)), description = text, visible = visible, area = area),
        clickable = true)

    private fun footer() = view(metadata("共15条回复"))
    private fun list(vararg rows: CommentNode) = view(
        view(CommentNode(text = "大家都在搜：", className = "android.widget.TextView", area = viewport)),
        view(CommentNode(text = "热门评论", className = "android.widget.TextView", area = viewport), metadata("按热度")),
        *rows)

    private fun titleRow(title: String = "合成视频标题") = view(native("title", title),
        native("arrow", className = "android.widget.ImageView"), description = "视频，$title，展开",
        clickable = true, className = "android.view.ViewGroup")

    private fun infoRow(description: String, extra: List<CommentNode> = emptyList()) = view(
        native("views", "9.4万"), native("danmakus", "109"), native("time", "2026年9月19日 18:33"),
        native("desc", description), *extra.toTypedArray(), className = "android.view.ViewGroup")

    private fun tree(description: String = "$firstId \n合成简介", title: String = "合成视频标题",
                     rows: List<CommentNode> = listOf(main("合成主评论")), hiddenIntro: Boolean = true,
                     commentSelected: Boolean = true, commentsVisible: Boolean = true,
                     introExtra: List<CommentNode> = emptyList(), infoExtra: List<CommentNode> = emptyList(),
                     extraLists: List<CommentNode> = emptyList()): CommentNode {
        val recycler = native("recycler", className = "androidx.recyclerview.widget.RecyclerView").copy(children = listOf(
            view(native("author_layout", className = "android.widget.LinearLayout").copy(children = listOf(
                native("author_name", "合成作者")))), titleRow(title), infoRow(description, infoExtra),
            view(native("title", "推荐视频标题"), native("cover_layout", className = "android.widget.FrameLayout"),
                native("desc", "$secondId\n推荐简介"))) + introExtra)
        val intro = view(native("fl_intro_container", className = "android.widget.FrameLayout"), recycler,
            className = "android.view.ViewGroup")
        fun hide(node: CommentNode): CommentNode = node.copy(visible = false, area = NodeArea(0, 200, 0, 1800),
            children = node.children.map(::hide))
        val loginMask = view(view(description = "登录后查看更多评论"),
            CommentNode(text = "登录后查看更多评论", className = "android.widget.TextView", area = viewport),
            CommentNode(className = "android.widget.Button", area = viewport), clickable = true)
        val compose = view(view(view(list(*rows.toTypedArray()), *extraLists.toTypedArray()), loginMask,
            view(CommentNode(text = "发条评论吧", className = "android.widget.TextView", area = viewport))),
            className = "androidx.compose.ui.platform.ComposeView")
        val comments = native("comment_container", className = "android.widget.FrameLayout")
            .copy(children = listOf(compose), visible = commentsVisible)
        val tabs = native("tabs", className = "android.widget.HorizontalScrollView").copy(children = listOf(
            native("tab_title", "简介", selected = !commentSelected), native("tab_title", "评论", selected = commentSelected)))
        val pager = native("pager", className = "androidx.viewpager.widget.ViewPager").copy(children = listOf(
            if (hiddenIntro) hide(intro) else intro, view(comments, className = "android.widget.FrameLayout")))
        // 广告复用 title/desc，并含合法 BV；禁止全屏按同名控件取第一项。
        val ad = native("ad_tint_frame", className = "android.widget.FrameLayout").copy(children = listOf(
            native("title", "合成广告标题"), native("desc", "$secondId\n广告简介"), main("广告伪评论")))
        return view(native("video_area", className = "android.widget.FrameLayout"), ad, tabs, pager)
    }

    private fun parse(tree: CommentNode) = CommentPageParser.parse("tv.danmaku.bili", tree)

    private fun map(node: CommentNode, change: (CommentNode) -> CommentNode): CommentNode =
        change(node.copy(children = node.children.map { map(it, change) }))

    @Test fun currentIntroductionBindsDifferentVideosAndIgnoresTitleChanges() {
        val first = parse(tree())
        val renamed = parse(tree(title = "修改后的合成标题"))
        val second = parse(tree(description = "$secondId\n第二个简介"))
        assertEquals(firstId, first.postKey)
        assertEquals(firstId, renamed.postKey)
        assertEquals("修改后的合成标题", renamed.postTitle)
        assertEquals(secondId, second.postKey)
        assertNotEquals(first.postKey, second.postKey)
        assertEquals("合成视频标题", first.postTitle)
        assertEquals("B站可见评论", first.postSource)
        assertFalse(first.postTextAvailable)
        assertEquals("", first.postBody)
        assertTrue(first.ocrAreas.isEmpty())
    }

    @Test fun onlyACompleteIdOnTheFirstIntroductionLineCanBindTheVideo() {
        assertEquals(firstId, parse(tree(description = "$firstId\n正文里另有 $secondId")).postKey)
        listOf("", "没有编号\n$firstId", "$firstId 附带文字\n简介", "BV1AbCdEfGh0\n简介", "$firstId-extra").forEach {
            val page = parse(tree(description = it))
            assertEquals("", page.postKey)
            assertTrue(page.needsIdentity)
            assertFalse(page.recognized)
            assertTrue(page.comments.isEmpty())
            assertEquals("合成视频标题", page.postTitle)
            assertEquals("请先在简介页点一下视频标题，展开简介后再查看评论。", page.notice)
        }
    }

    @Test fun hiddenOrCollapsedIntroductionKeepsItsBoundIdentity() {
        val visible = parse(tree(hiddenIntro = false))
        val hidden = parse(tree())
        val collapsed = parse(map(tree(hiddenIntro = false)) { node ->
            if (node.id == id("desc")) node.copy(visible = false, area = NodeArea(0, 200, 1000, 200)) else node
        })
        assertEquals(visible.postKey, hidden.postKey)
        assertEquals(visible.comments, hidden.comments)
        assertEquals(visible.postKey, collapsed.postKey)
        assertFalse(hidden.needsIdentity)
    }

    @Test fun unselectedOrHiddenCommentPageDoesNotCollectStaleComments() {
        listOf(tree(commentSelected = false), tree(commentsVisible = false), map(tree()) {
            if (it.id == id("comment_container")) it.copy(area = NodeArea(0, 200, 0, 1800)) else it
        }).forEach { root ->
            val page = parse(root)
            assertEquals(firstId, page.postKey)
            assertFalse(page.recognized)
            assertTrue(page.comments.isEmpty())
        }
    }

    @Test fun composeBodiesExcludeNicknamesDatesCountsPromptsAndAdverts() {
        val page = parse(tree(rows = listOf(main("真正主评[星星眼]"), preview("第一条回复[doge]"),
            preview("折叠预览的完整语义描述"), footer(), main(null, "只有昵称"))))
        assertTrue(page.recognized)
        assertEquals(listOf("真正主评[星星眼]", "第一条回复[doge]", "折叠预览的完整语义描述"), page.comments.map { it.text })
        assertTrue(page.notice.contains("本屏已加载评论文字"))
        assertTrue(page.notice.contains("折叠"))
        val previews = page.comments.drop(1)
        assertTrue(previews.all { it.parentText.isEmpty() && it.threadRoot == "真正主评[星星眼]" && it.contextMissing })
    }

    @Test fun sameTextIsMergedWhileShortNumericOrUiLikeCommentBodiesAreKept() {
        val page = parse(tree(rows = listOf(main("相同正文"), preview("相同正文"), footer(), main(" 相同正文 "),
            main("666"), main("回复"))))
        assertEquals(listOf("相同正文", "666", "回复"), page.comments.map { it.text })
    }

    @Test fun invisibleOutOfBoundsAndImageOnlyBodiesAreNotCollected() {
        val hidden = map(main("隐藏文字")) { if (it.description == "隐藏文字") it.copy(visible = false) else it }
        val outside = map(main("屏幕外文字")) {
            if (it.description == "屏幕外文字") it.copy(area = NodeArea(100, 1900, 900, 2000)) else it
        }
        val zeroHeight = map(main("零高度文字")) {
            if (it.description == "零高度文字") it.copy(area = NodeArea(100, 400, 900, 400)) else it
        }
        val imageOnly = main(null, "纯图昵称", view(CommentNode(className = "android.widget.ImageView", area = viewport),
            clickable = true))
        val page = parse(tree(rows = listOf(hidden, outside, zeroHeight, imageOnly, main("可见主评"),
            preview("隐藏回复", visible = false), preview("只露出部分的已加载回复", area = NodeArea(100, 1790, 900, 1850)))))
        assertEquals(listOf("可见主评", "只露出部分的已加载回复"), page.comments.map { it.text })
    }

    @Test fun missingOrAmbiguousMainBodyCannotSupplyAReplyThread() {
        val ambiguous = main("第一候选", "多候选昵称", view(description = "第二候选"))
        val page = parse(tree(rows = listOf(main("第一楼"), preview("第一楼回复"), footer(), main(null),
            preview("无主评预览"), ambiguous, preview("歧义预览"), main("最后一楼"))))
        assertEquals(listOf("第一楼", "第一楼回复", "最后一楼"), page.comments.map { it.text })
    }

    @Test fun unknownRowsStopReplyGroupingAndMultipleListsAreRejected() {
        val unknown = view(view(description = "未知界面"), metadata("按钮"), clickable = true)
        val page = parse(tree(rows = listOf(main("主评"), unknown, preview("无法确认所属楼的预览"))))
        assertEquals(listOf("主评"), page.comments.map { it.text })
        val ambiguous = parse(tree(extraLists = listOf(list(main("另一个列表主评")))))
        assertEquals(firstId, ambiguous.postKey)
        assertFalse(ambiguous.recognized)
        assertTrue(ambiguous.comments.isEmpty())
    }

    @Test fun duplicatedIntroductionFieldsOrPagesRejectVideoIdentity() {
        val inputs = listOf(tree(introExtra = listOf(titleRow("第二个标题候选"))),
            tree(introExtra = listOf(infoRow("$secondId\n第二统计行"))),
            tree(infoExtra = listOf(native("desc", "$secondId\n第二简介候选"))),
            map(tree()) { if (it.id == id("pager")) it.copy(children = it.children + it.children.first()) else it })
        inputs.forEach { root ->
            val page = parse(root)
            assertEquals("", page.postKey)
            assertFalse(page.needsIdentity)
            assertTrue(page.comments.isEmpty())
        }
    }

    @Test fun absentCurrentFieldsCannotBorrowAdvertOrRecommendationText() {
        listOf("arrow", "views", "danmakus", "time", "fl_intro_container").forEach { missing ->
            val root = map(tree()) { it.copy(children = it.children.filterNot { child -> child.id == id(missing) }) }
            val page = parse(root)
            assertEquals("", page.postKey)
            assertFalse(page.needsIdentity)
            assertTrue(page.comments.isEmpty())
        }
        val noCurrentDesc = map(tree()) { node ->
            if (node.children.any { it.id == id("time") }) node.copy(children = node.children.filterNot { it.id == id("desc") }) else node
        }
        assertEquals("", parse(noCurrentDesc).postKey)
        assertFalse(parse(noCurrentDesc).needsIdentity)
    }

    @Test fun homepageAndLoginDialogAreRejectedInsteadOfCollectingTheirComposeText() {
        val home = view(native("title", "首页推荐"), native("desc", "$firstId\n伪简介"), list(main("首页文字")))
        val login = view(view(CommentNode(text = "登录注册解锁更多精彩内容", className = "android.widget.TextView"),
            CommentNode(text = "请输入手机号", className = "android.widget.EditText"),
            preview("验证登录"), className = "androidx.compose.ui.platform.ComposeView"))
        listOf(home, login, map(tree()) { it.copy(children = it.children.filterNot { child -> child.id == id("video_area") }) }).forEach {
            val page = parse(it)
            assertEquals("", page.postKey)
            assertFalse(page.needsIdentity)
            assertFalse(page.recognized)
            assertTrue(page.comments.isEmpty())
        }
    }
}
