package com.jev.probe.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.comments.CommentNode
import com.jev.probe.comments.NodeArea

object CommentNodeReader {
    fun read(root: AccessibilityNodeInfo): CommentNode {
        if (root.packageName?.toString() == "com.max.xiaoheihe") return readXiaoheihe(root)
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
