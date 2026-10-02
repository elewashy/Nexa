package com.elewashy.nexa.ui.components.common

import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.elewashy.nexa.R

/**
 * Shows the app launcher icon inside the same Material 3 expressive polygon
 * frame used for the developer's profile picture.
 *
 * Uses [AndroidView] with an [ImageView] because `painterResource()` cannot
 * decode adaptive launcher icons (XML), matching the rest of the app.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FramedAppIcon(
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialShapes.Cookie9Sided.toShape(),
) {
    Surface(
        modifier = modifier.size(size),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 4.dp,
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    setImageResource(R.mipmap.ic_launcher)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
