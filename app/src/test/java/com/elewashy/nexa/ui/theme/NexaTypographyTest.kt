package com.elewashy.nexa.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class NexaTypographyTest {
    @Test
    fun `every design-system text role resolves direction from content`() {
        val styles: List<TextStyle> = with(NexaTypography) {
            listOf(
                displayLarge, displayMedium, displaySmall,
                headlineLarge, headlineMedium, headlineSmall,
                titleLarge, titleMedium, titleSmall,
                bodyLarge, bodyMedium, bodySmall,
                labelLarge, labelMedium, labelSmall,
            )
        }

        styles.forEach { style ->
            assertEquals(TextDirection.Content, style.textDirection)
        }
    }
}
