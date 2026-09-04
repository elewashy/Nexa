package com.elewashy.nexa.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val PushPin: ImageVector
    get() {
        if (_pushPin != null) return _pushPin!!
        _pushPin = ImageVector.Builder(
            name = "push_pin",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 12f)
                verticalLineTo(4f)
                horizontalLineToRelative(1f)
                verticalLineTo(2f)
                horizontalLineTo(7f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(1f)
                verticalLineToRelative(8f)
                lineToRelative(-2f, 2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-6f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(-2f)
                close()
                moveTo(10f, 4f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(8.83f)
                lineTo(15.17f, 14f)
                horizontalLineTo(8.83f)
                lineTo(10f, 12.83f)
                close()
            }
        }.build()
        return _pushPin!!
    }

private var _pushPin: ImageVector? = null
