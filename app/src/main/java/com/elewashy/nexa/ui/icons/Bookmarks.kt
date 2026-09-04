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
val Bookmarks: ImageVector
  get() {
    if (_bookmarks != null) {
      return _bookmarks!!
    }
    _bookmarks =
      ImageVector.Builder(
          name = "bookmarks",
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
            moveTo(10f, 19f)
            lineTo(5.45f, 21.28f)
            quadTo(4.95f, 21.53f, 4.48f, 21.24f)
            reflectiveQuadTo(4f, 20.38f)
            verticalLineTo(8f)
            quadTo(4f, 7.18f, 4.59f, 6.59f)
            reflectiveQuadTo(6f, 6f)
            horizontalLineToRelative(8f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(16f, 7.18f, 16f, 8f)
            verticalLineTo(20.38f)
            quadToRelative(0f, 0.57f, -0.47f, 0.86f)
            reflectiveQuadToRelative(-0.97f, 0.04f)
            lineTo(10f, 19f)
            close()
            moveTo(6f, 18.98f)
            lineTo(9.05f, 17.33f)
            quadTo(9.5f, 17.08f, 10f, 17.08f)
            reflectiveQuadToRelative(0.95f, 0.25f)
            lineTo(14f, 18.98f)
            verticalLineTo(8f)
            horizontalLineTo(6f)
            verticalLineTo(18.98f)
            close()
            moveTo(18.29f, 17.71f)
            quadTo(18f, 17.43f, 18f, 17f)
            verticalLineTo(4f)
            horizontalLineTo(8f)
            quadTo(7.58f, 4f, 7.29f, 3.71f)
            reflectiveQuadTo(7f, 3f)
            quadTo(7f, 2.57f, 7.29f, 2.29f)
            reflectiveQuadTo(8f, 2f)
            horizontalLineTo(18f)
            quadToRelative(0.82f, 0f, 1.41f, 0.59f)
            reflectiveQuadTo(20f, 4f)
            verticalLineTo(17f)
            quadToRelative(0f, 0.43f, -0.29f, 0.71f)
            reflectiveQuadTo(19f, 18f)
            reflectiveQuadTo(18.29f, 17.71f)
            close()
            moveTo(6f, 8f)
            horizontalLineToRelative(8f)
            horizontalLineTo(10.95f)
            quadTo(10.5f, 8f, 10f, 8f)
            reflectiveQuadTo(9.05f, 8f)
            horizontalLineTo(6f)
            close()
          }
        }
        .build()
    return _bookmarks!!
  }

private var _bookmarks: ImageVector? = null
