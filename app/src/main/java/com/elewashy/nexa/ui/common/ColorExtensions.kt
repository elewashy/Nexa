package com.elewashy.nexa.ui.common

import androidx.compose.ui.graphics.Color

/**
 * Calculate the luminance of a color to determine if it's dark or light
 * 
 * @return Luminance value between 0 (black) and 1 (white)
 */
fun Color.luminance(): Float {
    return 0.299f * red + 0.587f * green + 0.114f * blue
}

