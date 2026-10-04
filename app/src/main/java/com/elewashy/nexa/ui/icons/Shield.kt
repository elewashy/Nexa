package com.elewashy.nexa.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
val Shield: ImageVector
  get() {
    if (_shield != null) {
      return _shield!!
    }
    _shield =
      ImageVector.Builder(
          name = "shield",
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
            moveTo(11.68f, 21.88f)
            quadTo(11.53f, 21.85f, 11.38f, 21.8f)
            quadTo(8f, 20.68f, 6f, 17.64f)
            reflectiveQuadTo(4f, 11.1f)
            verticalLineTo(6.38f)
            quadTo(4f, 5.75f, 4.36f, 5.25f)
            quadTo(4.73f, 4.75f, 5.3f, 4.52f)
            lineToRelative(6f, -2.25f)
            quadTo(11.65f, 2.15f, 12f, 2.15f)
            reflectiveQuadToRelative(0.7f, 0.13f)
            lineToRelative(6f, 2.25f)
            quadToRelative(0.58f, 0.23f, 0.94f, 0.73f)
            reflectiveQuadTo(20f, 6.38f)
            verticalLineTo(11.1f)
            quadToRelative(0f, 3.5f, -2f, 6.54f)
            quadToRelative(-2f, 3.04f, -5.38f, 4.16f)
            quadToRelative(-0.15f, 0.05f, -0.3f, 0.07f)
            reflectiveQuadTo(12f, 21.9f)
            reflectiveQuadTo(11.68f, 21.88f)
            close()
            moveTo(12f, 19.9f)
            quadToRelative(2.6f, -0.82f, 4.3f, -3.3f)
            reflectiveQuadTo(18f, 11.1f)
            verticalLineTo(6.38f)
            lineTo(12f, 4.13f)
            lineTo(6f, 6.38f)
            verticalLineTo(11.1f)
            quadToRelative(0f, 3.03f, 1.7f, 5.5f)
            reflectiveQuadTo(12f, 19.9f)
            close()
            moveTo(12f, 12f)
            close()
          }
        }
        .build()
    return _shield!!
  }

private var _shield: ImageVector? = null

@Suppress("CheckReturnValue")
val ShieldFilled: ImageVector
  get() {
    if (_shield_filled != null) {
      return _shield_filled!!
    }
    _shield_filled =
      ImageVector.Builder(
          name = "shield",
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
            moveTo(11.68f, 21.88f)
            quadTo(11.53f, 21.85f, 11.38f, 21.8f)
            quadTo(8f, 20.68f, 6f, 17.64f)
            reflectiveQuadTo(4f, 11.1f)
            verticalLineTo(6.38f)
            quadTo(4f, 5.75f, 4.36f, 5.25f)
            quadTo(4.73f, 4.75f, 5.3f, 4.52f)
            lineToRelative(6f, -2.25f)
            quadTo(11.65f, 2.15f, 12f, 2.15f)
            reflectiveQuadToRelative(0.7f, 0.13f)
            lineToRelative(6f, 2.25f)
            quadToRelative(0.58f, 0.23f, 0.94f, 0.73f)
            reflectiveQuadTo(20f, 6.38f)
            verticalLineTo(11.1f)
            quadToRelative(0f, 3.5f, -2f, 6.54f)
            quadToRelative(-2f, 3.04f, -5.38f, 4.16f)
            quadToRelative(-0.15f, 0.05f, -0.3f, 0.07f)
            reflectiveQuadTo(12f, 21.9f)
            reflectiveQuadTo(11.68f, 21.88f)
            close()
          }
        }
        .build()
    return _shield_filled!!
  }

private var _shield_filled: ImageVector? = null
