package com.jev.probe.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.comments.CommentNode
import com.jev.probe.comments.NodeArea

object CommentNodeReader {
    fun read(root: AccessibilityNodeInfo): CommentNode {
        if (root.packageName?.toString() == "com.max.xiaoheihe") return readXiaoheihe(root)
        if (root.packageName?.toString() == "tv.danmaku.bili") return readBilibili(root, comments = true)
        var count = 0
        fun snapshot(node: AccessibilityNodeInfo): CommentNode {
            count++
            val rect = Rect(); node.getBoundsInScreen(rect)
            val children = if (count >= 2500) emptyList() else (0 until node.childCount).mapNotNull { index ->
                node.getChild(index)?.let { child -> try { if (child.isVisibleToUser) snapshot(child) else null } finally { child.recycle() } }
            }
            return CommentNode(node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty(), node.isVisibleToUser,
                NodeArea(rect.left, rect.top, rect.right, rect.bottom), children)
        }
        return snapshot(root)
    }

    /** 回调重新核对当前视频；不遍历评论或推荐列表。 */
    fun readBilibiliIdentity(root: AccessibilityNodeInfo): CommentNode = readBilibili(root, comments = false)

    private fun readBilibili(root: AccessibilityNodeInfo, comments: Boolean): CommentNode {
        fun leaf(node: AccessibilityNodeInfo): CommentNode {
            val area = Rect(); node.getBoundsInScreen(area)
            return CommentNode(node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty(), node.isVisibleToUser,
                NodeArea(area.left, area.top, area.right, area.bottom), className = node.className?.toString().orEmpty(),
                description = node.contentDescription?.toString().orEmpty(), clickable = node.isClickable, selected = node.isSelected)
        }
        fun <T> query(parent: AccessibilityNodeInfo, id: String, block: (List<AccessibilityNodeInfo>) -> T): T {
            val nodes = parent.findAccessibilityNodeInfosByViewId("tv.danmaku.bili:id/$id")
            return try { block(nodes) } finally { nodes.forEach { it.recycle() } }
        }
        fun children(parent: AccessibilityNodeInfo, block: (AccessibilityNodeInfo) -> CommentNode?): List<CommentNode> =
            (0 until parent.childCount).mapNotNull { index -> parent.getChild(index)?.let { child ->
                try { block(child) } finally { child.recycle() }
            } }
        var count = 0
        fun tree(node: AccessibilityNodeInfo, depth: Int): CommentNode {
            check(++count <= 1500 && depth <= 35) { "B站评论节点过多，等待页面稳定后重试" }
            return leaf(node).copy(children = children(node) { tree(it, depth + 1) })
        }
        fun headerRow(node: AccessibilityNodeInfo, depth: Int): CommentNode = leaf(node).copy(
            children = if (depth == 2) emptyList() else children(node) { headerRow(it, depth + 1) })

        val video = query(root, "video_area") { it.map(::leaf) }
        if (video.size != 1) return CommentNode()
        val tabs = query(root, "tabs") { nodes -> nodes.map { tree(it, 0) } }
        val pages = query(root, "pager") { pagers -> pagers.map { pager ->
            // 简介退出可视区域后仍保留 BV；只读取头部结构，不复制推荐视频子树。
            val intros = query(pager, "fl_intro_container") { markers -> markers.mapNotNull { marker ->
                marker.parent?.let { intro -> try {
                    leaf(intro).copy(children = children(intro) { child -> when (child.viewIdResourceName) {
                        "tv.danmaku.bili:id/fl_intro_container" -> leaf(child)
                        "tv.danmaku.bili:id/recycler" -> leaf(child).copy(children = children(child) { headerRow(it, 0) })
                        else -> null
                    } })
                } finally { intro.recycle() } }
            } }
            val rows = if (comments) query(pager, "comment_container") { nodes -> nodes.map { node ->
                if (node.isVisibleToUser) tree(node, 0) else leaf(node)
            } } else emptyList()
            leaf(pager).copy(children = intros + rows)
        } }
        return leaf(root).copy(children = video + tabs + pages)
    }

    /** 只查询已验证的控件，避免遍历图片、推荐区和过期列表节点时触发系统长超时。 */
    private fun readXiaoheihe(root: AccessibilityNodeInfo): CommentNode = XiaoheiheNodeReader.read(root,
        object : XiaoheiheNodeReader.NodeAccess<AccessibilityNodeInfo> {
            override fun find(parent: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> =
                parent.findAccessibilityNodeInfosByViewId("com.max.xiaoheihe:id/$id")

            override fun snapshot(node: AccessibilityNodeInfo): CommentNode {
                val rect = Rect(); node.getBoundsInScreen(rect)
                return CommentNode(node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty(), node.isVisibleToUser,
                    NodeArea(rect.left, rect.top, rect.right, rect.bottom))
            }

            override fun hasChildren(node: AccessibilityNodeInfo) = node.childCount > 0
            override fun recycle(node: AccessibilityNodeInfo) = node.recycle()

            override fun webViews(root: AccessibilityNodeInfo): List<CommentNode> {
                // 只在正文/评论 ViewPager 内寻找 WebView，不扫描首页或其他窗口。
                val pages = find(root, "vp")
                val result = mutableListOf<CommentNode>()
                var count = 0
                fun webSnapshot(node: AccessibilityNodeInfo, depth: Int): CommentNode {
                    check(++count <= 1500 && depth <= 40) { "长文节点过多，等待页面稳定后重试" }
                    val children = (0 until node.childCount).mapNotNull { index ->
                        node.getChild(index)?.let { child ->
                            try { webSnapshot(child, depth + 1) } finally { child.recycle() }
                        }
                    }
                    return snapshot(node).copy(children = children,
                        className = node.className?.toString().orEmpty(),
                        role = node.extras.getString("AccessibilityNodeInfo.chromeRole").orEmpty())
                }
                fun findWeb(node: AccessibilityNodeInfo, depth: Int) {
                    if (node.className?.toString() == "android.webkit.WebView") {
                        result += webSnapshot(node, 0)
                        return
                    }
                    // 原生评论区可能很长，已由窄选择器处理。
                    if (depth >= 10 || node.className?.toString()?.contains("RecyclerView") == true) return
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let { child ->
                            try { findWeb(child, depth + 1) } finally { child.recycle() }
                        }
                    }
                }
                try { pages.forEach { findWeb(it, 0) } } finally { pages.forEach { it.recycle() } }
                return result
            }
        })
}
