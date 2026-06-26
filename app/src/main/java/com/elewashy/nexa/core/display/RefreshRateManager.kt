package com.elewashy.nexa.core.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Window
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class RefreshRateManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun isHighRefreshRateSupported(): Boolean = supportedModes().any { it.refreshRate > HIGH_REFRESH_THRESHOLD }

    fun apply(window: Window, enabled: Boolean) {
        if (!isHighRefreshRateSupported()) return

        val attributes = window.attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attributes.preferredDisplayModeId = if (enabled) {
                DisplayModeDefault
            } else {
                preferredSixtyHertzModeId() ?: DisplayModeDefault
            }
        } else {
            @Suppress("DEPRECATION")
            attributes.preferredRefreshRate = if (enabled) RefreshRateDefault else TARGET_REFRESH_RATE
        }
        window.attributes = attributes
    }

    private fun preferredSixtyHertzModeId(): Int? = supportedModes()
        .minByOrNull { abs(it.refreshRate - TARGET_REFRESH_RATE) }
        ?.modeId

    private fun supportedModes(): Array<Display.Mode> {
        val displayManager = context.getSystemService(DisplayManager::class.java) ?: return emptyArray()
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyArray()
        return display.supportedModes ?: emptyArray()
    }

    private companion object {
        const val TARGET_REFRESH_RATE = 60f
        const val HIGH_REFRESH_THRESHOLD = 61f
        const val RefreshRateDefault = 0f
        const val DisplayModeDefault = 0
    }
}
