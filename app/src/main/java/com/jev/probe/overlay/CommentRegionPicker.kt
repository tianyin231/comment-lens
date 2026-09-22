package com.jev.probe.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** 每次框选只用于本屏，防止滚动或旋转后沿用过期范围。 */
class CommentRegionPicker(private val ctx: Context, private val done: (Rect?) -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private val rect = Rect()
    private var drawing: View? = null
    private val density = ctx.resources.displayMetrics.density
    fun show() {
        val frame = FrameLayout(ctx)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val area = object : View(ctx) {
            var startX = 0; var startY = 0
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(0x22000000)
                paint.color = Color.rgb(18, 108, 102); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * density
                canvas.drawRect(rect, paint)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                val x = event.x.toInt(); val y = event.y.toInt()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { startX = x; startY = y; rect.set(x, y, x, y) }
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        rect.set(min(x, startX), min(y, startY), max(x, startX), max(y, startY))
                        if (event.action == MotionEvent.ACTION_UP) performClick()
                    }
                }
                invalidate(); return true
            }
            override fun performClick(): Boolean { super.performClick(); return true }
        }
        drawing = area; frame.addView(area, FrameLayout.LayoutParams(-1, -1))
        frame.addView(TextView(ctx).apply {
            text = "拖动框住评论正文，避开昵称、时间、按钮\n本次范围仅用于手动采集"
            setTextColor(Color.WHITE); setBackgroundColor(0xEE126C66.toInt()); textSize = 15f
            setPadding(20, 18, 20, 18)
        }, FrameLayout.LayoutParams(-1, -2).apply { topMargin = (32 * density).toInt() })
        val buttons = LinearLayout(ctx)
        buttons.addView(Button(ctx).apply { text = "取消"; setOnClickListener { finish(null) } }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(ctx).apply {
            text = "识别框内正文"
            setOnClickListener {
                if (rect.width() < 20 || rect.height() < 20) return@setOnClickListener
                val offset = IntArray(2); area.getLocationOnScreen(offset)
                finish(Rect(rect).apply { offset(offset[0], offset[1]) })
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        frame.addView(buttons, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = (28 * density).toInt() })
        root = frame
        wm.addView(frame, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT))
    }
    private fun finish(value: Rect?) { dismiss(); done(value) }
    fun dismiss() { root?.let { runCatching { wm.removeView(it) } }; root = null; drawing = null }
}
