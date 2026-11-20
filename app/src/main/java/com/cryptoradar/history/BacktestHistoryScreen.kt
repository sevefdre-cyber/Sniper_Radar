package com.cryptoradar.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cryptoradar.analysis.MarketType
import com.cryptoradar.backtest.BacktestConfig
import com.cryptoradar.backtest.BacktestSignal
import com.cryptoradar.backtest.BacktestSignalModel
import com.cryptoradar.backtest.Direction
import com.cryptoradar.backtest.HistoricalBacktestEngine
import com.cryptoradar.chart.ChartCandle
import com.cryptoradar.data.BinancePublicMarketData
import com.cryptoradar.data.LocalCandleStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun BacktestHistoryScreen() {
    val context = LocalContext.current
    val store = remember(context) { LocalCandleStore(context.applicationContext) }
    val api = remember { BinancePublicMarketData() }
    val scope = rememberCoroutineScope()
    var symbol by remember { mutableStateOf("BTCUSDT") }
    var interval by remember { mutableStateOf("1h") }
    var market by remember { mutableStateOf(MarketType.SPOT) }
    var candles by remember { mutableStateOf<List<ChartCandle>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<com.cryptoradar.backtest.BacktestResult?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Historique & backtesting", style = MaterialTheme.typography.headlineSmall)
        Text("Téléchargez les bougies publiques Binance, conservez-les localement et lancez une simulation hors ligne.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = market == MarketType.SPOT, onClick = { market = MarketType.SPOT }, label = { Text("Spot") })
            FilterChip(selected = market == MarketType.FUTURES, onClick = { market = MarketType.FUTURES }, label = { Text("Futures") })
        }
        OutlinedTextField(value = symbol, onValueChange = { symbol = it.uppercase() }, label = { Text("Paire (ex. BTCUSDT)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = interval, onValueChange = { interval = it }, label = { Text("Intervalle Binance (ex. 1h, 15m)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !loading, onClick = {
                loading = true
                message = null
                result = null
                scope.launch {
                    try {
                        val end = System.currentTimeMillis()
                        val start = end - 90L * 24 * 60 * 60 * 1000
                        api.fetchHistoricalCandles(market, symbol.trim(), interval.trim(), start, end, maxCandles = 10_000) { batch ->
                            store.upsert(market, symbol.trim(), interval.trim(), batch)
                        }
                        candles = store.read(market, symbol.trim(), interval.trim(), start, end)
                        message = "${candles.size} bougies téléchargées et enregistrées (${\"%tF\".format(Date(start))} → ${\"%tF\".format(Date(end))})."
                    } catch (error: Exception) {
                        message = "Téléchargement impossible : ${error.message ?: "erreur réseau"}"
                    } finally { loading = false }
                }
            }) { Text("Télécharger 90 jours") }
            Button(enabled = !loading, onClick = {
                loading = true
                message = null
                scope.launch {
                    try {
                        val end = System.currentTimeMillis()
                        candles = store.read(market, symbol.trim(), interval.trim(), 0L, end)
                        message = "${candles.size} bougies chargées depuis l’archive locale."
                    } catch (error: Exception) {
                        message = "Lecture locale impossible : ${error.message}"
                    } finally { loading = false }
                }
            }) { Text("Charger archive") }
        }
        if (loading) CircularProgressIndicator(modifier = Modifier.padding(8.dp))
        message?.let { Text(it, modifier = Modifier.padding(vertical = 8.dp)) }
        Text("Bougies disponibles : ${candles.size}")
        Button(enabled = !loading && candles.size >= 30, onClick = {
            loading = true
            scope.launch {
                try {
                    val signalModel = BacktestSignalModel { history, parameters ->
                        val lookback = (parameters["lookback"] ?: 12.0).toInt().coerceIn(2, history.lastIndex)
                        val first = history[history.size - lookback - 1].close
                        val last = history.last().close
                        if (first <= 0.0) null else {
                            val change = last / first - 1.0
                            val threshold = parameters["threshold"] ?: 0.008
                            val direction = when { change >= threshold -> Direction.LONG; change <= -threshold -> Direction.SHORT; else -> Direction.FLAT }
                            BacktestSignal(direction, (abs(change) / 0.03).coerceIn(0.0, 1.0))
                        }
                    }
                    result = HistoricalBacktestEngine(signalModel).run(
                        candles,
                        BacktestConfig(feeRate = 0.001, slippageRate = 0.0005, minimumScore = 0.25),
                        mapOf("lookback" to 12.0, "threshold" to 0.008),
                    )
                    message = "Simulation terminée. Modèle de démonstration par momentum; frais et slippage inclus, funding non inclus."
                } catch (error: Exception) {
                    message = "Backtest impossible : ${error.message}"
                } finally { loading = false }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Lancer le backtest") }

        result?.let { report ->
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Résultats", style = MaterialTheme.typography.titleLarge)
                    Text("Capital final : ${"%.2f".format(report.finalCapital)}")
                    Text("Rendement net : ${"%+.2f%%".format(report.netReturnPercent)}")
                    Text("Drawdown maximal : ${"%.2f%%".format(report.maxDrawdownPercent)}")
                    Text("Trades : ${report.tradeCount} · taux gagnant : ${"%.1f%%".format(report.winRatePercent)}")
                }
            }
            Text("Transactions simulées", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(report.trades.asReversed().take(100)) { trade ->
                    Text("${trade.direction} · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(trade.entryTimeMillis))} → ${trade.exitReason} · ${"%+.2f%%".format(trade.netReturnFraction * 100)}")
                }
            }
        }
        if (result == null && candles.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f)) {
                items(candles.asReversed().take(30)) { candle ->
                    Text("${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(candle.timestampMillis))} · O ${candle.open} H ${candle.high} L ${candle.low} C ${candle.close} · Vol ${candle.volume}")
                }
            }
        }
        Text("Simulation indicative uniquement : aucun ordre réel. Le backtest utilise un modèle momentum de démonstration; il ne constitue pas un conseil financier.",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
    }
}
