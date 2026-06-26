package com.elewashy.nexa.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val AutoMode: ImageVector
  get() {
    if (_auto_mode != null) {
      return _auto_mode!!
    }
    _auto_mode =
      ImageVector.Builder(
          name = "auto_mode",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 24f,
          viewportHeight = 24f,
        )
        .apply {
          path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.Companion.NonZero,
          ) {
            moveTo(10.45f, 13.55f)
            lineTo(8f, 12.45f)
            quadTo(7.7f, 12.33f, 7.7f, 12f)
            reflectiveQuadTo(8f, 11.55f)
            lineToRelative(2.45f, -1.13f)
            lineTo(11.55f, 8f)
            quadTo(11.68f, 7.7f, 12f, 7.7f)
            reflectiveQuadTo(12.45f, 8f)
            lineToRelative(1.13f, 2.42f)
            lineTo(16f, 11.55f)
            quadToRelative(0.3f, 0.13f, 0.3f, 0.45f)
            reflectiveQuadTo(16f, 12.45f)
            lineToRelative(-2.42f, 1.1f)
            lineTo(12.45f, 16f)
            quadTo(12.33f, 16.3f, 12f, 16.3f)
            reflectiveQuadTo(11.55f, 16f)
            lineToRelative(-1.1f, -2.45f)
            close()
            moveTo(3f, 18.3f)
            verticalLineTo(20f)
            quadToRelative(0f, 0.43f, -0.29f, 0.71f)
            reflectiveQuadTo(2f, 21f)
            quadTo(1.58f, 21f, 1.29f, 20.71f)
            quadTo(1f, 20.43f, 1f, 20f)
            verticalLineTo(16f)
            quadTo(1f, 15.58f, 1.29f, 15.29f)
            reflectiveQuadTo(2f, 15f)
            horizontalLineTo(6f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(7f, 16f)
            reflectiveQuadTo(6.71f, 16.71f)
            reflectiveQuadTo(6f, 17f)
            horizontalLineTo(4.55f)
            quadToRelative(1.28f, 1.88f, 3.24f, 2.94f)
            reflectiveQuadTo(12f, 21f)
            quadToRelative(2.6f, 0f, 4.75f, -1.35f)
            reflectiveQuadToRelative(3.3f, -3.63f)
            quadToRelative(0.23f, -0.42f, 0.59f, -0.67f)
            reflectiveQuadTo(21.48f, 15.2f)
            quadToRelative(0.45f, 0.1f, 0.59f, 0.51f)
            reflectiveQuadTo(21.98f, 16.6f)
            quadToRelative(-1.35f, 2.9f, -4.03f, 4.65f)
            quadTo(15.28f, 23f, 12f, 23f)
            quadTo(9.3f, 23f, 6.94f, 21.76f)
            quadTo(4.58f, 20.53f, 3f, 18.3f)
            close()
            moveTo(2.08f, 11f)
            quadTo(1.65f, 11f, 1.39f, 10.69f)
            reflectiveQuadTo(1.2f, 9.95f)
            quadTo(1.45f, 8.77f, 1.85f, 7.79f)
            reflectiveQuadTo(2.93f, 5.8f)
            quadTo(3.18f, 5.43f, 3.58f, 5.38f)
            reflectiveQuadTo(4.3f, 5.65f)
            quadTo(4.65f, 6f, 4.65f, 6.41f)
            reflectiveQuadTo(4.38f, 7.25f)
            quadTo(3.95f, 7.9f, 3.7f, 8.55f)
            reflectiveQuadTo(3.25f, 9.98f)
            quadToRelative(-0.1f, 0.45f, -0.41f, 0.74f)
            reflectiveQuadTo(2.08f, 11f)
            close()
            moveTo(11f, 2.05f)
            quadToRelative(0f, 0.48f, -0.29f, 0.78f)
            reflectiveQuadTo(9.95f, 3.22f)
            quadTo(9.2f, 3.4f, 8.56f, 3.67f)
            quadTo(7.93f, 3.95f, 7.28f, 4.38f)
            quadTo(6.88f, 4.65f, 6.46f, 4.63f)
            reflectiveQuadTo(5.7f, 4.25f)
            quadTo(5.4f, 3.95f, 5.44f, 3.56f)
            quadTo(5.48f, 3.17f, 5.83f, 2.9f)
            quadTo(6.8f, 2.25f, 7.76f, 1.84f)
            reflectiveQuadTo(9.9f, 1.2f)
            quadToRelative(0.45f, -0.07f, 0.78f, 0.18f)
            reflectiveQuadTo(11f, 2.05f)
            close()
            moveToRelative(7.35f, 2.2f)
            quadTo(18f, 4.6f, 17.58f, 4.61f)
            quadTo(17.15f, 4.63f, 16.75f, 4.35f)
            quadTo(16.1f, 3.92f, 15.45f, 3.67f)
            reflectiveQuadTo(14.03f, 3.22f)
            quadTo(13.58f, 3.13f, 13.29f, 2.81f)
            reflectiveQuadTo(13f, 2.05f)
            quadTo(13f, 1.63f, 13.31f, 1.38f)
            reflectiveQuadTo(14.05f, 1.2f)
            quadToRelative(1.2f, 0.22f, 2.18f, 0.63f)
            quadTo(17.2f, 2.22f, 18.2f, 2.9f)
            quadToRelative(0.35f, 0.25f, 0.4f, 0.65f)
            reflectiveQuadToRelative(-0.25f, 0.7f)
            close()
            moveTo(21.95f, 11f)
            quadToRelative(-0.48f, 0f, -0.78f, -0.29f)
            quadToRelative(-0.3f, -0.29f, -0.4f, -0.76f)
            quadTo(20.58f, 9.17f, 20.31f, 8.54f)
            reflectiveQuadTo(19.63f, 7.22f)
            quadTo(19.35f, 6.82f, 19.38f, 6.41f)
            reflectiveQuadTo(19.75f, 5.65f)
            quadToRelative(0.3f, -0.3f, 0.69f, -0.25f)
            reflectiveQuadTo(21.1f, 5.8f)
            quadToRelative(0.68f, 1f, 1.07f, 1.97f)
            reflectiveQuadTo(22.8f, 9.95f)
            quadToRelative(0.07f, 0.43f, -0.18f, 0.74f)
            reflectiveQuadTo(21.95f, 11f)
            close()
          }
        }
        .build()
    return _auto_mode!!
  }

private var _auto_mode: ImageVector? = null
