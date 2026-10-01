package com.jev.probe.comments

data class LiveDecision(val sentiment: Sentiment, val confidence: Double, val original: Sentiment = sentiment)
data class LiveText(val id: String, val text: String, val decision: LiveDecision? = null)
data class LiveBatch(val revision: Long, val texts: List<LiveText>)

data class LiveSnapshot(
    val title: String = "", val post: LiveText? = null, val comments: List<LiveText> = emptyList(), val postKey: String = "",
    val postSource: String = ""
) {
    val judged get() = comments.filter { it.decision != null }
    fun count(sentiment: Sentiment) = judged.count { it.decision?.sentiment == sentiment }
    fun percent(sentiment: Sentiment) = if (judged.isEmpty()) 0 else (count(sentiment) * 100.0 / judged.size).toInt()
}

/** 最近五帖保留完整去重文字和结果；情绪统计包含本帖全部已判断评论。主线程调用。 */
class LiveSentiment(initialCache: List<LiveSnapshot> = emptyList(), private val cacheLimit: Int = 5) {
    private var key = ""
    private var revision = 0L
    private var nextId = 0
    private val seen = LinkedHashSet<String>()
    private val cache = linkedMapOf<String, LiveSnapshot>().apply {
        initialCache.filter { it.postKey.isNotBlank() }.takeLast(cacheLimit).forEach { put(it.postKey, it) }
    }
    var snapshot = LiveSnapshot()
        private set

    fun detach() {
        revision++; key = ""; nextId = 0; seen.clear(); snapshot = LiveSnapshot()
    }

    fun cachedPosts(): List<LiveSnapshot> = cache.values.toList()
    fun invalidateRequests() { revision++ }
    fun isCurrent(batch: LiveBatch) = batch.revision == revision

    fun follow(pkg: String, page: CommentPage, collect: Boolean): Boolean {
        if (page.postKey.isBlank()) return false
        val nextKey = "$pkg:${page.postKey}"
        val changed = key != nextKey
        val body = if (page.postTextAvailable)
            listOf(page.postTitle, page.postBody).filter { it.isNotBlank() }.joinToString("\n").take(3000) else ""
        if (changed) {
            detach(); key = nextKey
            snapshot = cache.remove(key) ?: LiveSnapshot(page.postTitle,
                body.takeIf { it.isNotBlank() }?.let { LiveText("p", it) }, postKey = key, postSource = page.postSource)
            seen.addAll(snapshot.comments.map { CommentText.fingerprint(it.text) })
            nextId = snapshot.comments.maxOfOrNull { it.id.removePrefix("c").toInt() } ?: 0
            cache[key] = snapshot
            while (cache.size > cacheLimit) cache.remove(cache.keys.first())
        }
        if (snapshot.post == null && body.isNotBlank()) {
            snapshot = snapshot.copy(post = LiveText("p", body), postSource = page.postSource)
            cache[key] = snapshot
        }
        if (collect && page.recognized) {
            val fresh = page.comments.mapNotNull { comment ->
                val text = comment.text.trim().take(1500)
                if (text.isBlank() || !seen.add(CommentText.fingerprint(text))) null
                else LiveText("c${++nextId}", text)
            }
            if (fresh.isNotEmpty()) {
                snapshot = snapshot.copy(comments = snapshot.comments + fresh)
                cache[key] = snapshot
            }
        }
        return changed
    }

    /** 用户明确补充正文时只重置帖文判断，评论及其已有判断继续保留。 */
    fun updatePostText(text: String, source: String): Boolean {
        val body = text.trim().take(3000)
        if (key.isBlank() || body.isBlank()) return false
        val sameText = snapshot.post?.text == body
        if (sameText && snapshot.postSource == source) return false
        if (!sameText) revision++
        snapshot = snapshot.copy(post = if (sameText) snapshot.post else LiveText("p", body), postSource = source)
        cache[key] = snapshot
        return true
    }

    fun pending(limit: Int = 8): LiveBatch {
        val post = listOfNotNull(snapshot.post?.takeIf { it.decision == null })
        // 优先判断最新文字；较早的未完成内容保留，空闲时继续处理。
        val comments = snapshot.comments.filter { it.decision == null }.takeLast(limit - post.size)
        return LiveBatch(revision, post + comments)
    }

    fun apply(batch: LiveBatch, decisions: Map<String, LiveDecision>): Boolean {
        if (!isCurrent(batch)) return false
        val ids = batch.texts.map { it.id }.toSet()
        fun update(item: LiveText) = if (item.id in ids && decisions[item.id] != null)
            item.copy(decision = decisions.getValue(item.id)) else item
        snapshot = snapshot.copy(post = snapshot.post?.let(::update), comments = snapshot.comments.map(::update))
        cache[key] = snapshot
        return true
    }
}
