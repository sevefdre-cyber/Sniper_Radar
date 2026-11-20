package com.cryptoradar.analysis

enum class RsiConfig { PERIOD_14 }

object TechnicalIndicators {
    fun rsi(candles: List<com.cryptoradar.chart.ChartCandle>, period: Int = 14): Double? {
        require(period in 2..200)
        if (candles.size < period + 1) return null
        var averageGain = 0.0
        var averageLoss = 0.0
        for (i in candles.size - period until candles.size) {
            val change = candles[i].close - candles[i - 1].close
            if (change > 0) averageGain += change else averageLoss += kotlin.math.abs(change)
        }
        averageGain /= period
        averageLoss /= period
        if (averageLoss == 0.0) return 1.0
        val relativeStrength = averageGain / averageLoss
        val normalized = (100.0 - 100.0 / (1.0 + relativeStrength) - 50.0) / 50.0
        return normalized.coerceIn(-1.0, 1.0)
    }

    fun ema(candles: List<com.cryptoradar.chart.ChartCandle>, period: Int): Double? {
        require(period in 2..500)
        if (candles.size < period) return null
        val multiplier = 2.0 / (period + 1.0)
        var ema = candles.take(period).map { it.close }.average()
        for (i in period until candles.size) ema = (candles[i].close - ema) * multiplier + ema
        return ema
    }

    fun macd(candles: List<com.cryptoradar.chart.ChartCandle>, fast: Int = 12, slow: Int = 26): Double? {
        if (candles.size < slow) return null
        val fastEma = ema(candles, fast) ?: return null
        val slowEma = ema(candles, slow) ?: return null
        val close = candles.last().close
        val macd = fastEma - slowEma
        return if (close == 0.0) 0.0 else (macd / close * 20.0).coerceIn(-1.0, 1.0)
    }
}
