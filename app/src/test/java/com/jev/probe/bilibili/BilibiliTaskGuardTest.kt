package com.jev.probe.bilibili

import org.junit.Assert.*
import org.junit.Test

class BilibiliTaskGuardTest {
    @Test fun oldProgressAndFinalCallbacksCannotDisplayResultsOrUnlockCurrentTask() {
        val guard = BilibiliTaskGuard()
        val old = guard.ticket()
        guard.invalidate()
        val current = guard.ticket()
        var rendered = "current task loading"
        var unlocked = false
        assertFalse(guard.deliver(old) { rendered = "old progress" })
        assertFalse(guard.deliver(old) { rendered = "old final"; unlocked = true })
        assertEquals("current task loading", rendered)
        assertFalse(unlocked)
        assertTrue(guard.deliver(current) { rendered = "current final"; unlocked = true })
        assertEquals("current final", rendered)
        assertTrue(unlocked)
    }

    @Test fun repeatedInputChangesAndCancellationNeverReviveAnEarlierTicket() {
        val guard = BilibiliTaskGuard()
        val old = guard.ticket()
        repeat(4) { guard.invalidate() }
        assertFalse(guard.isCurrent(old))
        assertFalse(guard.deliver(old) { fail("cancelled callback must not run") })
        assertTrue(guard.isCurrent(guard.ticket()))
    }

    @Test fun unchangedTicketCanDeliverIncrementalProgressAndFinalResult() {
        val guard = BilibiliTaskGuard()
        val ticket = guard.ticket()
        val delivered = mutableListOf<String>()
        assertTrue(guard.deliver(ticket) { delivered += "progress" })
        assertTrue(guard.deliver(ticket) { delivered += "final" })
        assertEquals(listOf("progress", "final"), delivered)
    }
}
