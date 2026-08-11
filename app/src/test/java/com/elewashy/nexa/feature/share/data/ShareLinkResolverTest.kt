package com.elewashy.nexa.feature.share.data

import com.elewashy.nexa.core.network.HttpClientProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the resolution gates of [ShareLinkResolver].
 *
 * The platform is passed explicitly to [ShareLinkResolver.needsResolution]
 * because platform detection parses URIs with `android.net.Uri`, which is
 * unavailable in plain JVM tests; the runtime path still detects by default.
 * No network access happens — only the gates are exercised.
 */
class ShareLinkResolverTest {

    private val resolver = ShareLinkResolver(HttpClientProvider())

    // ── needsResolution ─────────────────────────────────────────────────

    @Test
    fun `known platform canonical urls skip resolution`() {
        assertFalse(resolver.needsResolution("https://www.youtube.com/watch?v=dQw4w9WgXcQ", SharePlatform.YOUTUBE))
        assertFalse(resolver.needsResolution("https://youtu.be/dQw4w9WgXcQ", SharePlatform.YOUTUBE))
        assertFalse(resolver.needsResolution("https://www.instagram.com/reel/CxYz123/", SharePlatform.INSTAGRAM))
        assertFalse(resolver.needsResolution("https://www.tiktok.com/@user/video/7300000000000000000", SharePlatform.TIKTOK))
        assertFalse(resolver.needsResolution("https://www.facebook.com/watch/?v=123", SharePlatform.FACEBOOK))
        assertFalse(resolver.needsResolution("https://twitter.com/user/status/123", SharePlatform.TWITTER))
    }

    @Test
    fun `threads share links need resolution`() {
        assertTrue(resolver.needsResolution("https://www.threads.net/@user/share/AbCdEf/", SharePlatform.THREADS))
        assertTrue(resolver.needsResolution("https://www.threads.com/@user/share/AbCdEf/", SharePlatform.THREADS))
    }

    @Test
    fun `threads post links do not need resolution`() {
        assertFalse(resolver.needsResolution("https://www.threads.net/@user/post/CxYz123/", SharePlatform.THREADS))
        assertFalse(resolver.needsResolution("https://www.threads.net/@user/post/CxYz123/?foo=bar", SharePlatform.THREADS))
    }

    @Test
    fun `unknown hosts need resolution when they look like short links`() {
        assertTrue(resolver.needsResolution("https://t.co/abc123", SharePlatform.VIDEO))
        assertTrue(resolver.needsResolution("https://bit.ly/xyz", SharePlatform.VIDEO))
    }

    @Test
    fun `unknown hosts that fail the probe gate do not need resolution`() {
        assertFalse(resolver.needsResolution("not a url at all", SharePlatform.VIDEO))
        assertFalse(resolver.needsResolution("https://localhost/share", SharePlatform.VIDEO))
        assertFalse(resolver.needsResolution("https://192.168.1.10/video.mp4", SharePlatform.VIDEO))
        assertFalse(resolver.needsResolution("https://[2001:db8::1]/video.mp4", SharePlatform.VIDEO))
    }

    // ── isProbeCandidate ────────────────────────────────────────────────

    @Test
    fun `probe candidate accepts dotted non-ip hosts`() {
        assertTrue(resolver.isProbeCandidate("https://t.co/abc123"))
        assertTrue(resolver.isProbeCandidate("https://short.link.example.com/a/b"))
        // Contains letters — not an IPv4 literal.
        assertTrue(resolver.isProbeCandidate("https://123.example.com/x"))
    }

    @Test
    fun `probe candidate rejects oversized urls`() {
        val base = "https://t.co/"
        val exact = base + "a".repeat(2048 - base.length)
        assertEquals(2048, exact.length)
        assertTrue(resolver.isProbeCandidate(exact))
        assertFalse(resolver.isProbeCandidate(exact + "a"))
    }

    @Test
    fun `probe candidate rejects dotless hosts`() {
        assertFalse(resolver.isProbeCandidate("https://localhost/x"))
        assertFalse(resolver.isProbeCandidate("https://intranet/media"))
    }

    @Test
    fun `probe candidate rejects ipv4 literals`() {
        assertFalse(resolver.isProbeCandidate("https://192.168.0.1/x"))
        assertFalse(resolver.isProbeCandidate("https://10.0.0.5:8080/video.mp4"))
    }

    @Test
    fun `probe candidate rejects ipv6 literals`() {
        assertFalse(resolver.isProbeCandidate("https://[2001:db8::1]/x"))
        assertFalse(resolver.isProbeCandidate("https://[::1]:8080/x"))
    }

    @Test
    fun `probe candidate rejects unparseable urls`() {
        assertFalse(resolver.isProbeCandidate(""))
        assertFalse(resolver.isProbeCandidate("not a url"))
        assertFalse(resolver.isProbeCandidate("://broken"))
    }
}
