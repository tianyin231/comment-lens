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
        })
}
