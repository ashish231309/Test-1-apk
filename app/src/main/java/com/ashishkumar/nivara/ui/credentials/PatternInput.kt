package com.ashishkumar.nivara.ui.credentials

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/** Drag through the 3x3 nodes; only cell indices live in transient Compose state. */
@Composable
fun PatternInput(
    selectedPoints: List<Int>,
    onPointsChanged: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val selected = MaterialTheme.colorScheme.primaryContainer
    val gridSize = with(LocalDensity.current) { 280.dp.toPx() }

    Canvas(
        modifier = modifier
            .size(280.dp)
            .semantics { contentDescription = "Pattern input, 3 by 3 grid. Drag through the points." }
            .pointerInput(Unit) {
                val active = mutableListOf<Int>()
                fun nearestPoint(position: Offset): Int? {
                    val minDimension = minOf(size.width, size.height).toFloat()
                    val inset = minDimension * 0.16f
                    val step = (minDimension - inset * 2) / 2f
                    val column = ((position.x - inset) / step).toInt().coerceIn(0, 2)
                    val row = ((position.y - inset) / step).toInt().coerceIn(0, 2)
                    val center = Offset(inset + column * step, inset + row * step)
                    return if ((position - center).getDistance() <= step * 0.55f) row * 3 + column else null
                }
                detectDragGestures(
                    onDragStart = { position ->
                        active.clear()
                        nearestPoint(position)?.let(active::add)
                        onPointsChanged(active.toList())
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        nearestPoint(change.position)?.let { point ->
                            if (point !in active) {
                                active += point
                                onPointsChanged(active.toList())
                            }
                        }
                    },
                    onDragEnd = { },
                    onDragCancel = { },
                )
            },
    ) {
        val inset = size.minDimension * 0.16f
        val step = (size.minDimension - inset * 2) / 2f
        fun center(id: Int) = Offset(inset + (id % 3) * step, inset + (id / 3) * step)

        if (selectedPoints.size > 1) {
            val path = Path().apply {
                moveTo(center(selectedPoints.first()).x, center(selectedPoints.first()).y)
                selectedPoints.drop(1).forEach { point -> lineTo(center(point).x, center(point).y) }
            }
            drawPath(path, primary, style = Stroke(width = gridSize * 0.035f))
        }
        for (point in 0..8) {
            drawCircle(
                color = if (point in selectedPoints) selected else outline,
                radius = gridSize * if (point in selectedPoints) 0.075f else 0.055f,
                center = center(point),
            )
            if (point in selectedPoints) {
                drawCircle(color = primary, radius = gridSize * 0.035f, center = center(point))
            }
        }
    }
}
