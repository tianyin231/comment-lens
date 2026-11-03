package com.jev.probe.capture

import com.jev.probe.comments.CommentCaptureMode
import org.junit.Assert.*
import org.junit.Test

/** Exercises the ticket checks shared by the service's reader and judge callbacks. */
class CaptureGuardTest {
    @Test fun versionedActivitiesAreNavigationButTabsAndWebViewsAreNot() {
        assertTrue(CaptureGuard.isActivity("com.max.xiaoheihe.MainActivity"))
        assertTrue(CaptureGuard.isActivity("com.max.xiaoheihe.WebNewsPostPageActivityV2"))
        assertTrue(CaptureGuard.isActivity("com.max.xiaoheihe.PicturePostPageActivityV2"))
        assertFalse(CaptureGuard.isActivity("android.webkit.WebView"))
        assertFalse(CaptureGuard.isActivity("android.widget.RelativeLayout"))
    }
    private val postWindow = CaptureWindow("com.max.xiaoheihe", 7)
    private val videoWindow = CaptureWindow("tv.danmaku.bili", 7, "BV1xx411c7mD")
    private fun guard(mode: CommentCaptureMode = CommentCaptureMode.MANUAL) =
        CaptureGuard(mode).apply { updateWindow(postWindow) }

    @Test fun automaticReadCannotCollectAfterSwitchingToManual() {
        val guard = guard(CommentCaptureMode.AUTO)
        val read = guard.token()!!
        assertTrue(guard.updateMode(CommentCaptureMode.MANUAL))
        assertFalse(guard.isCurrent(read))
        assertFalse(guard.accepts(read, postWindow))
        assertTrue(guard.accepts(guard.token()!!, postWindow))
    }

    @Test fun manualReadOrJudgmentCannotResumeAfterSwitchingToAutomatic() {
        val guard = guard()
        val read = guard.token()!!
        val judgment = guard.token()!!
        assertTrue(guard.updateMode(CommentCaptureMode.AUTO))
        assertFalse(guard.accepts(read, postWindow))
        assertFalse(guard.accepts(judgment, postWindow))
    }

    @Test fun switchingModeBackDoesNotReviveEarlierReadOrJudgment() {
        val guard = guard(CommentCaptureMode.AUTO)
        val old = guard.token()!!
        guard.updateMode(CommentCaptureMode.MANUAL)
        guard.updateMode(CommentCaptureMode.AUTO)
        assertFalse(guard.isCurrent(old))
        assertFalse(guard.accepts(old, postWindow))
        assertTrue(guard.accepts(guard.token()!!, postWindow))
    }

    @Test fun sameActivityAndWindowNavigationInvalidatesPreviousTickets() {
        val guard = guard()
        val oldRead = guard.token()!!
        val oldJudgment = guard.token()!!
        guard.invalidate()
        assertFalse(guard.accepts(oldRead, postWindow))
        assertFalse(guard.accepts(oldJudgment, postWindow))
        assertTrue(guard.accepts(guard.token()!!, postWindow))
    }

    @Test fun leavingAndReturningToSameWindowDoesNotRevivePreviousTickets() {
        val guard = guard()
        val old = guard.token()!!
        assertTrue(guard.updateWindow(CaptureWindow("other.app", 8)))
        assertFalse(guard.accepts(old, postWindow))
        assertTrue(guard.updateWindow(postWindow))
        assertFalse(guard.accepts(old, postWindow))
        assertTrue(guard.accepts(guard.token()!!, postWindow))
    }

    @Test fun windowIdAndPackageAreBothCheckedBeforeNextPoll() {
        val guard = guard()
        val ticket = guard.token()!!
        assertTrue(guard.isCurrent(ticket))
        assertFalse(guard.accepts(ticket, CaptureWindow(postWindow.packageName, 8)))
        assertFalse(guard.accepts(ticket, CaptureWindow("other.app", postWindow.id)))
        assertFalse(guard.accepts(ticket, null))
        assertTrue(guard.accepts(ticket, postWindow))
    }

    @Test fun absentWindowInvalidatesOldTicketsAndCannotCreateNewTicket() {
        val guard = guard()
        val old = guard.token()!!
        assertTrue(guard.updateWindow(null))
        assertNull(guard.token())
        assertFalse(guard.isCurrent(old))
        guard.updateWindow(postWindow)
        assertFalse(guard.accepts(old, postWindow))
    }

    @Test fun routinePollsWithSameModeAndWindowKeepTicketsValid() {
        val guard = guard(CommentCaptureMode.AUTO)
        val ticket = guard.token()!!
        repeat(3) {
            assertFalse(guard.updateMode(CommentCaptureMode.AUTO))
            assertFalse(guard.updateWindow(postWindow.copy()))
            assertTrue(guard.isCurrent(ticket))
            assertTrue(guard.accepts(ticket, postWindow.copy()))
        }
    }

    @Test fun switchingVideosInTheSamePackageAndWindowInvalidatesOldTickets() {
        val guard = CaptureGuard().apply { updateWindow(videoWindow) }
        val old = guard.token()!!
        val next = videoWindow.copy(contentKey = "BV1yy411c7mD")
        assertTrue(guard.updateWindow(next))
        assertFalse(guard.isCurrent(old))
        assertFalse(guard.accepts(old, next))
        assertTrue(guard.accepts(guard.token()!!, next))
    }

    @Test fun callbacksRejectChangedOrUnreadableVideoIdsBeforeTheNextPoll() {
        val guard = CaptureGuard().apply { updateWindow(videoWindow) }
        val ticket = guard.token()!!
        assertFalse(guard.accepts(ticket, videoWindow.copy(contentKey = "BV1yy411c7mD")))
        assertFalse(guard.accepts(ticket, videoWindow.copy(contentKey = "")))
        assertTrue(guard.isCurrent(ticket))
        assertTrue(guard.accepts(ticket, videoWindow.copy()))
    }

    @Test fun repeatedPollsForTheSameVideoKeepCurrentTicketsValid() {
        val guard = CaptureGuard().apply { updateWindow(videoWindow) }
        val ticket = guard.token()!!
        repeat(3) {
            assertFalse(guard.updateWindow(videoWindow.copy()))
            assertTrue(guard.isCurrent(ticket))
            assertTrue(guard.accepts(ticket, videoWindow.copy()))
        }
    }

    @Test fun temporarilyUnreadableVideoIdInvalidatesOldTicketsEvenAfterItReturns() {
        val guard = CaptureGuard().apply { updateWindow(videoWindow) }
        val old = guard.token()!!
        assertTrue(guard.updateWindow(videoWindow.copy(contentKey = "")))
        assertFalse(guard.isCurrent(old))
        assertTrue(guard.updateWindow(videoWindow))
        assertFalse(guard.accepts(old, videoWindow))
        assertTrue(guard.accepts(guard.token()!!, videoWindow))
    }
}
