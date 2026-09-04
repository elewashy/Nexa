package com.elewashy.nexa.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Material Symbols Rounded `globe`, opsz 24, weight 400, fill 0, grade 0, roundness 50. */
@Suppress("CheckReturnValue")
val Globe: ImageVector
    get() {
        if (_globe != null) return _globe!!
        _globe = ImageVector.Builder(
            name = "globe",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(12f, 22f)
                quadTo(9.93f, 22f, 8.1f, 21.21f)
                quadTo(6.28f, 20.43f, 4.93f, 19.08f)
                quadTo(3.58f, 17.73f, 2.79f, 15.9f)
                reflectiveQuadTo(2f, 12f)
                quadTo(2f, 9.92f, 2.79f, 8.1f)
                quadTo(3.58f, 6.27f, 4.93f, 4.93f)
                quadTo(6.28f, 3.57f, 8.1f, 2.79f)
                quadTo(9.93f, 2f, 12f, 2f)
                reflectiveQuadToRelative(3.9f, 0.79f)
                reflectiveQuadToRelative(3.17f, 2.14f)
                quadToRelative(1.35f, 1.35f, 2.14f, 3.17f)
                quadTo(22f, 9.92f, 22f, 12f)
                reflectiveQuadToRelative(-0.79f, 3.9f)
                reflectiveQuadToRelative(-2.14f, 3.17f)
                quadToRelative(-1.35f, 1.35f, -3.17f, 2.14f)
                reflectiveQuadTo(12f, 22f)
                close()
                moveToRelative(0f, -2f)
                quadToRelative(3.35f, 0f, 5.68f, -2.32f)
                reflectiveQuadTo(20f, 12f)
                quadToRelative(0f, -0.18f, -0.01f, -0.36f)
                reflectiveQuadTo(19.98f, 11.33f)
                quadToRelative(-0.13f, 0.72f, -0.68f, 1.2f)
                reflectiveQuadTo(18f, 13f)
                horizontalLineTo(16f)
                quadToRelative(-0.82f, 0f, -1.41f, -0.59f)
                reflectiveQuadTo(14f, 11f)
                verticalLineTo(10f)
                horizontalLineTo(10f)
                verticalLineTo(8f)
                quadTo(10f, 7.18f, 10.59f, 6.59f)
                reflectiveQuadTo(12f, 6f)
                horizontalLineToRelative(1f)
                quadTo(13f, 5.43f, 13.31f, 4.99f)
                reflectiveQuadTo(14.08f, 4.27f)
                quadTo(13.58f, 4.15f, 13.06f, 4.07f)
                reflectiveQuadTo(12f, 4f)
                quadTo(8.65f, 4f, 6.33f, 6.32f)
                reflectiveQuadTo(4f, 12f)
                horizontalLineTo(9f)
                quadToRelative(1.65f, 0f, 2.83f, 1.17f)
                reflectiveQuadTo(13f, 16f)
                verticalLineToRelative(1f)
                horizontalLineTo(10f)
                verticalLineToRelative(2.75f)
                quadToRelative(0.5f, 0.13f, 0.99f, 0.19f)
                reflectiveQuadTo(12f, 20f)
                close()
            }
        }.build()
        return _globe!!
    }

private var _globe: ImageVector? = null
