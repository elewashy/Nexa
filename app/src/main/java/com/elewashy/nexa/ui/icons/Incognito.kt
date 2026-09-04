package com.elewashy.nexa.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Incognito: ImageVector by lazy {
    ImageVector.Builder(
        name = "Incognito",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7.2f, 4f)
            lineTo(5.6f, 9f)
            horizontalLineTo(18.4f)
            lineTo(16.8f, 4f)
            lineTo(13.7f, 5.5f)
            lineTo(10.3f, 5.5f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(3f, 12f)
            horizontalLineTo(21f)
            moveTo(10f, 16f)
            curveTo(10f, 18.2f, 8.4f, 20f, 6.4f, 20f)
            reflectiveCurveTo(3f, 18.2f, 3f, 16f)
            reflectiveCurveTo(4.5f, 13f, 6.4f, 13f)
            reflectiveCurveTo(10f, 13.8f, 10f, 16f)
            moveTo(14f, 16f)
            curveTo(14f, 13.8f, 15.6f, 13f, 17.6f, 13f)
            reflectiveCurveTo(21f, 13.8f, 21f, 16f)
            reflectiveCurveTo(19.5f, 20f, 17.6f, 20f)
            reflectiveCurveTo(14f, 18.2f, 14f, 16f)
            moveTo(10f, 15f)
            curveTo(11f, 14.5f, 13f, 14.5f, 14f, 15f)
        }
    }.build()
}
