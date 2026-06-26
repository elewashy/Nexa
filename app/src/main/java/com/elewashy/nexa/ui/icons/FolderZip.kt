package com.elewashy.nexa.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val FolderZip: ImageVector
  get() {
    if (_folder_zip != null) {
      return _folder_zip!!
    }
    _folder_zip =
      ImageVector.Builder(
          name = "folder_zip",
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
            moveTo(16f, 12f)
            verticalLineTo(10f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineTo(16f)
            close()
            moveToRelative(0f, 2f)
            horizontalLineTo(14f)
            verticalLineTo(12f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            close()
            moveToRelative(0f, 2f)
            verticalLineTo(14f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineTo(16f)
            close()
            moveTo(11.18f, 8f)
            lineToRelative(-2f, -2f)
            horizontalLineTo(4f)
            verticalLineTo(18f)
            horizontalLineTo(14f)
            verticalLineTo(16f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(4f)
            verticalLineTo(8f)
            horizontalLineTo(16f)
            verticalLineToRelative(2f)
            horizontalLineTo(14f)
            verticalLineTo(8f)
            horizontalLineTo(11.18f)
            close()
            moveTo(4f, 20f)
            quadTo(3.18f, 20f, 2.59f, 19.41f)
            reflectiveQuadTo(2f, 18f)
            verticalLineTo(6f)
            quadTo(2f, 5.18f, 2.59f, 4.59f)
            reflectiveQuadTo(4f, 4f)
            horizontalLineTo(9.18f)
            quadToRelative(0.4f, 0f, 0.76f, 0.15f)
            reflectiveQuadToRelative(0.64f, 0.43f)
            lineTo(12f, 6f)
            horizontalLineToRelative(8f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(22f, 7.18f, 22f, 8f)
            verticalLineTo(18f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(20f, 20f)
            horizontalLineTo(4f)
            close()
            moveTo(4f, 18f)
            verticalLineTo(8f)
            verticalLineTo(6f)
            verticalLineTo(18f)
            close()
          }
        }
        .build()
    return _folder_zip!!
  }

private var _folder_zip: ImageVector? = null
