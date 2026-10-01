package com.jev.probe.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.doOnLayout
import com.jev.probe.SettingsActivity
import com.jev.probe.comments.LiveSnapshot
import com.jev.probe.comments.Sentiment
import kotlin.math.abs
import kotlin.math.roundToInt

/** 常驻部分只占屏幕右边 24dp；正文小点和评论色条分开，详情按需打开。 */
class OverlayController(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val positions = ctx.getSharedPreferences("sentiment_rail", Context.MODE_PRIVATE)
    private var rail: RailView? = null
    private var railParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var content: LinearLayout? = null
    private var statusText: TextView? = null
    private var modeSwitch: Switch? = null
    private var manualButton: TextView? = null
    private var ocrButton: TextView? = null
    private var canOcr = false
    private var scroll: ScrollView? = null
    private var snapshot = LiveSnapshot()
    private var automatic = false
    private var working = false
    private var status = ""
    private var failed = false
    private var changingSwitch = false
    var onCapture: (() -> Unit)? = null
    var onToggleAuto: (() -> Unit)? = null
    var onOcr: (() -> Unit)? = null

    private val ink = Color.rgb(37, 49, 57)
    private val sub = Color.rgb(111, 124, 131)
    private val positive = Color.rgb(32, 149, 130)
    private val negative = Color.rgb(213, 107, 101)
    private val quiet = Color.rgb(183, 192, 198)
    private val paper = Color.rgb(250, 252, 252)
    private val screen get() = wm.currentWindowMetrics.bounds
    private fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).roundToInt()
    private fun color(value: Sentiment?) = when (value) {
        Sentiment.POSITIVE -> positive
        Sentiment.NEGATIVE -> negative
        Sentiment.MIXED -> Color.rgb(176, 160, 151)
        else -> quiet
    }

    fun update(value: LiveSnapshot, auto: Boolean, busy: Boolean, message: String, error: Boolean = false,
        ocrAvailable: Boolean = false) {
        val changed = snapshot != value
        val changedPost = snapshot.postKey != value.postKey
        snapshot = value; automatic = auto; working = busy; status = message; failed = error
        canOcr = ocrAvailable
        rail?.apply {
            contentDescription = "本帖评论情绪，已判断 ${value.judged.size} 条：正面 ${value.percent(Sentiment.POSITIVE)}%，负面 ${value.percent(Sentiment.NEGATIVE)}%。点击展开，上下拖动调整位置"
            invalidate()
        }
        if (changed) renderDetails(resetScroll = changedPost)
        updateControls()
    }

    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END }

    fun show() {
        if (rail != null || !Settings.canDrawOverlays(ctx)) return
        val lp = params(dp(24), dp(212)).apply {
            y = positions.getInt("y", screen.height() / 3).coerceIn(dp(30), maxOf(dp(30), screen.height() - dp(270)))
        }
        val view = RailView()
        var down = 0f; var startY = 0; var moved = false
        view.setOnClickListener { if (panel == null) expand() else collapse() }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = event.rawY; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawY - down).toInt()
                    if (abs(delta) > dp(6)) moved = true
                    if (moved) {
                        collapse()
                        lp.y = (startY + delta).coerceIn(dp(30), maxOf(dp(30), screen.height() - dp(270)))
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) positions.edit().putInt("y", lp.y).apply() else view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        rail = view; railParams = lp
        if (runCatching { wm.addView(view, lp) }.isFailure) { rail = null; railParams = null }
    }

    fun hide() {
        collapse()
        rail?.let { runCatching { wm.removeView(it) } }
        rail = null; railParams = null
    }

    fun ownsWindow(id: Int): Boolean = listOfNotNull(rail, panel).any { view ->
        val node = view.createAccessibilityNodeInfo()
        try { node.windowId == id } finally { node.recycle() }
    }

    fun hideForCapture(hidden: Boolean) {
        listOfNotNull(rail, panel).forEach { it.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }
    }

    private fun expand() {
        if (rail == null || panel != null) return
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply { setColor(paper); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(226, 232, 231)) }
            elevation = dp(6).toFloat()
        }
        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("评论风向", 18f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("设置") {
            hide(); ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        header.addView(action("收起") { collapse() })
        body.addView(header)
        modeSwitch = Switch(ctx).apply {
            text = "自动跟随帖子"; textSize = 14f; setTextColor(ink); minHeight = dp(42)
            setOnCheckedChangeListener { _, _ -> if (!changingSwitch) onToggleAuto?.invoke() }
        }
        body.addView(modeSwitch)
        statusText = text("", 11f, sub).apply { minHeight = dp(30) }
        body.addView(statusText)
        scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            addView(content)
        }
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        manualButton = action("分析当前屏幕") { onCapture?.invoke() }.apply {
            gravity = Gravity.CENTER; setTextColor(positive)
            background = GradientDrawable().apply { setColor(Color.rgb(234, 243, 241)); cornerRadius = dp(10).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(10) }
        }
        body.addView(manualButton)
        ocrButton = action("OCR 识别并分析本屏正文") { onOcr?.invoke() }.apply {
            gravity = Gravity.CENTER; setTextColor(positive)
        }
        body.addView(ocrButton)
        body.addView(text("点：帖文  ·  条：本帖全部评论  ·  灰色：中性或不确定", 10f, sub))
        val height = minOf(dp(510), screen.height() - dp(90))
        val lp = params(minOf(dp(330), screen.width() - dp(38)), height).apply {
            x = dp(28)
            y = (railParams?.y ?: dp(60)).coerceIn(dp(28), maxOf(dp(28), screen.height() - height - dp(55)))
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        body.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { collapse(); true } else false
        }
        panel = body
        if (runCatching { wm.addView(body, lp) }.isFailure) { panel = null; return }
        renderDetails(); updateControls()
    }

    private fun collapse() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null; content = null; statusText = null; modeSwitch = null; manualButton = null; scroll = null; ocrButton = null
    }

    private fun updateControls() {
        changingSwitch = true; modeSwitch?.isChecked = automatic; changingSwitch = false
        statusText?.apply { text = status; setTextColor(if (failed) negative else sub) }
        manualButton?.apply {
            visibility = if (automatic) View.GONE else View.VISIBLE
            isEnabled = !working; alpha = if (working) .5f else 1f
            text = if (working) "Jev 判断中…" else "分析当前屏幕"
        }
        ocrButton?.apply {
            visibility = if (canOcr) View.VISIBLE else View.GONE
            isEnabled = !working; alpha = if (working) .5f else 1f
        }
    }

    private fun renderDetails(resetScroll: Boolean = false) {
        val target = content ?: return
        val y = if (resetScroll) 0 else scroll?.scrollY ?: 0
        val anchor = if (resetScroll) null else (0 until target.childCount).asSequence().map(target::getChildAt)
            .firstOrNull { it.tag is String && it.bottom > y }?.let { it.tag to (it.top - y) }
        target.removeAllViews()
        target.addView(text("评论情绪 · 已判断 ${snapshot.judged.size} 条", 11f, sub))
        val stats = LinearLayout(ctx)
        stats.addView(text("${snapshot.percent(Sentiment.POSITIVE)}% 正面", 21f, positive, true), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(text("${snapshot.percent(Sentiment.NEGATIVE)}% 负面", 21f, negative, true), LinearLayout.LayoutParams(0, -2, 1f))
        target.addView(stats)
        val pending = snapshot.comments.size - snapshot.judged.size
        if (pending > 0) target.addView(text("还有 $pending 条待判断", 11f, sub))
        if (snapshot.judged.isNotEmpty()) target.addView(text(
            "中性 ${snapshot.count(Sentiment.NEUTRAL)} · 褒贬混合 ${snapshot.count(Sentiment.MIXED)} · 不确定 ${snapshot.count(Sentiment.UNCERTAIN)}", 11f, sub))
        divider(target)
        snapshot.post?.let { post ->
            val source = snapshot.postSource.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            target.addView(text("● 帖文$source · ${post.decision?.sentiment?.label ?: "待判断"}", 11f, decisionColor(post.decision?.sentiment)))
            target.addView(text(post.text, 12f, sub).apply { maxLines = 4; setOnClickListener { maxLines = if (maxLines == 4) Int.MAX_VALUE else 4 } })
        } ?: target.addView(text(if (snapshot.postSource.isBlank()) "打开帖子后，正文会在这里单独显示。"
            else "切回正文页可读取文章文字；本页评论可单独分析。", 12f, sub))
        divider(target)
        if (snapshot.comments.isEmpty()) target.addView(text(
            if (automatic) "正常浏览评论区即可，读到的新评论会自动更新。" else "默认手动，可分析本屏，或打开自动跟随。", 13f, sub))
        snapshot.comments.asReversed().forEach { comment ->
            val decision = comment.decision
            target.addView(text(comment.text, 13f, ink).apply { tag = comment.id })
            val label = decision?.let {
                "${it.sentiment.label}  ·  置信度 ${(it.confidence * 100).roundToInt()}%" +
                    if (it.sentiment != it.original) "（原判断：${it.original.label}）" else ""
            } ?: "等待 Jev"
            target.addView(text(label, 10f, decisionColor(decision?.sentiment)))
            divider(target)
        }
        target.addView(text("最近 5 帖保留本地缓存，返回可继续。仅判断文字情绪，不代表对原帖的立场。图片未参与，同文合并，比例不代表人数。", 10f, sub))
        target.doOnLayout {
            val view = anchor?.let { saved -> (0 until target.childCount).asSequence().map(target::getChildAt).firstOrNull { it.tag == saved.first } }
            scroll?.scrollTo(0, if (view != null) view.top - anchor.second else y)
        }
    }

    private fun divider(parent: LinearLayout) = parent.addView(View(ctx).apply {
        setBackgroundColor(Color.rgb(230, 235, 234))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(9); bottomMargin = dp(9) }
    })
    private fun decisionColor(value: Sentiment?) = if (value == Sentiment.POSITIVE || value == Sentiment.NEGATIVE) color(value) else sub
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun action(value: String, click: () -> Unit) = text(value, 12f, sub).apply {
        setPadding(dp(9), dp(12), dp(9), dp(12)); setOnClickListener { click() }
    }

    private inner class RailView : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val x = width - dp(7).toFloat()
            // 帖文点比评论色条更淡，二者不混入同一比例。
            paint.color = color(snapshot.post?.decision?.sentiment); paint.alpha = 175
            canvas.drawCircle(x, dp(10).toFloat(), dp(3).toFloat(), paint)
            paint.alpha = 255
            val left = x - dp(3); val right = x + dp(3)
            val top = dp(24).toFloat(); val bottom = height - dp(12).toFloat()
            paint.color = Color.rgb(216, 223, 225)
            canvas.drawRoundRect(left, top, right, bottom, dp(3).toFloat(), dp(3).toFloat(), paint)
            val total = snapshot.judged.size
            if (total > 0) {
                canvas.save()
                val path = Path().apply { addRoundRect(left, top, right, bottom, dp(3).toFloat(), dp(3).toFloat(), Path.Direction.CW) }
                canvas.clipPath(path)
                val positiveEnd = top + (bottom - top) * snapshot.count(Sentiment.POSITIVE) / total
                val negativeStart = bottom - (bottom - top) * snapshot.count(Sentiment.NEGATIVE) / total
                paint.color = positive; canvas.drawRect(left, top, right, positiveEnd, paint)
                paint.color = negative; canvas.drawRect(left, negativeStart, right, bottom, paint)
                canvas.restore()
            }
            if (working || failed || !automatic) {
                paint.color = if (failed) negative else quiet
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat()
                canvas.drawCircle(x, height - dp(4).toFloat(), dp(2).toFloat(), paint)
                paint.style = Paint.Style.FILL
            }
        }
    }
}
