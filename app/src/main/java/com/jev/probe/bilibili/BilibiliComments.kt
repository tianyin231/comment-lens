package com.jev.probe.bilibili

enum class BilibiliSort(val label: String) { HOT("热度顺序"), NEWEST("时间倒序") }

data class BilibiliRequest(val video: BilibiliLink.Video, val limit: Int, val sort: BilibiliSort) {
    init {
        require(limit in 1..200) { "读取数量须为 1–200。" }
        require(BilibiliLinks.parse(video.url) == video) { "视频标识无效。" }
    }
}

/** 空文字可表示纯图片评论；适配器必须验证原始文字字段，不能用空串代替缺失字段。 */
data class BilibiliComment(val id: String, val text: String, val replyCount: Long? = null)

/** 只包含正常分页顺序中的顶层评论；置顶项和有限的楼中楼预览不并入该序列。 */
data class BilibiliCommentPage(
    val comments: List<BilibiliComment>,
    val sort: BilibiliSort,
    val hasMore: Boolean,
    val nextCursor: String? = null,
    val reportedTotal: Long? = null
)

sealed interface BilibiliAvailability {
    data object Available : BilibiliAvailability
    data class Blocked(val reason: String) : BilibiliAvailability
}

interface BilibiliCommentSource {
    val availability: BilibiliAvailability
    fun readPage(request: BilibiliRequest, cursor: String?): BilibiliCommentPage
}

/** 只有获得适用许可并验证真实接口后，才能替换生产默认来源。无匿名网页 API 后备。 */
object UnavailableBilibiliSource : BilibiliCommentSource {
    override val availability = BilibiliAvailability.Blocked(
        "本应用尚无获准的 B站评论数据来源，暂不能读取真实评论。视频分享链接本身不提供评论读取授权。")
    override fun readPage(request: BilibiliRequest, cursor: String?): BilibiliCommentPage =
        throw BilibiliSourceException(availability.reason)
}

class BilibiliSourceException(val reason: String) : RuntimeException(reason)

enum class BilibiliLoadStatus { LIMIT_REACHED, COMPLETE, EMPTY, PARTIAL, BLOCKED, FAILED, CANCELLED }

data class BilibiliLoadResult(
    val request: BilibiliRequest,
    val status: BilibiliLoadStatus,
    val comments: List<BilibiliComment> = emptyList(),
    val actualSort: BilibiliSort? = null,
    val reportedTotal: Long? = null,
    val pagesRead: Int = 0,
    val reason: String = ""
)

/** 按来源的分页顺序取前 N 个不同评论 ID，不以相同文字合并不同评论。 */
class BilibiliCommentLoader(private val source: BilibiliCommentSource) {
    fun load(request: BilibiliRequest, cancelled: () -> Boolean = { false }): BilibiliLoadResult {
        val comments = linkedMapOf<String, BilibiliComment>()
        var pages = 0
        var actualSort: BilibiliSort? = null
        var total: Long? = null
        fun result(status: BilibiliLoadStatus, reason: String = "") =
            BilibiliLoadResult(request, status, comments.values.toList(), actualSort, total, pages, reason)
        fun failed(reason: String) = if (cancelled()) result(BilibiliLoadStatus.CANCELLED, "已取消。")
            else result(if (comments.isEmpty()) BilibiliLoadStatus.FAILED else BilibiliLoadStatus.PARTIAL, reason)
        if (cancelled()) return result(BilibiliLoadStatus.CANCELLED, "已取消。")
        (source.availability as? BilibiliAvailability.Blocked)?.let {
            return result(BilibiliLoadStatus.BLOCKED, it.reason)
        }
        var cursor: String? = null
        val cursors = mutableSetOf<String>()
        repeat(20) {
            if (cancelled()) return result(BilibiliLoadStatus.CANCELLED, "已取消。")
            val page = try { source.readPage(request, cursor) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return result(BilibiliLoadStatus.CANCELLED, "已取消。")
            } catch (error: BilibiliSourceException) {
                return failed(error.reason)
            } catch (_: Exception) {
                return failed("评论读取失败，未继续请求下一页。")
            }
            if (cancelled()) return result(BilibiliLoadStatus.CANCELLED, "已取消。")
            if (page.sort != request.sort) return failed("来源返回的排序与所选排序不一致，已停止读取。")
            if (page.comments.any { !it.id.matches(Regex("[1-9][0-9]*")) || (it.replyCount ?: 0) < 0 } ||
                (page.reportedTotal ?: 0) < 0) return failed("评论来源返回的数据不完整或格式无效，已停止读取。")
            actualSort = page.sort; total = page.reportedTotal ?: total; pages++
            val before = comments.size
            page.comments.forEach { comment ->
                if (comments.size < request.limit) comments.putIfAbsent(comment.id, comment)
            }
            if (comments.size >= request.limit) return result(BilibiliLoadStatus.LIMIT_REACHED)
            if (!page.hasMore) return result(if (comments.isEmpty()) BilibiliLoadStatus.EMPTY else BilibiliLoadStatus.COMPLETE)
            if (comments.size == before) return failed("分页未返回新的评论，已停止，不能确认已读完整个评论区。")
            val next = page.nextCursor?.takeIf { it.isNotBlank() }
                ?: return failed("来源未提供下一页位置，已保留本次读到的评论。")
            if (!cursors.add(next)) return failed("来源重复返回相同分页位置，已停止读取。")
            cursor = next
        }
        return failed("已达到本次 20 页读取上限，未继续获取。")
    }
}
