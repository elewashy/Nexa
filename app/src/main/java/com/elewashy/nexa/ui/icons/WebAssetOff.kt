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
val WebAssetOff: ImageVector
  get() {
    if (_web_asset_off != null) {
      return _web_asset_off!!
    }
    _web_asset_off =
      ImageVector.Builder(
          name = "web_asset_off",
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
            moveTo(4f, 20f)
            quadTo(3.18f, 20f, 2.59f, 19.41f)
            reflectiveQuadTo(2f, 18f)
            verticalLineTo(6f)
            quadTo(2f, 5.18f, 2.59f, 4.59f)
            reflectiveQuadTo(4f, 4f)
            verticalLineTo(6.85f)
            lineTo(1.35f, 4.2f)
            quadTo(1.05f, 3.9f, 1.05f, 3.49f)
            reflectiveQuadTo(1.35f, 2.77f)
            reflectiveQuadTo(2.06f, 2.47f)
            quadToRelative(0.41f, 0f, 0.71f, 0.3f)
            lineToRelative(18.4f, 18.4f)
            quadToRelative(0.3f, 0.3f, 0.3f, 0.7f)
            reflectiveQuadToRelative(-0.3f, 0.7f)
            reflectiveQuadToRelative(-0.71f, 0.3f)
            reflectiveQuadToRelative(-0.71f, -0.3f)
            lineTo(17.15f, 20f)
            horizontalLineTo(4f)
            close()
            moveTo(4f, 18f)
            horizontalLineTo(15.15f)
            lineTo(5.15f, 8f)
            horizontalLineTo(4f)
            verticalLineTo(18f)
            close()
            moveTo(22f, 6f)
            verticalLineTo(17f)
            quadToRelative(0f, 0.5f, -0.31f, 0.75f)
            reflectiveQuadTo(21f, 18f)
            reflectiveQuadTo(20.31f, 17.74f)
            reflectiveQuadTo(20f, 16.98f)
            verticalLineTo(8f)
            horizontalLineTo(11.68f)
            quadTo(11.28f, 8f, 10.91f, 7.85f)
            reflectiveQuadTo(10.28f, 7.43f)
            lineTo(8.55f, 5.7f)
            quadTo(8.33f, 5.45f, 8.28f, 5.18f)
            reflectiveQuadTo(8.35f, 4.63f)
            reflectiveQuadTo(8.7f, 4.17f)
            reflectiveQuadTo(9.28f, 4f)
            horizontalLineTo(20f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(22f, 5.18f, 22f, 6f)
            close()
          }
        }
        .build()
    return _web_asset_off!!
  }

private var _web_asset_off: ImageVector? = null
