package com.jev.probe.capture

import com.jev.probe.comments.CommentCaptureMode

internal data class CaptureWindow(val packageName: String, val id: Int)

/** 读屏和判断共用页面代际；同一窗口再次进入页面也不能接受上一次的异步结果。 */
internal class CaptureGuard(initialMode: CommentCaptureMode = CommentCaptureMode.MANUAL) {
    data class Token internal constructor(internal val revision: Long, internal val window: CaptureWindow)

    private var revision = 0L
    private var mode = initialMode
    private var window: CaptureWindow? = null

    fun updateMode(next: CommentCaptureMode): Boolean {
        if (mode == next) return false
        mode = next; invalidate()
        return true
    }

    fun updateWindow(next: CaptureWindow?): Boolean {
        if (window == next) return false
        window = next; invalidate()
        return true
    }

    fun invalidate() { revision++ }
    fun token(): Token? = window?.let { Token(revision, it) }
    fun isCurrent(token: Token) = token.revision == revision && token.window == window

    // 当前窗口来自回调时的重新读取，也能拦住轮询尚未观察到的前台切换。
    fun accepts(token: Token, current: CaptureWindow?) = isCurrent(token) && token.window == current
}
