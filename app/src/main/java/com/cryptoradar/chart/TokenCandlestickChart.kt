package com.cryptoradar.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** OHLCV candle. Prices and volume should come from the selected market/timeframe feed. */
data class ChartCandle(
    val timestampMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

data class ChartLevels(
    val entry: Double? = null,
    val takeProfit: Double? = null,
    val stopLoss: Double? = null,
)

/**
 * Interactive candlestick chart with pinch zoom, horizontal pan, volume bars and optional
 * reference levels. Lines are visual analysis levels only; they never place orders.
 */
@Composable
fun TokenCandlestickChart(
    symbol: String,
    timeframe: String,
    candles: List<ChartCandle>,
    levels: ChartLevels = ChartLevels(),
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panPixels by remember { mutableFloatStateOf(0f) }
    val colors = MaterialTheme.colorScheme
    val upColor = Color(0xFF26A69A)
    val downColor = Color(0xFFEF5350)

    Column(modifier = modifier.fillMaxWidth()) {
        Text("$symbol · $timeframe", style = MaterialTheme.typography.titleMedium)
        Text(
            volumeChangeLabel(candles),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .pointerInput(candles.size) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, 12f)
                        panPixels = (panPixels + pan.x).coerceIn(-size.width.toFloat() * 4f, size.width.toFloat() * 4f)
                    }
                },
        ) {
            if (candles.isEmpty()) return@Canvas
            val left = 8f
            val right = size.width - 66f
            val top = 12f
            val priceBottom = size.height * 0.72f
            val volumeTop = size.height * 0.79f
            val chartWidth = right - left
            val visibleCount = (candles.size / zoom).toInt().coerceIn(5, candles.size)
            val cellWidth = chartWidth / visibleCount
            val start = (candles.size - visibleCount - (panPixels / max(cellWidth, 1f)).toInt())
                .coerceIn(0, max(0, candles.size - visibleCount))
            val visible = candles.subList(start, start + visibleCount)
            val levelPrices = listOfNotNull(levels.entry, levels.takeProfit, levels.stopLoss)
            val minPrice = min(visible.minOf { it.low }, levelPrices.minOrNull() ?: Double.POSITIVE_INFINITY)
            val maxPrice = max(visible.maxOf { it.high }, levelPrices.maxOrNull() ?: Double.NEGATIVE_INFINITY)
            val priceRange = (maxPrice - minPrice).takeIf { it > 0.0 } ?: maxPrice * 0.01
            val maxVolume = visible.maxOf { it.volume }.coerceAtLeast(1.0)
            fun y(price: Double) = priceBottom - ((price - minPrice) / priceRange).toFloat() * (priceBottom - top)

            val axisPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.LTGRAY
                textSize = 11.dp.toPx()
            }
            val gridPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.DKGRAY
                strokeWidth = 1.dp.toPx()
            }
            // Price grid and right-side labels.
            for (i in 0..4) {
                val gy = top + (priceBottom - top) * i / 4f
                drawLine(Color.LightGray.copy(alpha = 0.22f), Offset(left, gy), Offset(right, gy), 1.dp.toPx())
                val price = maxPrice - priceRange * i / 4.0
                drawContext.canvas.nativeCanvas.drawText("%.6g".format(price), right + 5f, gy + 4f, axisPaint)
            }

            // Volume histogram and candles.
            visible.forEachIndexed { index, candle ->
                val x = left + index * cellWidth + cellWidth / 2f
                val rising = candle.close >= candle.open
                val color = if (rising) upColor else downColor
                val candlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color.toArgb()
                    strokeWidth = max(1.dp.toPx(), cellWidth * 0.08f)
                    style = android.graphics.Paint.Style.STROKE
                }
                drawLine(color, Offset(x, y(candle.high)), Offset(x, y(candle.low)), candlePaint.strokeWidth)
                val bodyTop = y(max(candle.open, candle.close))
                val bodyBottom = y(min(candle.open, candle.close))
                drawRect(
                    color,
                    Offset(x - cellWidth * 0.32f, bodyTop),
                    androidx.compose.ui.geometry.Offset(x + cellWidth * 0.32f, max(bodyTop + 1f, bodyBottom)),
                )
                val volumeHeight = (candle.volume / maxVolume).toFloat() * (size.height - volumeTop - 8f)
                drawRect(
                    color.copy(alpha = 0.55f),
                    Offset(x - cellWidth * 0.3f, size.height - volumeHeight),
                    Offset(x + cellWidth * 0.3f, size.height),
                )
            }

            fun drawLevel(price: Double?, label: String, color: Color, style: android.graphics.Paint.Style) {
                if (price == null || price !in minPrice..maxPrice) return
                val levelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color.toArgb()
                    strokeWidth = 1.5.dp.toPx()
                    this.style = style
                    textSize = 11.dp.toPx()
                }
                val levelY = y(price)
                drawLine(color, Offset(left, levelY), Offset(right, levelY), 1.5.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText("$label %.6g".format(price), left + 4f, levelY - 4f, levelPaint)
            }
            drawLevel(levels.entry, "ENTRY", Color(0xFF42A5F5), android.graphics.Paint.Style.FILL)
            drawLevel(levels.takeProfit, "TARGET", upColor, android.graphics.Paint.Style.FILL)
            drawLevel(levels.stopLoss, "STOP", downColor, android.graphics.Paint.Style.FILL)
            drawContext.canvas.nativeCanvas.drawText("Volume", left, volumeTop - 3f, axisPaint)
        }
        Text(
            "Pincez pour zoomer · faites glisser pour parcourir. Niveaux indicatifs, aucun ordre n’est exécuté.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun volumeChangeLabel(candles: List<ChartCandle>): String {
    if (candles.size < 2) return "Variation du volume : données insuffisantes"
    val previous = candles[candles.lastIndex - 1].volume
    val current = candles.last().volume
    if (previous <= 0.0) return "Dernière bougie : volume ${"%.4g".format(current)}"
    val change = (current - previous) / previous * 100.0
    val direction = if (change >= 0) "▲" else "▼"
    return "$direction Volume dernière bougie : ${"%+.1f%%".format(change)} · ${"%.4g".format(current)}"
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
