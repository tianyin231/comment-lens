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
    private fun readXiaoheihe(root: AccessibilityNodeInfo): CommentNode {
        fun nodes(parent: AccessibilityNodeInfo, id: String) = parent.findAccessibilityNodeInfosByViewId("com.max.xiaoheihe:id/$id")
        fun leaf(node: AccessibilityNodeInfo): CommentNode {
            val rect = Rect(); node.getBoundsInScreen(rect)
            return CommentNode(node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty(), node.isVisibleToUser,
                NodeArea(rect.left, rect.top, rect.right, rect.bottom))
        }
        fun leaves(parent: AccessibilityNodeInfo, id: String): List<CommentNode> = nodes(parent, id).map { node ->
            try { leaf(node) } finally { node.recycle() }
        }
        val result = mutableListOf<CommentNode>()
        result += leaves(root, "title_name")
        result += leaves(root, "tv_all_comment")
        nodes(root, "post_like").firstOrNull()?.let { post ->
            try {
                val parts = leaves(post, "bbs_name").take(1).toMutableList()
                nodes(post, "post_content_container").firstOrNull()?.let { body ->
                    try { parts += CommentNode(id = "post_content_container", children = leaves(body, "tv_title").take(1) + leaves(body, "tv_desc")) }
                    finally { body.recycle() }
                }
                result += CommentNode(id = "post_like", children = parts)
            } finally { post.recycle() }
        }
        nodes(root, "vg_comments_detail").forEach { row ->
            try {
                if (!row.isVisibleToUser) return@forEach
                val parts = mutableListOf<CommentNode>()
                nodes(row, "vg_main_comment").firstOrNull()?.let { main ->
                    try {
                        val content = leaves(main, "tv_comment") + leaves(main, "bbs_name")
                        val pictures = nodes(main, "ll_pic").map { pic ->
                            try { CommentNode(id = "ll_pic", children = if (pic.childCount > 0) listOf(CommentNode(id = "image")) else emptyList()) }
                            finally { pic.recycle() }
                        }
                        parts += CommentNode(id = "vg_main_comment", children = content + pictures)
                    } finally { main.recycle() }
                }
                parts += CommentNode(id = "rv_sub_comments", children = leaves(row, "tv_sub_comment_content"))
                result += CommentNode(id = "vg_comments_detail", children = parts)
            } finally { row.recycle() }
        }
        return CommentNode(children = result)
    }
}
