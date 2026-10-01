package com.jev.probe.capture

import com.jev.probe.comments.CommentCaptureMode
import org.junit.Assert.*
import org.junit.Test

/** Exercises the ticket checks shared by the service's reader and judge callbacks. */
class CaptureGuardTest {
    private val postWindow = CaptureWindow("com.max.xiaoheihe", 7)
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
}
