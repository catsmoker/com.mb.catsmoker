package com.catsmoker.app.shared.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp

/**
 * The faint dot texture behind [QuickActionButton].
 *
 * One 10dp tile is drawn once into an [ImageBitmap] and repeated by an [ImageShader], rather than
 * looping `drawCircle` across the surface: the dots are 1dp apart, so a full-surface loop issues
 * hundreds of draw calls per frame for a background nobody looks at. `drawWithCache` keeps the tile
 * across recompositions and only re-renders it when the size changes.
 */
@Composable
fun DotGridBackground(modifier: Modifier = Modifier) {
    val dotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    Spacer(
        modifier = modifier.drawWithCache {
            val step = 10.dp.toPx()
            val dotRadius = 1.dp.toPx()

            // Create a small tile bitmap
            val tileBitmap = ImageBitmap(step.toInt(), step.toInt())
            val tileCanvas = Canvas(tileBitmap)
            val paint = Paint().apply { color = dotColor }

            // Draw a single dot in the center of the tile
            tileCanvas.drawCircle(
                center = androidx.compose.ui.geometry.Offset(step / 2, step / 2),
                radius = dotRadius,
                paint = paint
            )

            val shader = ImageShader(tileBitmap, TileMode.Repeated, TileMode.Repeated)
            val brush = ShaderBrush(shader)

            onDrawBehind {
                drawRect(brush)
            }
        }
    )
}
