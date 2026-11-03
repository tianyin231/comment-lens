package com.jev.probe.bilibili

import org.junit.Assert.*
import org.junit.Test

/** Offline URL fixtures only; no request to these video identifiers is made. */
class BilibiliLinksTest {
    private val bv = "BV1xx411c7mD"
    private fun invalid(input: String) = assertTrue(input, BilibiliLinks.parse(input) is BilibiliLink.Invalid)

    @Test fun canonicalVideoStripsTrackingQueryAndFragment() {
        val parsed = BilibiliLinks.parse("https://www.bilibili.com/video/$bv/?share_source=test#reply")
        assertEquals(BilibiliLink.Video(bv), parsed)
        assertEquals("https://www.bilibili.com/video/$bv", (parsed as BilibiliLink.Video).url)
    }

    @Test fun acceptsExactVideoHostsAndCaseInsensitiveHostAndScheme() {
        listOf("bilibili.com", "www.bilibili.com", "m.bilibili.com", "WWW.BILIBILI.COM").forEach {
            assertEquals(BilibiliLink.Video(bv), BilibiliLinks.parse("HTTPS://$it/video/$bv"))
        }
    }

    @Test fun parsesShareTextAndChineseTrailingPunctuation() {
        assertEquals(BilibiliLink.Video(bv), BilibiliLinks.parse("【合成分享标题】 https://www.bilibili.com/video/$bv？）"))
        assertEquals(BilibiliLink.Video(bv), BilibiliLinks.parse("合成分享 https://www.bilibili.com/video/$bv。"))
    }

    @Test fun repeatedLinksToSameVideoAreUnambiguous() {
        val input = "https://www.bilibili.com/video/$bv?share=a\nhttps://m.bilibili.com/video/$bv?share=b"
        assertEquals(BilibiliLink.Video(bv), BilibiliLinks.parse(input))
    }

    @Test fun differentTargetsAreRejectedRatherThanSelectingFirst() {
        invalid("https://www.bilibili.com/video/$bv https://www.bilibili.com/video/av123")
        invalid("https://www.bilibili.com/video/$bv https://b23.tv/Synthetic1")
        invalid("https://b23.tv/Synthetic1 https://b23.tv/Synthetic2")
    }

    @Test fun shortLinkIsOnlyRecognizedAndCanonicalized() {
        assertEquals(BilibiliLink.Short("https://b23.tv/Synthetic1"),
            BilibiliLinks.parse("合成分享 https://b23.tv/Synthetic1/?tracking=test#ignored"))
        invalid("https://b23.tv/Synthetic1/extra")
    }

    @Test fun acceptsBareIdentifiersAndRejectsOverflowingNumericVideoIds() {
        assertEquals(BilibiliLink.Video(bv), BilibiliLinks.parse(bv))
        assertEquals(BilibiliLink.Video("av123"), BilibiliLinks.parse("av123"))
        invalid("av0")
        invalid("av9223372036854775808")
        invalid("https://www.bilibili.com/video/av9223372036854775808")
    }

    @Test fun lookalikeOrArbitraryHostsCannotBecomeVideoLinks() {
        listOf("bilibili.com.evil.example", "evil-bilibili.com", "b23.tv.evil.example", "127.0.0.1",
            "bilibili.com.", "www.bilibili.com%2eevil.example").forEach {
            invalid("https://$it/video/$bv")
        }
        invalid("https://evil.example/?url=https://www.bilibili.com/video/$bv")
    }

    @Test fun insecureUrlsCredentialsAndPortsAreRejected() {
        invalid("http://www.bilibili.com/video/$bv")
        invalid("https://user@www.bilibili.com/video/$bv")
        invalid("https://user:synthetic-password@www.bilibili.com/video/$bv")
        invalid("https://www.bilibili.com:443/video/$bv")
        invalid("https://b23.tv:8443/Synthetic1")
    }

    @Test fun encodedOrConfusedPathsAndNonVideoTargetsAreRejected() {
        listOf("/video/%42V1xx411c7mD", "/video/$bv/../av123", "/video/$bv/extra", "/video/$bv%2fextra",
            "/read/cv123", "/opus/123", "/bangumi/play/ep123", "/video/BV0000000000").forEach {
            invalid("https://www.bilibili.com$it")
        }
        invalid("https://www.bilibili.com\\@evil.example/video/$bv")
    }

    @Test fun invalidUrlMixedWithSupportedUrlDoesNotHideTheInvalidTarget() {
        invalid("https://evil.example/video/test https://www.bilibili.com/video/$bv")
    }

    @Test fun missingAndOversizedInputFailWithoutReturningTarget() {
        invalid("")
        invalid("没有链接的分享标题")
        invalid("x".repeat(8001) + " https://www.bilibili.com/video/$bv")
    }
}
