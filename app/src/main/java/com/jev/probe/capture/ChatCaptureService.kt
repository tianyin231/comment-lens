package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.jev.probe.comments.*
import com.jev.probe.core.Prefs
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors

/** 读屏与 Jev 请求分开执行；自动模式随前台帖子持续运行，不需要创建采样。 */
open class ChatCaptureService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor()
    private val judge = Executors.newSingleThreadExecutor()
    private val disk = Executors.newSingleThreadExecutor()
    private var stream = LiveSentiment()
    private lateinit var cache: LiveCacheStore
    private var cacheFailure = ""
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null
    private var alive = false
    private var reading = false
    private var judging = false
    private var navigation = 0L
    private var foreground = ""
    private var activity = ""
    private var appWindow = -1
    private var manualRead = false
    private var manualRun = false
    private var retryAt = 0L
    private var failure = ""
    private var elapsed = 0L
    private var keepAlive = false
    private var lastMode = CommentCaptureMode.MANUAL
    private val automatic get() = prefs.commentCaptureMode == CommentCaptureMode.AUTO
    private val poll = object : Runnable {
        override fun run() {
            if (!alive) return
            refresh()
            main.postDelayed(this, 1200)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        alive = true; prefs = Prefs(this); lastMode = prefs.commentCaptureMode
        cache = LiveCacheStore(this)
        stream = LiveSentiment(runCatching { cache.load() }.getOrElse {
            cacheFailure = "旧缓存未能读取，当前内容会重新判断"; emptyList()
        })
        overlay = OverlayController(this).apply {
            onCapture = { manualRead = true; manualRun = true; failure = ""; retryAt = 0; refresh() }
            onToggleAuto = {
                prefs.commentCaptureMode = if (automatic) CommentCaptureMode.MANUAL else CommentCaptureMode.AUTO
                syncMode(); refresh(); publish()
            }
        }
        main.post(poll)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!alive || event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className?.toString()?.endsWith("Activity") == true) {
            val next = "${event.packageName}/${event.className}/${event.windowId}"
            if (activity.isNotBlank() && activity != next) resetPage()
            activity = next
        }
    }

    private fun syncMode() {
        if (lastMode == prefs.commentCaptureMode) return
        lastMode = prefs.commentCaptureMode
        stream.invalidateRequests(); manualRead = false; manualRun = false
        failure = ""; retryAt = 0
    }

    private fun resetPage() {
        navigation++; stream.detach(); manualRun = false; manualRead = false
        failure = ""; retryAt = 0; elapsed = 0
        overlay?.hide()
    }

    private fun refresh() {
        syncMode()
        if (keepAlive != prefs.enabled) {
            keepAlive = prefs.enabled
            if (keepAlive) runCatching { KeepAliveService.start(this) }
            else { stopService(Intent(this, KeepAliveService::class.java)); resetPage() }
        }
        if (!prefs.enabled || reading) return
        val root = currentAppRoot() ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != foreground || root.windowId != appWindow) {
            resetPage(); foreground = pkg; appWindow = root.windowId
        }
        // 只读取已经有明确正文选择器的平台，不把整个屏幕当评论。
        if (pkg != "com.max.xiaoheihe") { root.recycle(); overlay?.hide(); return }
        // 一些页面静止时也持续发滚动事件；按固定频率读取，不能等待“滚动结束”。
        val token = navigation
        val collect = automatic || manualRead
        val requested = manualRead
        manualRead = false; reading = true
        reader.execute {
            val result = runCatching { CommentPageParser.parse(pkg, CommentNodeReader.read(root)) }
            root.recycle()
            main.post {
                reading = false
                if (!alive || !prefs.enabled || token != navigation) return@post
                // 读树期间可能已切换窗口；先核对，避免短暂显示上一帖的结果。
                val current = currentAppRoot()
                val same = current?.packageName?.toString() == pkg && current.windowId == appWindow
                current?.recycle()
                if (!same) { resetPage(); return@post }
                result.fold(onSuccess = { page ->
                    if (page.postKey.isBlank()) { resetPage(); return@fold }
                    val previous = stream.snapshot
                    val changed = stream.follow(pkg, page, collect)
                    if (changed || previous != stream.snapshot) saveCache()
                    if (changed) { failure = ""; retryAt = 0; elapsed = 0 }
                    if (requested) manualRun = true
                    overlay?.show()
                    publish(); analyzeNext()
                }, onFailure = {
                    failure = "暂时读不到评论，稍后自动重试"; publish()
                })
            }
        }
    }

    private fun analyzeNext() {
        if (!alive || !prefs.enabled || judging || (!automatic && !manualRun)) return
        if (SystemClock.uptimeMillis() < retryAt) return
        if (prefs.judgeKey.isBlank()) { failure = "请在设置中填写 Jev 密钥"; manualRun = false; publish(); return }
        val batch = stream.pending()
        if (batch.texts.isEmpty()) { manualRun = false; publish(); return }
        val nav = navigation
        val config = prefs.commentJevConfig()
        judging = true; failure = ""; publish()
        judge.execute {
            val started = SystemClock.elapsedRealtime()
            val result = runCatching { JevSentimentClient(config).analyze(batch.texts) }
            val duration = SystemClock.elapsedRealtime() - started
            main.post {
                judging = false
                if (!alive || !prefs.enabled) return@post
                if (nav == navigation && stream.isCurrent(batch) && (automatic || manualRun)) {
                    result.fold(onSuccess = { decisions ->
                        if (stream.apply(batch, decisions)) { elapsed = duration; failure = ""; retryAt = 0; saveCache() }
                    }, onFailure = { error ->
                        failure = error.message?.take(130) ?: "Jev 暂时不可用"
                        retryAt = SystemClock.uptimeMillis() + 15000
                        manualRun = false
                    })
                }
                publish()
                // 延迟返回的旧帖答案丢弃，新帖仍可接着处理，不会卡住整条流。
                analyzeNext()
            }
        }
    }

    private fun publish() {
        val pending = stream.pending().texts.isNotEmpty()
        val message = when {
            failure.isNotBlank() -> failure
            cacheFailure.isNotBlank() -> cacheFailure
            judging -> "Jev 正在判断新文字…"
            automatic && pending -> "等待判断新文字"
            automatic && elapsed == 0L && stream.snapshot.post?.decision != null -> "已恢复本帖缓存 · 等待新评论"
            automatic -> "实时跟随" + if (elapsed > 0) " · 上次 Jev ${elapsed} ms" else " · 等待评论"
            else -> "手动模式 · 点击下方分析本屏"
        }
        overlay?.update(stream.snapshot, automatic, judging || (reading && manualRun), message, failure.isNotBlank() || cacheFailure.isNotBlank())
    }

    private fun saveCache() {
        val posts = stream.cachedPosts()
        disk.execute {
            val saved = runCatching { cache.save(posts) }.isSuccess
            main.post {
                if (alive) {
                    cacheFailure = if (saved) "" else "本地缓存保存失败，当前结果仍保留在内存"
                    publish()
                }
            }
        }
    }

    private fun currentAppRoot(): AccessibilityNodeInfo? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && overlay?.ownsWindow(it.id) != true }
        val app = apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive } ?: apps.firstOrNull()
        return app?.root ?: rootInActiveWindow?.takeIf { overlay?.ownsWindow(it.windowId) != true }
    }

    override fun onInterrupt() { if (alive) resetPage() }
    override fun onDestroy() {
        alive = false; navigation++; main.removeCallbacksAndMessages(null)
        overlay?.hide(); reader.shutdownNow(); judge.shutdownNow(); disk.shutdown()
        stopService(Intent(this, KeepAliveService::class.java)); super.onDestroy()
    }
}
