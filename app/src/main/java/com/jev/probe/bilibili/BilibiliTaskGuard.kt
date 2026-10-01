package com.jev.probe.bilibili

import java.util.concurrent.atomic.AtomicLong

/** 输入变更/新任务/销毁均失效旧任务；invalidate 与 UI deliver 在主线程调用。 */
internal class BilibiliTaskGuard {
    private val generation = AtomicLong()
    fun ticket(): Long = generation.get()
    fun invalidate() { generation.incrementAndGet() }
    fun isCurrent(ticket: Long) = generation.get() == ticket

    fun deliver(ticket: Long, action: () -> Unit): Boolean {
        if (!isCurrent(ticket)) return false
        action()
        return true
    }
}
