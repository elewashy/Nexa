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
val BookmarkBorder: ImageVector
  get() {
    if (_bookmark_border != null) {
      return _bookmark_border!!
    }
    _bookmark_border =
      ImageVector.Builder(
          name = "bookmark_border",
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
            moveTo(12f, 18f)
            lineTo(7.8f, 19.8f)
            quadToRelative(-1f, 0.43f, -1.9f, -0.16f)
            reflectiveQuadTo(5f, 17.98f)
            verticalLineTo(5f)
            quadTo(5f, 4.17f, 5.59f, 3.59f)
            reflectiveQuadTo(7f, 3f)
            horizontalLineTo(17f)
            quadToRelative(0.82f, 0f, 1.41f, 0.59f)
            reflectiveQuadTo(19f, 5f)
            verticalLineTo(17.98f)
            quadToRelative(0f, 1.07f, -0.9f, 1.66f)
            quadToRelative(-0.9f, 0.59f, -1.9f, 0.16f)
            lineTo(12f, 18f)
            close()
            moveToRelative(0f, -2.2f)
            lineToRelative(5f, 2.15f)
            verticalLineTo(5f)
            horizontalLineTo(7f)
            verticalLineTo(17.95f)
            lineTo(12f, 15.8f)
            close()
            moveTo(12f, 5f)
            horizontalLineTo(7f)
            horizontalLineTo(17f)
            horizontalLineTo(12f)
            close()
          }
        }
        .build()
    return _bookmark_border!!
  }

private var _bookmark_border: ImageVector? = null
