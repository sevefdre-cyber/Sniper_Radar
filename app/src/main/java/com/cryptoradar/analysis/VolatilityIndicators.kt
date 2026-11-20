package com.cryptoradar.analysis

/** True Average True Range (ATR), Wilder smoothing, and volatility readings. */
object VolatilityIndicators {
    data class AtrReading(val atr: Double, val atrPercent: Double)

    data class VolatilityReading(
        val atrPercent: Double,
        val directionalBias: Double,
        val explanation: String,
    )

    fun calculateAtr(
        candles: List<com.cryptoradar.chart.ChartCandle>,
        period: Int = 14,
    ): AtrReading? {
        require(period in 2..500)
        if (candles.size <= period) return null

        val trueRanges = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val previousClose = candles[i - 1].close
            val high = candles[i].high
            val low = candles[i].low
            val trueRange = maxOf(high - low, kotlin.math.abs(high - previousClose), kotlin.math.abs(low - previousClose))
            trueRanges += trueRange
        }
        if (trueRanges.size < period) return null

        var atr = trueRanges.take(period).average()
        for (i in period until trueRanges.size) {
            atr = (atr * (period - 1) + trueRanges[i]) / period
        }
        val close = candles.last().close
        if (close <= 0.0 || !close.isFinite()) return null
        val atrPercent = atr / close * 100.0
        return AtrReading(atr, atrPercent)
    }

    fun volatilityRead(candles: List<com.cryptoradar.chart.ChartCandle>, period: Int = 14): VolatilityReading? {
        val atr = calculateAtr(candles, period) ?: return null
        val halfWindow = kotlin.math.min(20, candles.size)
        val previous = if (candles.size > halfWindow) candles[candles.size - halfWindow - 1].close else candles.first().close
        val change = (candles.last().close - previous) / previous
        val bias = change.coerceIn(-1.0, 1.0)
        val boundedAtr = (atr.atrPercent / 10.0).coerceIn(0.0, 1.0)
        val explanation = "ATR ${"%.2f".format(atr.atrPercent)}% · volatilité ${if (boundedAtr >= 0.45) "élevée" else "modérée/faible"}"
        return VolatilityReading(atr.atrPercent, bias * boundedAtr, explanation)
    }
}
