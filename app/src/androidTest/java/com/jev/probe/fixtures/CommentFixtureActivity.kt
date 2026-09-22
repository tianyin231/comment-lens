package com.jev.probe.fixtures

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 仅进入测试 APK，提供不依赖浏览器、网络或平台账号的 OCR 画面。 */
class CommentFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(this).apply {
            text = "合成评论测试 · 不是真实平台数据"
            textSize = 18f; setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(28))
        })
        // 测试 APK 可单独启动，数组循环不依赖宿主 APK 的 Kotlin 集合运行库。
        arrayOf(
            "画面比上个版本清晰，光影也很好看。",
            "更新之后掉帧严重，希望尽快修复。",
            "主播讲得挺好，但这游戏的价格太高。",
            "这段介绍没听懂，能再解释一下吗？",
            "666"
        ).forEach { comment ->
            content.addView(TextView(this).apply {
                text = comment; textSize = 22f; setTextColor(Color.BLACK)
                setPadding(0, dp(16), 0, dp(16))
            })
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }
}
