package com.jev.probe

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.jev.probe.bilibili.*
import com.jev.probe.comments.JevSentimentClient
import com.jev.probe.comments.LiveDecision
import com.jev.probe.comments.LiveText
import com.jev.probe.comments.Sentiment
import com.jev.probe.core.Prefs
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/** 分享文本只会填入输入框；用户点击后才检查，尚未获准的评论来源不会触发网络请求。 */
class BilibiliActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val tasks = BilibiliTaskGuard()
    private var task: Future<*>? = null
    private val source: BilibiliCommentSource = UnavailableBilibiliSource
    private lateinit var input: EditText
    private lateinit var limit: EditText
    private lateinit var sort: Spinner
    private lateinit var runButton: Button
    private lateinit var cancelButton: Button
    private lateinit var output: LinearLayout
    private val ink = Color.rgb(37, 49, 57)
    private val sub = Color.rgb(111, 124, 131)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "B站视频链接"
        window.decorView.setBackgroundColor(Color.rgb(242, 245, 244))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            padForSystemBars()
        }
        body.addView(text("B站视频链接", 23f, true))
        body.addView(text("粘贴视频分享文字或从 B站分享至本应用。这里只检查视频链接，评论获取目前尚未启用。", 14f))
        input = EditText(this).apply {
            hint = "视频链接、BV / av 编号或分享文字"
            minLines = 2; maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        body.addView(input)
        body.addView(text("期望读取的顶层评论数量（1–200）", 13f))
        limit = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(R.string.bilibili_default_limit) }
        body.addView(limit)
        body.addView(text("期望排序（真实读取前不能确认实际顺序）", 13f))
        sort = Spinner(this).apply {
            adapter = ArrayAdapter(this@BilibiliActivity, android.R.layout.simple_spinner_dropdown_item,
                BilibiliSort.entries.map { it.label })
        }
        body.addView(sort)
        runButton = Button(this).apply {
            text = "检查链接与获取条件"
            setOnClickListener { startCheck() }
        }
        cancelButton = Button(this).apply {
            text = "取消"; isEnabled = false
            setOnClickListener { cancelTask(); showMessage("已取消，不再发起后续请求。") }
        }
        body.addView(runButton); body.addView(cancelButton)
        output = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(output)
        body.addView(Button(this).apply {
            setText(R.string.jev_settings)
            setOnClickListener { startActivity(Intent(this@BilibiliActivity, SettingsActivity::class.java)) }
        })
        body.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(body) })
        if (savedInstanceState == null) {
            readShare(intent)
        } else {
            input.setText(savedInstanceState.getString(STATE_INPUT, ""))
            limit.setText(savedInstanceState.getString(STATE_LIMIT, getString(R.string.bilibili_default_limit)))
            sort.setSelection(savedInstanceState.getInt(STATE_SORT, 0))
        }
        fun changed() {
            cancelTask()
            showMessage("输入已更新，请点击检查链接。\n${UnavailableBilibiliSource.availability.reason}\n实际读取 0 条，尚未分析。")
        }
        input.doAfterTextChanged { changed() }
        limit.doAfterTextChanged { changed() }
        sort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = changed()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        showMessage(UnavailableBilibiliSource.availability.reason + "\n实际读取 0 条，未发送 Jev 请求。")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); readShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_INPUT, input.text.toString())
        outState.putString(STATE_LIMIT, limit.text.toString())
        outState.putInt(STATE_SORT, sort.selectedItemPosition)
        super.onSaveInstanceState(outState)
    }

    private fun readShare(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain")
            input.setText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
    }

    private fun startCheck() {
        cancelTask()
        val count = limit.text.toString().toIntOrNull()
        if (count == null || count !in 1..200) { showMessage("读取数量须为 1–200。实际读取 0 条，尚未分析。"); return }
        when (val link = BilibiliLinks.parse(input.text.toString())) {
            is BilibiliLink.Invalid -> showMessage(link.reason + "\n实际读取 0 条，尚未分析。")
            is BilibiliLink.Short -> {
                showMessage("已识别 B站短链接：${link.url}\n短链尚未解析到具体视频，也未核验视频是否存在。\n" +
                    "期望读取 $count 条顶层评论 · ${selectedSort().label}\n实际排序：未取得\n平台总数：未知\n" +
                    "实际读取 0 条，已判断 0 条，楼中楼未读取。\n${UnavailableBilibiliSource.availability.reason}")
                openLinkButton(link.url)
            }
            is BilibiliLink.Video -> {
                val request = BilibiliRequest(link, count, selectedSort())
                val prefs = Prefs(this)
                val judge: ((List<LiveText>) -> Map<String, LiveDecision>)? = if (prefs.judgeKey.isBlank()) null
                    else JevSentimentClient(prefs.commentJevConfig())::analyze
                val ticket = tasks.ticket()
                fun cancelled() = !tasks.isCurrent(ticket) || Thread.currentThread().isInterrupted
                fun display(value: BilibiliAnalysisResult) = runOnUiThread {
                    if (!isDestroyed) tasks.deliver(ticket) { render(value) }
                }
                runButton.isEnabled = false; cancelButton.isEnabled = true
                showMessage("正在检查链接与评论获取条件…")
                task = worker.submit {
                    val result = BilibiliAnalysis(source, judge).run(request, ::cancelled, ::display)
                    runOnUiThread {
                        if (!isDestroyed) tasks.deliver(ticket) {
                            runButton.isEnabled = true; cancelButton.isEnabled = false
                            render(result)
                        }
                    }
                }
            }
        }
    }

    private fun selectedSort() = BilibiliSort.entries[sort.selectedItemPosition.coerceAtLeast(0)]

    private fun render(result: BilibiliAnalysisResult) {
        output.removeAllViews()
        val load = result.load
        output.addView(text("视频 ${load.request.video.id}", 17f, true))
        output.addView(text("链接格式已识别；本入口尚未核验视频是否存在。", 12f))
        output.addView(text("期望读取：${load.request.limit} 条顶层评论 · ${load.request.sort.label}\n" +
            "实际排序：${load.actualSort?.label ?: "未取得"}\n" +
            "实际读取：${load.comments.size} 条 · 平台总数：${load.reportedTotal?.toString() ?: "未知"}\n" +
            "含文字：${result.textCount} 条 · 已判断：${result.decisions.size} 条\n" +
            "楼中楼：未读取（0 条） · 置顶：未单独读取", 14f))
        val loadLabel = when (load.status) {
            BilibiliLoadStatus.BLOCKED -> "评论获取未启用"
            BilibiliLoadStatus.FAILED -> "评论读取失败"
            BilibiliLoadStatus.PARTIAL -> "只取得部分评论"
            BilibiliLoadStatus.EMPTY -> "来源返回空评论页，且无后续页"
            BilibiliLoadStatus.LIMIT_REACHED -> "已取得所请求数量"
            BilibiliLoadStatus.COMPLETE -> "来源已返回末页"
            BilibiliLoadStatus.CANCELLED -> "已取消"
        }
        output.addView(text(loadLabel, 16f, true))
        if (load.reason.isNotBlank()) output.addView(text(load.reason, 14f))
        if (result.reason.isNotBlank() && result.reason != load.reason) output.addView(text(result.reason, 14f))
        if (result.decisions.isEmpty()) output.addView(text("情绪比例：尚未分析。", 14f))
        else {
            output.addView(text("正面 ${result.percent(Sentiment.POSITIVE)}% · 负面 ${result.percent(Sentiment.NEGATIVE)}%", 18f, true))
            output.addView(text("中性 ${result.count(Sentiment.NEUTRAL)} 条 · 混合 ${result.count(Sentiment.MIXED)} 条 · " +
                "不确定 ${result.count(Sentiment.UNCERTAIN)} 条\n分母为 ${result.decisions.size} 条已判断的顶层评论（按评论 ID），不代表人数或全站观点。", 12f))
        }
        if (result.status == BilibiliAnalysisStatus.RUNNING) output.addView(text("正在判断已读取的文字…", 14f))
        load.comments.forEachIndexed { index, comment ->
            val decision = result.decisions[comment.id]
            output.addView(text("${index + 1}. ${comment.text.ifBlank { "（无可分析文字，已跳过）" }}", 14f))
            output.addView(text(decision?.let { "${it.sentiment.label} · 置信度 ${(it.confidence * 100).roundToInt()}%" }
                ?: if (comment.text.isBlank()) "未判断" else "尚未判断", 12f))
        }
        openLinkButton(load.request.video.url)
    }

    private fun openLinkButton(url: String) = output.addView(Button(this).apply {
        text = "在浏览器打开链接"
        setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { showMessage("当前没有可打开链接的应用。") }
        }
    })

    private fun showMessage(message: String) {
        output.removeAllViews(); output.addView(text(message, 14f))
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(if (bold) ink else sub)
        setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun cancelTask() {
        tasks.invalidate(); task?.cancel(true); task = null
        runButton.isEnabled = true; cancelButton.isEnabled = false
    }

    override fun onDestroy() {
        tasks.invalidate(); task?.cancel(true); worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val STATE_INPUT = "bilibili_input"
        const val STATE_LIMIT = "bilibili_limit"
        const val STATE_SORT = "bilibili_sort"
    }
}
