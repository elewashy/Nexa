package com.elewashy.nexa.feature.browser.data.favicon

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FaviconRepositoryTest {
    @Test
    fun `fallback is same-origin and strips URL credentials`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.cacheDir.resolve("favicons").deleteRecursively()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FaviconRepository(context, this, dispatcher)

        val source = repository.observe("https://user:secret@example.com:8443/page")
        advanceUntilIdle()

        assertEquals(
            "https://example.com:8443/favicon.ico",
            (source.value as FaviconSource.Remote).url,
        )
        assertFalse((source.value as FaviconSource.Remote).url.contains("secret"))
    }

    @Test
    fun `captured WebView icon replaces fallback with bounded local cache`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.cacheDir.resolve("favicons").deleteRecursively()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FaviconRepository(context, this, dispatcher)
        val url = "https://icons.example/path"
        val source = repository.observe(url)
        advanceUntilIdle()

        repository.store(url, Bitmap.createBitmap(256, 64, Bitmap.Config.ARGB_8888))
        advanceUntilIdle()

        val local = source.value as FaviconSource.Local
        assertTrue(local.file.isFile)
        assertTrue(local.file.length() > 0)
    }
}
