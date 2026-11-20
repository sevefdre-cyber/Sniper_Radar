package com.cryptoradar.radar

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptoradar.analysis.AnalysisStage
import com.cryptoradar.analysis.MarketType
import com.cryptoradar.analysis.RankedSignal
import com.cryptoradar.analysis.SignalDirection
import com.cryptoradar.chart.ChartLevels
import com.cryptoradar.chart.TokenCandlestickChart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveRadarScreen(viewModel: RadarViewModel) {
    val state by viewModel.state.collectAsState()
    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("CRYPTO RADAR", style = MaterialTheme.typography.headlineSmall)
                    Text(state.lastUpdatedMillis?.let { "Actualisé ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))}" } ?: "En attente des données Binance")
                }
                if (state.loading) CircularProgressIndicator()
                else TextButton(onClick = viewModel::refresh) { Text("Actualiser") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.market == MarketType.SPOT, onClick = { viewModel.setMarket(MarketType.SPOT) }, label = { Text("Spot") })
                FilterChip(selected = state.market == MarketType.FUTURES, onClick = { viewModel.setMarket(MarketType.FUTURES) }, label = { Text("Futures") })
                AssistChip(onClick = { viewModel.setInterval(if (state.interval == "15m") "1h" else "15m") }, label = { Text(state.interval) })
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            state.selectedSymbol?.let { symbol ->
                val candles = state.candlesBySymbol[symbol].orEmpty()
                TokenCandlestickChart(symbol, state.interval, candles, modifier = Modifier.padding(vertical = 8.dp))
                TextButton(onClick = { viewModel.selectSymbol(null) }) { Text("Retour au radar") }
            } ?: LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                item {
                    Text("Signaux · ${state.signals.size} actifs · scan limité à ${RadarViewModel.MAX_SCANNED_SYMBOLS} paires USDT", style = MaterialTheme.typography.labelMedium)
                }
                items(state.signals, key = { "${it.market}:${it.symbol}" }) { signal ->
                    SignalCard(signal) { viewModel.selectSymbol(signal.symbol) }
                }
                if (!state.loading && state.signals.isEmpty() && state.error == null) {
                    item { Text("Aucun signal ne dépasse actuellement le seuil. Les scores sont des indicateurs, pas des probabilités.") }
                }
                item {
                    Text("Données publiques Binance · lecture seule · aucun ordre exécuté. Les indicateurs disponibles sont simplifiés.",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun SignalCard(signal: RankedSignal, onClick: () -> Unit) {
    val direction = when (signal.direction) {
        SignalDirection.BULLISH -> "HAUSSIER"
        SignalDirection.BEARISH -> "BAISSIER"
        SignalDirection.NEUTRAL -> "NEUTRE"
    }
    val stage = when (signal.stage) {
        AnalysisStage.OBSERVATION -> "OBSERVATION"
        AnalysisStage.CANDIDATE -> "CANDIDAT"
        AnalysisStage.CONFIRMATION -> "CONFIRMATION"
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(signal.symbol, style = MaterialTheme.typography.titleMedium)
                Text("${signal.score}/100 · $direction", style = MaterialTheme.typography.labelLarge)
            }
            Text("$stage · ${signal.confirmations} confirmations · ${signal.market.name}")
            signal.rationale.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text("Toucher pour afficher le graphique", style = MaterialTheme.typography.labelSmall)
        }
    }
}
