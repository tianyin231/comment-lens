package com.jev.probe.bilibili

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Every source in this test is synthetic and stays in the test source set. */
class BilibiliCommentLoaderTest {
    private val request = BilibiliRequest(BilibiliLink.Video("BV1xx411c7mD"), 10, BilibiliSort.HOT)
    private fun comment(id: String, text: String = "合成评论 $id") = BilibiliComment(id, text)
    private fun page(vararg comments: BilibiliComment, more: Boolean = false, next: String? = null,
        sort: BilibiliSort = BilibiliSort.HOT, total: Long? = null) =
        BilibiliCommentPage(comments.toList(), sort, more, next, total)

    private class Source(vararg pages: () -> BilibiliCommentPage,
        override val availability: BilibiliAvailability = BilibiliAvailability.Available) : BilibiliCommentSource {
        private val pages = ArrayDeque(pages.toList())
        val cursors = mutableListOf<String?>()
        override fun readPage(request: BilibiliRequest, cursor: String?): BilibiliCommentPage {
            cursors += cursor
            return pages.removeFirst().invoke()
        }
    }

    @Test fun requestLimitIsBoundedInTheModel() {
        listOf(-1, 0, 201, Int.MAX_VALUE).forEach {
            assertThrows(IllegalArgumentException::class.java) { request.copy(limit = it) }
        }
        assertEquals(1, request.copy(limit = 1).limit)
        assertEquals(200, request.copy(limit = 200).limit)
    }

    @Test fun invalidVideoIdentityCannotBypassTheLinkParserViaDirectConstruction() {
        listOf("not-a-video", "../av123", "BV1xx411c7mD?tracking=x", "av0").forEach {
            assertThrows(IllegalArgumentException::class.java) { request.copy(video = BilibiliLink.Video(it)) }
        }
    }

    @Test fun blockedSourceNeverReadsAPageOrPretendsToKnowSortAndTotal() {
        val source = Source(availability = BilibiliAvailability.Blocked("合成授权阻碍"))
        val result = BilibiliCommentLoader(source).load(request)
        assertEquals(BilibiliLoadStatus.BLOCKED, result.status)
        assertEquals("合成授权阻碍", result.reason)
        assertTrue(source.cursors.isEmpty())
        assertTrue(result.comments.isEmpty())
        assertNull(result.actualSort)
        assertNull(result.reportedTotal)
        assertEquals(0, result.pagesRead)
    }

    @Test fun productionDefaultSourceRemainsBlockedWithNoComments() {
        val result = BilibiliCommentLoader(UnavailableBilibiliSource).load(request)
        assertEquals(BilibiliLoadStatus.BLOCKED, result.status)
        assertTrue(result.reason.isNotBlank())
        assertTrue(result.comments.isEmpty())
        assertNull(result.actualSort)
        assertNull(result.reportedTotal)
    }

    @Test fun reachesLimitInSourceOrderWithoutReadingAnotherPage() {
        val source = Source({ page(comment("3"), comment("1"), comment("2"), more = true, next = "next") })
        val result = BilibiliCommentLoader(source).load(request.copy(limit = 2))
        assertEquals(BilibiliLoadStatus.LIMIT_REACHED, result.status)
        assertEquals(listOf("3", "1"), result.comments.map { it.id })
        assertEquals(listOf<String?>(null), source.cursors)
        assertEquals(BilibiliSort.HOT, result.actualSort)
        assertNull(result.reportedTotal)
    }

    @Test fun duplicateIdsAreRemovedButDifferentIdsWithSameTextAreKept() {
        val source = Source(
            { page(comment("1", "相同合成文字"), comment("1", "同ID变化"), more = true, next = "page2") },
            { page(comment("1", "仍是同ID"), comment("2", "相同合成文字")) })
        val result = BilibiliCommentLoader(source).load(request)
        assertEquals(BilibiliLoadStatus.COMPLETE, result.status)
        assertEquals(listOf("1", "2"), result.comments.map { it.id })
        assertEquals(listOf("相同合成文字", "相同合成文字"), result.comments.map { it.text })
        assertEquals(listOf(null, "page2"), source.cursors)
    }

    @Test fun successfulEmptyPageIsDistinctFromBlockedOrFailedAndCanReportVerifiedZero() {
        val result = BilibiliCommentLoader(Source({ page(total = 0) })).load(request)
        assertEquals(BilibiliLoadStatus.EMPTY, result.status)
        assertEquals(BilibiliSort.HOT, result.actualSort)
        assertEquals(0L, result.reportedTotal)
        assertEquals(1, result.pagesRead)
    }

    @Test fun firstPageFailureDoesNotClaimVerifiedSortOrEmptyCommentArea() {
        val source = Source({ throw BilibiliSourceException("合成访问限制，停止") })
        val result = BilibiliCommentLoader(source).load(request)
        assertEquals(BilibiliLoadStatus.FAILED, result.status)
        assertEquals("合成访问限制，停止", result.reason)
        assertTrue(result.comments.isEmpty())
        assertNull(result.actualSort)
        assertNull(result.reportedTotal)
        assertEquals(1, source.cursors.size)
    }

    @Test fun laterAccessFailureKeepsRealFirstPageWithoutRetryingOrFallback() {
        val source = Source({ page(comment("1"), more = true, next = "page2", total = 30) },
            { throw BilibiliSourceException("合成限额，停止") })
        val result = BilibiliCommentLoader(source).load(request)
        assertEquals(BilibiliLoadStatus.PARTIAL, result.status)
        assertEquals(listOf("1"), result.comments.map { it.id })
        assertEquals("合成限额，停止", result.reason)
        assertEquals(BilibiliSort.HOT, result.actualSort)
        assertEquals(30L, result.reportedTotal)
        assertEquals(1, result.pagesRead)
        assertEquals(2, source.cursors.size)
    }

    @Test fun genericSourceFailureDoesNotExposeExceptionDetails() {
        val source = Source({ throw IllegalStateException("synthetic private exception detail") })
        val result = BilibiliCommentLoader(source).load(request)
        assertEquals(BilibiliLoadStatus.FAILED, result.status)
        assertFalse(result.reason.contains("private exception detail"))
        assertEquals(1, source.cursors.size)
    }

    @Test fun mismatchedSortStopsAndRetainsPreviousVerifiedPage() {
        val source = Source({ page(comment("1"), more = true, next = "page2") },
            { page(comment("2"), sort = BilibiliSort.NEWEST) })
        val result = BilibiliCommentLoader(source).load(request)
        assertEquals(BilibiliLoadStatus.PARTIAL, result.status)
        assertEquals(listOf("1"), result.comments.map { it.id })
        assertEquals(BilibiliSort.HOT, result.actualSort)
        assertEquals(1, result.pagesRead)
    }

    @Test fun malformedIdsOrNegativeCountsFailThePageAtomically() {
        listOf(comment(""), comment("0"), comment("-1"), comment("abc"), comment("01"),
            comment("2").copy(replyCount = -1)).forEach { malformed ->
            val result = BilibiliCommentLoader(Source({ page(comment("1"), malformed) })).load(request)
            assertEquals(BilibiliLoadStatus.FAILED, result.status)
            assertTrue(result.comments.isEmpty())
            assertNull(result.actualSort)
        }
        val result = BilibiliCommentLoader(Source({ page(comment("1"), total = -1) })).load(request)
        assertEquals(BilibiliLoadStatus.FAILED, result.status)
    }

    @Test fun repeatedCursorStopsInsteadOfLoopingWithMoreRequests() {
        val source = Source({ page(comment("1"), more = true, next = "same") },
            { page(comment("2"), more = true, next = "same") })
        val result = BilibiliCommentLoader(source).load(request)
        assertEquals(BilibiliLoadStatus.PARTIAL, result.status)
        assertEquals(listOf("1", "2"), result.comments.map { it.id })
        assertEquals(2, source.cursors.size)
        assertTrue(result.reason.contains("分页"))
    }

    @Test fun duplicateOnlyPageCannotClaimFullCoverageOrContinueFetching() {
        val source = Source({ page(comment("1"), more = true, next = "page2") },
            { page(comment("1"), more = true, next = "page3") })
        val result = BilibiliCommentLoader(source).load(request)
        assertEquals(BilibiliLoadStatus.PARTIAL, result.status)
        assertEquals(listOf("1"), result.comments.map { it.id })
        assertEquals(2, source.cursors.size)
    }

    @Test fun missingNextCursorPreservesAcquiredCommentsAsPartial() {
        val result = BilibiliCommentLoader(Source({ page(comment("1"), more = true) })).load(request)
        assertEquals(BilibiliLoadStatus.PARTIAL, result.status)
        assertEquals(listOf("1"), result.comments.map { it.id })
    }

    @Test fun pageBudgetStopsAfterTwentyPagesAndReportsIncompleteCoverage() {
        val responses = (1..20).map { n -> { page(comment(n.toString()), more = true, next = "page${n + 1}") } }
        val source = Source(*responses.toTypedArray())
        val result = BilibiliCommentLoader(source).load(request.copy(limit = 200))
        assertEquals(BilibiliLoadStatus.PARTIAL, result.status)
        assertEquals(20, result.comments.size)
        assertEquals(20, source.cursors.size)
        assertEquals(20, result.pagesRead)
    }

    @Test fun cancellationBeforeLoadMakesNoSourceRequest() {
        val source = Source()
        val result = BilibiliCommentLoader(source).load(request) { true }
        assertEquals(BilibiliLoadStatus.CANCELLED, result.status)
        assertTrue(source.cursors.isEmpty())
    }

    @Test fun cancellationDuringSecondPageKeepsOnlyPreviouslyAcceptedComments() {
        var cancelled = false
        val source = Source({ page(comment("1"), more = true, next = "page2") },
            { cancelled = true; page(comment("2"), more = true, next = "page3") })
        val result = BilibiliCommentLoader(source).load(request) { cancelled }
        assertEquals(BilibiliLoadStatus.CANCELLED, result.status)
        assertEquals(listOf("1"), result.comments.map { it.id })
        assertEquals(1, result.pagesRead)
        assertEquals(2, source.cursors.size)
    }

    @Test fun interruptedSourceStopsAndPreservesThreadInterruption() {
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            val source = Source({ throw InterruptedException("synthetic cancellation") })
            val result = BilibiliCommentLoader(source).load(request)
            assertEquals(BilibiliLoadStatus.CANCELLED, result.status)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, source.cursors.size)
        } finally { Thread.interrupted() }
    }
}
