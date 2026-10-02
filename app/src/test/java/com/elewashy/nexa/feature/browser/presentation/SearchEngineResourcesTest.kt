package com.elewashy.nexa.feature.browser.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchEngineResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `every engine has a distinct icon and label`() {
        assertEquals(SearchEngine.entries.size, SearchEngine.entries.map { it.iconRes }.toSet().size)
        assertEquals(SearchEngine.entries.size, SearchEngine.entries.map { it.labelRes }.toSet().size)
    }

    /** Regression: brand icons were built without a fill, so nothing was drawn. */
    @Test
    fun `every engine icon draws visible pixels`() {
        SearchEngine.entries.forEach { engine ->
            val drawable = ContextCompat.getDrawable(context, engine.iconRes)
            assertNotNull("${engine.name} icon must inflate", drawable)
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            drawable!!.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val opaque = pixels.count { Color.alpha(it) > 0 }
            assertTrue("${engine.name} icon must draw visible pixels", opaque > pixels.size / 20)
        }
    }
}
