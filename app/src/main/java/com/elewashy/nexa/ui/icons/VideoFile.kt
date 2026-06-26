package com.elewashy.nexa.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val VideoFile: ImageVector
  get() {
    if (_video_file != null) {
      return _video_file!!
    }
    _video_file =
      ImageVector.Builder(
          name = "video_file",
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
            moveTo(9f, 18f)
            horizontalLineToRelative(4f)
            quadToRelative(0.43f, 0f, 0.71f, -0.29f)
            quadTo(14f, 17.43f, 14f, 17f)
            verticalLineTo(16f)
            lineToRelative(1.28f, 0.68f)
            quadToRelative(0.25f, 0.13f, 0.49f, -0.03f)
            reflectiveQuadTo(16f, 16.23f)
            verticalLineTo(13.77f)
            quadTo(16f, 13.5f, 15.76f, 13.35f)
            reflectiveQuadTo(15.28f, 13.33f)
            lineTo(14f, 14f)
            verticalLineTo(13f)
            quadToRelative(0f, -0.43f, -0.29f, -0.71f)
            reflectiveQuadTo(13f, 12f)
            horizontalLineTo(9f)
            quadTo(8.58f, 12f, 8.29f, 12.29f)
            reflectiveQuadTo(8f, 13f)
            verticalLineToRelative(4f)
            quadToRelative(0f, 0.43f, 0.29f, 0.71f)
            quadTo(8.58f, 18f, 9f, 18f)
            close()
            moveTo(6f, 22f)
            quadTo(5.18f, 22f, 4.59f, 21.41f)
            reflectiveQuadTo(4f, 20f)
            verticalLineTo(4f)
            quadTo(4f, 3.17f, 4.59f, 2.59f)
            reflectiveQuadTo(6f, 2f)
            horizontalLineToRelative(7.18f)
            quadToRelative(0.4f, 0f, 0.76f, 0.15f)
            reflectiveQuadToRelative(0.64f, 0.43f)
            lineToRelative(4.85f, 4.85f)
            quadTo(19.7f, 7.7f, 19.85f, 8.06f)
            quadTo(20f, 8.42f, 20f, 8.82f)
            verticalLineTo(20f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(18f, 22f)
            horizontalLineTo(6f)
            close()
            moveTo(13f, 8f)
            verticalLineTo(4f)
            horizontalLineTo(6f)
            verticalLineTo(20f)
            horizontalLineTo(18f)
            verticalLineTo(9f)
            horizontalLineTo(14f)
            quadTo(13.58f, 9f, 13.29f, 8.71f)
            reflectiveQuadTo(13f, 8f)
            close()
            moveTo(6f, 4f)
            verticalLineTo(8f)
            quadTo(6f, 8.42f, 6f, 8.71f)
            reflectiveQuadTo(6f, 9f)
            verticalLineTo(4f)
            verticalLineTo(8f)
            quadTo(6f, 8.42f, 6f, 8.71f)
            reflectiveQuadTo(6f, 9f)
            verticalLineTo(20f)
            verticalLineTo(4f)
            close()
          }
        }
        .build()
    return _video_file!!
  }

private var _video_file: ImageVector? = null
