package com.elewashy.nexa.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Speed: ImageVector
  get() {
    if (_speed != null) {
      return _speed!!
    }
    _speed =
      ImageVector.Builder(
          name = "speed",
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
            moveTo(12f, 16.09f)
            quadToRelative(0.95f, -0.01f, 1.4f, -0.69f)
            lineTo(17.63f, 9.07f)
            quadTo(17.85f, 8.73f, 17.56f, 8.44f)
            quadTo(17.28f, 8.15f, 16.93f, 8.38f)
            lineTo(10.6f, 12.6f)
            quadTo(9.93f, 13.05f, 9.89f, 13.98f)
            reflectiveQuadToRelative(0.56f, 1.53f)
            reflectiveQuadTo(12f, 16.09f)
            close()
            moveTo(12f, 4f)
            quadToRelative(0.9f, 0f, 1.78f, 0.15f)
            reflectiveQuadToRelative(1.7f, 0.48f)
            quadToRelative(0.4f, 0.15f, 0.85f, 0.56f)
            quadToRelative(0.45f, 0.41f, 0.25f, 0.79f)
            quadToRelative(-0.2f, 0.38f, -0.9f, 0.5f)
            reflectiveQuadTo(14.55f, 6.45f)
            quadTo(13.93f, 6.22f, 13.29f, 6.11f)
            reflectiveQuadTo(12f, 6f)
            quadTo(8.68f, 6f, 6.34f, 8.34f)
            quadTo(4f, 10.68f, 4f, 14f)
            quadToRelative(0f, 1.05f, 0.29f, 2.07f)
            reflectiveQuadTo(5.1f, 18f)
            horizontalLineTo(18.9f)
            quadToRelative(0.57f, -0.95f, 0.84f, -1.98f)
            reflectiveQuadTo(20f, 13.9f)
            quadToRelative(0f, -0.65f, -0.11f, -1.27f)
            reflectiveQuadTo(19.55f, 11.4f)
            quadTo(19.4f, 10.98f, 19.5f, 10.58f)
            reflectiveQuadTo(19.95f, 9.9f)
            quadTo(20.28f, 9.65f, 20.66f, 9.75f)
            reflectiveQuadTo(21.2f, 10.2f)
            quadToRelative(0.38f, 0.88f, 0.57f, 1.79f)
            reflectiveQuadTo(22f, 13.85f)
            quadToRelative(0.03f, 1.43f, -0.32f, 2.72f)
            reflectiveQuadToRelative(-1.02f, 2.48f)
            quadToRelative(-0.28f, 0.45f, -0.75f, 0.7f)
            reflectiveQuadTo(18.9f, 20f)
            horizontalLineTo(5.1f)
            quadToRelative(-0.53f, 0f, -1f, -0.25f)
            reflectiveQuadTo(3.35f, 19.05f)
            quadTo(2.7f, 17.93f, 2.35f, 16.66f)
            reflectiveQuadTo(2f, 14f)
            quadTo(2f, 11.93f, 2.79f, 10.11f)
            reflectiveQuadTo(4.94f, 6.94f)
            quadTo(6.3f, 5.57f, 8.13f, 4.79f)
            reflectiveQuadTo(12f, 4f)
            close()
            moveToRelative(0.18f, 7.82f)
            close()
          }
        }
        .build()
    return _speed!!
  }

private var _speed: ImageVector? = null
