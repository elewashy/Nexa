package com.elewashy.nexa.feature.browser.data.resources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.network.HttpClientProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserResourceRepositoryTmpSweepTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `orphaned tmp halves are swept at construction while cache files survive`() {
        val root = File(context.filesDir, "browser_resources").apply { mkdirs() }
        val orphan = File(root, "blocklist.txt.tmp").apply { writeText("partial") }
        val cache = File(root, "blocklist.txt").apply { writeText("content") }

        BrowserResourceRepository(context, HttpClientProvider())

        assertFalse(orphan.exists())
        assertTrue(cache.exists())
    }
}
