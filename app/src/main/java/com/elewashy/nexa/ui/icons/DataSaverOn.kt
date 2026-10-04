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
val DataSaverOn: ImageVector
  get() {
    if (_data_saver_on != null) {
      return _data_saver_on!!
    }
    _data_saver_on =
      ImageVector.Builder(
          name = "data_saver_on",
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
            moveTo(12f, 22f)
            quadTo(9.93f, 22f, 8.1f, 21.21f)
            quadTo(6.28f, 20.43f, 4.93f, 19.08f)
            quadTo(3.58f, 17.73f, 2.79f, 15.9f)
            reflectiveQuadTo(2f, 12f)
            quadTo(2f, 8.75f, 3.88f, 6.15f)
            reflectiveQuadTo(8.85f, 2.52f)
            quadTo(9.58f, 2.27f, 10.19f, 2.7f)
            reflectiveQuadTo(10.8f, 3.85f)
            quadToRelative(0f, 0.5f, -0.29f, 0.91f)
            reflectiveQuadTo(9.78f, 5.32f)
            quadTo(7.63f, 6f, 6.31f, 7.84f)
            reflectiveQuadTo(5f, 12f)
            quadToRelative(0f, 2.92f, 2.04f, 4.96f)
            reflectiveQuadTo(12f, 19f)
            quadToRelative(1.3f, 0f, 2.51f, -0.45f)
            reflectiveQuadToRelative(2.16f, -1.3f)
            quadTo(17.05f, 16.9f, 17.59f, 16.9f)
            reflectiveQuadToRelative(0.91f, 0.35f)
            quadToRelative(0.57f, 0.52f, 0.6f, 1.19f)
            reflectiveQuadTo(18.55f, 19.6f)
            quadToRelative(-1.35f, 1.17f, -3.01f, 1.79f)
            reflectiveQuadTo(12f, 22f)
            close()
            moveTo(19f, 12f)
            quadTo(19f, 9.7f, 17.68f, 7.86f)
            quadTo(16.35f, 6.02f, 14.2f, 5.32f)
            quadTo(13.75f, 5.18f, 13.46f, 4.76f)
            reflectiveQuadTo(13.18f, 3.85f)
            quadToRelative(0f, -0.72f, 0.61f, -1.15f)
            reflectiveQuadTo(15.13f, 2.52f)
            quadToRelative(3.13f, 1.05f, 5f, 3.65f)
            quadTo(22f, 8.77f, 22f, 12f)
            quadToRelative(0f, 0.45f, -0.04f, 0.91f)
            reflectiveQuadToRelative(-0.14f, 1.01f)
            quadToRelative(-0.13f, 0.73f, -0.74f, 1.04f)
            reflectiveQuadTo(19.75f, 15f)
            quadTo(19.28f, 14.83f, 19.01f, 14.36f)
            reflectiveQuadTo(18.85f, 13.4f)
            quadToRelative(0.07f, -0.42f, 0.11f, -0.75f)
            quadTo(19f, 12.33f, 19f, 12f)
            close()
            moveToRelative(-8f, 1f)
            horizontalLineTo(9f)
            quadTo(8.58f, 13f, 8.29f, 12.71f)
            quadTo(8f, 12.43f, 8f, 12f)
            reflectiveQuadTo(8.29f, 11.29f)
            quadTo(8.58f, 11f, 9f, 11f)
            horizontalLineToRelative(2f)
            verticalLineTo(9f)
            quadTo(11f, 8.57f, 11.29f, 8.29f)
            reflectiveQuadTo(12f, 8f)
            reflectiveQuadToRelative(0.71f, 0.29f)
            reflectiveQuadTo(13f, 9f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(16f, 12f)
            reflectiveQuadToRelative(-0.29f, 0.71f)
            reflectiveQuadTo(15f, 13f)
            horizontalLineTo(13f)
            verticalLineToRelative(2f)
            quadToRelative(0f, 0.42f, -0.29f, 0.71f)
            reflectiveQuadTo(12f, 16f)
            reflectiveQuadTo(11.29f, 15.71f)
            reflectiveQuadTo(11f, 15f)
            verticalLineTo(13f)
            close()
          }
        }
        .build()
    return _data_saver_on!!
  }

private var _data_saver_on: ImageVector? = null
