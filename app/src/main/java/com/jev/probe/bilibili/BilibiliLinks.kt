package com.jev.probe.bilibili

import java.net.URI

sealed interface BilibiliLink {
    data class Video(val id: String) : BilibiliLink {
        val url get() = "https://www.bilibili.com/video/$id"
    }
    data class Short(val url: String) : BilibiliLink
    data class Invalid(val reason: String) : BilibiliLink
}

/** 只解析链接，不访问分享页面，不携带或保存链接中的跟踪参数。 */
object BilibiliLinks {
    private val urls = Regex("https?://[^\\s<>\"'，。；！？”】）]+", RegexOption.IGNORE_CASE)
    private val videoPath = Regex("/video/(BV[1-9A-HJ-NP-Za-km-z]{10}|av[1-9][0-9]{0,18})/?")
    private val videoId = Regex("BV[1-9A-HJ-NP-Za-km-z]{10}|av[1-9][0-9]{0,18}")
    private val shortPath = Regex("/[A-Za-z0-9]{1,32}/?")
    private val videoHosts = setOf("bilibili.com", "www.bilibili.com", "m.bilibili.com")

    fun parse(input: String): BilibiliLink {
        if (input.isBlank()) return BilibiliLink.Invalid("请粘贴一个 B站视频分享链接。")
        if (input.length > 8000) return BilibiliLink.Invalid("分享文字过长，请只粘贴视频链接。")
        val clean = input.trim()
        if (videoId.matches(clean)) return video(clean)
        val matches = urls.findAll(clean).map { it.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}') }.toList()
        if (matches.isEmpty()) return BilibiliLink.Invalid("未找到 HTTPS 视频链接，请复制 B站视频的分享链接。")
        val parsed = matches.map(::parseUrl)
        parsed.filterIsInstance<BilibiliLink.Invalid>().firstOrNull()?.let { return it }
        val unique = parsed.distinct()
        return unique.singleOrNull() ?: BilibiliLink.Invalid("发现多个不同链接，请一次只分析一个视频。")
    }

    private fun parseUrl(value: String): BilibiliLink {
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return BilibiliLink.Invalid("链接格式无效，请重新复制。")
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port != -1 || value.contains('\\'))
            return BilibiliLink.Invalid("仅接受没有账号信息或自定义端口的 HTTPS 视频链接。")
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.rawPath.orEmpty()
        if (host in videoHosts) {
            val id = videoPath.matchEntire(path)?.groupValues?.get(1)
                ?: return BilibiliLink.Invalid("这不是视频链接；本入口暂不处理动态、专栏、直播或合集。")
            return video(id)
        }
        if (host == "b23.tv" && shortPath.matches(path))
            return BilibiliLink.Short("https://b23.tv${path.trimEnd('/')}")
        return BilibiliLink.Invalid("链接域名或路径不受支持，请复制 B站视频分享链接。")
    }

    private fun video(id: String): BilibiliLink {
        if (id.startsWith("av") && id.substring(2).toLongOrNull() == null)
            return BilibiliLink.Invalid("视频编号超出有效范围。")
        return BilibiliLink.Video(id)
    }
}
