package com.jev.probe.capture

import com.jev.probe.comments.CommentNode

/** 窄选择器读取可以用普通节点替身验证，不依赖 Android 无障碍服务。 */
internal object XiaoheiheNodeReader {
    interface NodeAccess<N> {
        fun find(parent: N, id: String): List<N>
        /** 单个节点的属性，不遍历或复制子树。 */
        fun snapshot(node: N): CommentNode
        fun hasChildren(node: N): Boolean
        fun recycle(node: N)
    }

    fun <N> read(root: N, access: NodeAccess<N>): CommentNode {
        fun <T> query(parent: N, id: String, read: (List<N>) -> T): T {
            val nodes = access.find(parent, id)
            return try { read(nodes) } finally { nodes.forEach(access::recycle) }
        }
        fun leaves(parent: N, id: String) = query(parent, id) { nodes -> nodes.map(access::snapshot) }

        val result = mutableListOf<CommentNode>()
        result += leaves(root, "title_name")
        result += leaves(root, "tv_all_comment")
        // 缺少已知详情标志时，不继续读取可能属于首页卡片的正文。
        if (result.isEmpty()) return CommentNode()
        query(root, "post_like") { posts ->
            posts.forEach { post ->
                result += access.snapshot(post).copy(children = leaves(post, "bbs_name").take(1))
            }
        }
        // 长文正文不一定是 post_like 的子节点。保留所有候选，让解析器拒绝歧义。
        query(root, "post_content_container") { bodies ->
            bodies.forEach { body ->
                result += access.snapshot(body).copy(children = leaves(body, "tv_title") + leaves(body, "tv_desc"))
            }
        }
        query(root, "vg_comments_detail") { rows ->
            rows.forEach { row ->
                val rowNode = access.snapshot(row)
                if (!rowNode.visible) return@forEach
                val parts = mutableListOf<CommentNode>()
                query(row, "vg_main_comment") { mainNodes ->
                    mainNodes.firstOrNull()?.let { main ->
                        val content = leaves(main, "tv_comment") + leaves(main, "bbs_name")
                        val pictures = query(main, "ll_pic") { pictures ->
                            pictures.map { pic ->
                                access.snapshot(pic).copy(children = if (access.hasChildren(pic))
                                    listOf(CommentNode(id = "image")) else emptyList())
                            }
                        }
                        parts += access.snapshot(main).copy(children = content + pictures)
                    }
                }
                parts += CommentNode(id = "rv_sub_comments", children = leaves(row, "tv_sub_comment_content"))
                result += rowNode.copy(children = parts)
            }
        }
        return CommentNode(children = result)
    }
}
