package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.comments.CommentCaptureMode
import com.jev.probe.comments.CommentEngine
import com.jev.probe.comments.JevCommentConfig
import com.jev.probe.comments.JevSentimentClient
import com.jev.probe.core.Prefs
import java.net.URI
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val ink = Color.rgb(37, 49, 57)
    private val sub = Color.rgb(111, 124, 131)
    private val accent = Color.rgb(32, 149, 130)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.rgb(250, 252, 252))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(28))
        }
        root.padForSystemBars()
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(text("实时情绪设置", 24f, bold = true))
        root.addView(text("平时只显示一条细线，点击展开详情。默认手动；开启自动后，换帖也会继续跟随。", 13f, sub))
        val automatic = Switch(this).apply {
            text = "自动跟随帖子"; textSize = 16f; setTextColor(ink)
            isChecked = prefs.commentCaptureMode == CommentCaptureMode.AUTO
            minHeight = dp(56)
        }
        root.addView(automatic)
        root.addView(text("仅判断新出现的评论文字，同文不重复发送。情绪条统计本帖全部已判断的评论，详情展示全部评论与结果。切换帖子自动跟随，返回已缓存的帖子可继续。", 12f, sub))

        root.addView(text("Jev 快速判断", 19f, bold = true).apply { setPadding(0, dp(26), 0, dp(8)) })
        root.addView(text("每段文字一道情绪选择题。帖文单独判断一次，不随每条评论重复上传；不发送历史或回复上下文。", 12f, sub))
        val endpoint = edit(prefs.judgeEndpoint(), "完整的 Jev 接口地址")
        val key = edit(prefs.judgeKey, "接口密钥", password = true)
        val model = edit(prefs.judgeModel, "Jev 模型名称")
        val presets = LinearLayout(this)
        listOf(
            Triple("OpenRouter", Prefs.DEFAULT_JUDGE_BASE_OPENROUTER + "/alpha/decisions", Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER),
            Triple("TypeSafe", Prefs.DEFAULT_JUDGE_BASE_TYPESAFE + "/v1/systemone", Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE)
        ).forEach { (label, url, name) ->
            presets.addView(button(label) { endpoint.setText(url); model.setText(name) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        }
        root.addView(presets)
        root.addView(text("Jev 接口地址", 12f, sub)); root.addView(endpoint)
        root.addView(text("接口密钥", 12f, sub)); root.addView(key)
        root.addView(text("模型名称", 12f, sub)); root.addView(model)
        val thresholdLabel = text("", 13f)
        val threshold = SeekBar(this).apply {
            max = 100; progress = (prefs.commentConfidenceFloor * 100).roundToInt()
            thresholdLabel.text = "低置信度阈值：${progress}%"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { thresholdLabel.text = "低置信度阈值：${progress}%" }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        root.addView(thresholdLabel); root.addView(threshold)
        root.addView(text("低于阈值标为不确定。只判断文字情绪，不把正负面当作赞成或反对原帖；反讽、短回复可能需要上下文。", 12f, sub))
        fun config() = JevCommentConfig(endpoint.text.toString().trim(), key.text.toString().trim(), model.text.toString().trim(), threshold.progress / 100.0)
        val result = text("", 12f, sub)
        val test = button("测试 Jev") {}
        test.setOnClickListener {
            val value = config()
            val problem = validate(value, true)
            if (problem != null) { result.text = problem; return@setOnClickListener }
            test.isEnabled = false; result.text = "正在用一条合成评论测试…"
            worker.execute {
                val response = runCatching { "连接成功 · ${JevSentimentClient(value).ping()}" }
                    .getOrElse { it.message?.take(160) ?: "连接失败" }
                main.post { if (!isDestroyed) { result.text = response; test.isEnabled = true } }
            }
        }
        root.addView(test); root.addView(result)
        root.addView(text("当前适配", 17f, bold = true))
        root.addView(text("小黑盒帖子评论与楼中楼、B站普通视频页的主评论和回复预览。B站先点标题展开简介，再进入评论；仅判断本屏已加载的评论文字，完整楼中楼暂未适配。昵称、点赞数、推荐内容、图片、视频与弹幕不参与判断。", 12f, sub))
        root.addView(text("最近 5 个帖子的已读文字和判断结果缓存在本机，退出再进入或重启服务后可恢复；第 6 帖会替换最久未访问的一帖。只有新增或尚未判断的文字发送至 Jev，缓存不作为上下文上传。", 12f, sub))
        root.addView(button("保存设置") {
            val value = config()
            val problem = validate(value, false)
            if (problem != null) { Toast.makeText(this, problem, Toast.LENGTH_LONG).show(); return@button }
            prefs.commentCaptureMode = if (automatic.isChecked) CommentCaptureMode.AUTO else CommentCaptureMode.MANUAL
            prefs.commentEngine = CommentEngine.JEV
            prefs.judgeProvider = Prefs.PROVIDER_CUSTOM; prefs.judgeBaseUrl = value.endpoint
            prefs.judgeKey = value.key; prefs.judgeModel = value.model
            prefs.commentConfidenceFloor = value.confidenceFloor
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show(); finish()
        })
    }

    private fun validate(config: JevCommentConfig, needKey: Boolean): String? {
        val uri = runCatching { URI(config.endpoint) }.getOrNull()
        return when {
            uri?.scheme != "https" || uri.host.isNullOrBlank() -> "请填写完整的 HTTPS 接口地址"
            config.model.isBlank() -> "请填写模型名称"
            needKey && config.key.isBlank() -> "请填写 Jev 密钥"
            else -> null
        }
    }
    private fun text(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun edit(value: String, placeholder: String, password: Boolean = false) = EditText(this).apply {
        setText(value); hint = placeholder; textSize = 14f; setTextColor(ink); setHintTextColor(sub)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        background = rounded(Color.rgb(239, 243, 242)); setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun button(value: String, click: () -> Unit) = text(value, 14f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; background = rounded(accent)
        setPadding(dp(8), dp(13), dp(8), dp(13))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        setOnClickListener { click() }
    }
    private fun rounded(color: Int) = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color) }
    override fun onDestroy() { super.onDestroy(); worker.shutdownNow(); main.removeCallbacksAndMessages(null) }
}
