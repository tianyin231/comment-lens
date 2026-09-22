package com.jev.probe.comments

import java.text.Normalizer

object CommentText {
    private val chrome = setOf("评论", "评论区", "全部评论", "热门评论", "最新评论", "最热", "最新",
        "点赞", "分享", "收藏", "转发", "关注", "已关注", "发送", "展开", "收起", "回复", "写评论", "发一条友善的评论")
    private val metadata = Regex("^(?:IP属地[:：]?.*|\\d+\\s*(?:秒|分钟|小时|天)前|\\d{1,2}:\\d{2}(?::\\d{2})?|展开\\s*\\d+\\s*条回复|共\\s*\\d+\\s*条回复)$")

    /** 只清理明确的界面标签，保留“666”等有意义的短弹幕；昵称等由采集预览核对。 */
    fun cleanBlock(lines: List<String>): String = lines.map { it.trim() }
        .filter { it.isNotBlank() && it !in chrome && !metadata.matches(it) }
        .joinToString("\n")

    fun splitSamples(text: String): List<String> = text.replace("\r\n", "\n")
        .split(Regex("\n[ \\t]*\n+"))
        .map { it.trim() }.filter { it.isNotBlank() }

    // OCR 没有可靠的评论 ID，合并同文仅用于文本样本统计，不能用来推算人数或刷屏次数。
    fun fingerprint(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .trim().replace(Regex("\\s+"), " ")
}
