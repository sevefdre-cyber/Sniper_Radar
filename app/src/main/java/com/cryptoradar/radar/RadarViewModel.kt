package com.cryptoradar.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptoradar.analysis.AnalysisSettings
import com.cryptoradar.analysis.AnalysisStage
import com.cryptoradar.analysis.CriterionReading
import com.cryptoradar.analysis.MarketSnapshot
import com.cryptoradar.analysis.MarketType
import com.cryptoradar.analysis.RankedSignal
import com.cryptoradar.analysis.ScoreCriterion
import com.cryptoradar.analysis.WeightedScoringStrategy
import com.cryptoradar.chart.ChartCandle
import com.cryptoradar.data.BinancePublicMarketData
import com.cryptoradar.data.BinanceSymbol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

 data class RadarUiState(
    val market: MarketType = MarketType.SPOT,
    val interval: String = "15m",
    val symbols: List<BinanceSymbol> = emptyList(),
    val signals: List<RankedSignal> = emptyList(),
    val candlesBySymbol: Map<String, List<ChartCandle>> = emptyMap(),
    val selectedSymbol: String? = null,
    val loading: Boolean = false,
    val lastUpdatedMillis: Long? = null,
    val error: String? = null,
)

/** Live read-only market radar. Periodically fetches a bounded public-symbol universe. */
class RadarViewModel(
    private val api: BinancePublicMarketData = BinancePublicMarketData(),
) : ViewModel() {
    var state = kotlinx.coroutines.flow.MutableStateFlow(RadarUiState())
        private set
    private var pollingJob: Job? = null

    fun start() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun setMarket(market: MarketType) {
        if (state.value.market == market) return
        state.value = state.value.copy(market = market, symbols = emptyList(), signals = emptyList(), candlesBySymbol = emptyMap())
        refresh()
    }

    fun setInterval(interval: String) {
        require(interval in BinancePublicMarketData.supportedIntervals)
        state.value = state.value.copy(interval = interval, candlesBySymbol = emptyMap())
        refresh()
    }

    fun selectSymbol(symbol: String?) {
        state.value = state.value.copy(selectedSymbol = symbol)
    }

    fun refresh() {
        viewModelScope.launch {
            val current = state.value
            state.value = current.copy(loading = true, error = null)
            try {
                val symbols = if (current.symbols.isEmpty()) api.fetchSymbols(current.market) else current.symbols
                // Bound polling: fetching every Binance listing is too heavy for a phone and shared IP.
                val universe = symbols.filter { it.quoteAsset == "USDT" }.take(MAX_SCANNED_SYMBOLS)
                val candles = linkedMapOf<String, List<ChartCandle>>()
                supervisorScope {
                    universe.map { symbol ->
                        launch {
                            val rows = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
                                api.fetchCandles(current.market, symbol.symbol, current.interval, CANDLE_LIMIT)
                            }.orEmpty()
                            if (rows.size >= MIN_CANDLES_FOR_SIGNAL) synchronized(candles) { candles[symbol.symbol] = rows }
                        }
                    }.forEach { it.join() }
                }
                val snapshots = candles.mapNotNull { (symbol, rows) -> toSnapshot(symbol, current.market, rows) }
                val ranked = WeightedScoringStrategy(
                    AnalysisSettings(markets = setOf(current.market), minimumScore = MINIMUM_SCORE),
                ).rank(snapshots)
                state.value = state.value.copy(
                    symbols = symbols,
                    candlesBySymbol = candles,
                    signals = ranked,
                    lastUpdatedMillis = System.currentTimeMillis(),
                    loading = false,
                    error = if (candles.isEmpty()) "Aucune donnée reçue. Réessayez plus tard." else null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                state.value = state.value.copy(loading = false, error = failure.message ?: "Erreur Binance")
            }
        }
    }

    private fun toSnapshot(symbol: String, market: MarketType, candles: List<ChartCandle>): MarketSnapshot? {
        val closes = candles.map { it.close }
        val latest = closes.lastOrNull()?.takeIf { it > 0 } ?: return null
        val previous = closes[closes.lastIndex - 1]
        val change = ((latest - previous) / previous).coerceIn(-1.0, 1.0)
        val recent = closes.takeLast(14)
        val avg = recent.average()
        val deviation = recent.sumOf { kotlin.math.abs(it - avg) } / recent.size
        val rsi = if (deviation == 0.0) 0.0 else ((latest - avg) / (deviation * 2.0)).coerceIn(-1.0, 1.0)
        val shortAverage = closes.takeLast(7).average()
        val longAverage = closes.takeLast(21).average()
        val macdLike = if (latest == 0.0) 0.0 else ((shortAverage - longAverage) / latest * 20.0).coerceIn(-1.0, 1.0)
        val trend = if (latest >= longAverage) 1.0 else -1.0
        val volumeAverage = candles.takeLast(20).map { it.volume }.average().coerceAtLeast(1e-12)
        val volumeImpulse = ((candles.last().volume / volumeAverage) - 1.0).coerceIn(-1.0, 1.0)
        val readings = mapOf(
            ScoreCriterion.RSI to CriterionReading(rsi, "Oscillateur normalisé (approximation)"),
            ScoreCriterion.MACD to CriterionReading(macdLike, "Écart de moyennes mobiles simplifié"),
            ScoreCriterion.MOVING_AVERAGES to CriterionReading(trend, "Cours vs moyenne 21 périodes"),
            ScoreCriterion.SUPER_TREND to CriterionReading(trend, "Tendance simplifiée, pas un SuperTrend ATR"),
            ScoreCriterion.PRICE_CHANGE to CriterionReading(change * 10.0, "Variation de la dernière bougie"),
            ScoreCriterion.CHANGE_SPEED to CriterionReading((change - ((previous - closes[closes.lastIndex - 2]) / previous)) * 10.0, "Accélération des variations"),
            ScoreCriterion.CHANGE_FREQUENCY to CriterionReading(volumeImpulse * trend, "Impulsion de volume simplifiée"),
        )
        return MarketSnapshot(symbol, symbol.removeSuffix("USDT"), market, candles.last().timestampMillis, readings)
    }

    companion object {
        const val POLL_INTERVAL_MILLIS = 60_000L
        const val MAX_SCANNED_SYMBOLS = 12
        const val CANDLE_LIMIT = 80
        const val MIN_CANDLES_FOR_SIGNAL = 25
        const val MINIMUM_SCORE = 45
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
    }
}
