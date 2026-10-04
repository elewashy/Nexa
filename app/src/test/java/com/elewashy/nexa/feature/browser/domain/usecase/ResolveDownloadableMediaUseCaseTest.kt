package com.elewashy.nexa.feature.browser.domain.usecase

import com.elewashy.nexa.feature.browser.domain.model.PageMediaProbeResult
import com.elewashy.nexa.feature.share.domain.MediaPageClassifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveDownloadableMediaUseCaseTest {

    private val resolve = ResolveDownloadableMediaUseCase()

    private fun resolve(url: String?, probe: PageMediaProbeResult? = null, report: Boolean? = null) =
        resolve(MediaPageClassifier.classify(url), probe, report)

    @Test
    fun `non media pages never qualify, whatever the signals say`() {
        val positive = PageMediaProbeResult.Reported("twitter:1", hasMedia = true)
        assertFalse(resolve("https://x.com/home", positive, report = true))
        assertFalse(resolve("https://x.com/", PageMediaProbeResult.Unavailable))
        assertFalse(resolve("https://www.youtube.com/"))
        assertFalse(resolve("https://example.com/"))
        assertFalse(resolve(""))
        assertFalse(resolve(null))
    }

    @Test
    fun `guaranteed media pages qualify from the url alone`() {
        assertTrue(resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(resolve("https://www.instagram.com/p/CxYz123/"))
        assertTrue(resolve("https://www.tiktok.com/@u/photo/7300000000000000001"))
        assertTrue(resolve("https://www.facebook.com/reel/123", PageMediaProbeResult.Reported(null, false), report = false))
    }

    @Test
    fun `the platform's report decides when there is one`() {
        val url = "https://x.com/_GauravGosain/status/2106095256093766131"
        val key = "twitter:2106095256093766131"

        assertTrue(resolve(url, probe = null, report = true))
        assertTrue("report wins over a negative probe", resolve(url, PageMediaProbeResult.Reported(key, false), report = true))
        assertFalse("report wins over a positive probe", resolve(url, PageMediaProbeResult.Reported(key, true), report = false))
        assertFalse(resolve(url, PageMediaProbeResult.Unavailable, report = false))
    }

    @Test
    fun `without a report, tweets qualify only when the probe confirmed media for that tweet`() {
        val url = "https://x.com/_GauravGosain/status/2106095256093766131"
        val key = "twitter:2106095256093766131"

        assertFalse("no signal yet", resolve(url))
        assertFalse("text-only tweet", resolve(url, PageMediaProbeResult.Reported(key, hasMedia = false)))
        assertFalse("stale report about another tweet", resolve(url, PageMediaProbeResult.Reported("twitter:1", true)))
        assertTrue(resolve(url, PageMediaProbeResult.Reported(key, hasMedia = true)))
        assertTrue("same tweet on its photo page", resolve("$url/photo/1", PageMediaProbeResult.Reported(key, true)))
    }

    @Test
    fun `page-checked urls fall back to the url when neither signal is available`() {
        assertTrue(resolve("https://www.threads.net/@u/post/Abc", PageMediaProbeResult.Unavailable))
    }
}
