package com.cryptoradar.backtest

import com.cryptoradar.chart.ChartCandle
import kotlin.math.max
import kotlin.math.min

/** Returned signal must use only the candles in [history], which ends at the signal candle. */
data class BacktestSignal(val direction: Direction, val score: Double)
enum class Direction { LONG, SHORT, FLAT }

fun interface BacktestSignalModel {
    fun signal(history: List<ChartCandle>, parameters: Map<String, Double>): BacktestSignal?
}

data class BacktestConfig(
    val initialCapital: Double = 10_000.0,
    val feeRate: Double = 0.001,
    val slippageRate: Double = 0.0005,
    val minimumScore: Double = 0.65,
    val stopLossFraction: Double = 0.02,
    val takeProfitFraction: Double = 0.04,
    val maxHoldingBars: Int = 48,
) {
    init {
        require(initialCapital > 0)
        require(feeRate >= 0 && slippageRate >= 0)
        require(minimumScore in 0.0..1.0)
        require(stopLossFraction > 0 && takeProfitFraction > 0)
        require(maxHoldingBars > 0)
    }
}

data class BacktestTrade(
    val entryTimeMillis: Long,
    val exitTimeMillis: Long,
    val direction: Direction,
    val entryPrice: Double,
    val exitPrice: Double,
    val netReturnFraction: Double,
    val exitReason: ExitReason,
)
enum class ExitReason { STOP_LOSS, TAKE_PROFIT, TIME_LIMIT, END_OF_DATA }

data class BacktestResult(
    val initialCapital: Double,
    val finalCapital: Double,
    val netReturnPercent: Double,
    val maxDrawdownPercent: Double,
    val winRatePercent: Double,
    val tradeCount: Int,
    val trades: List<BacktestTrade>,
)

/** Sequential, one-position-at-a-time simulation. Signals execute at the next candle open. */
class HistoricalBacktestEngine(private val model: BacktestSignalModel) {
    fun run(
        candles: List<ChartCandle>,
        config: BacktestConfig = BacktestConfig(),
        parameters: Map<String, Double> = emptyMap(),
    ): BacktestResult {
        require(candles.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis }) {
            "Candles must be strictly ordered and unique"
        }
        if (candles.size < 3) return result(config.initialCapital, config.initialCapital, emptyList(), 0.0)

        var capital = config.initialCapital
        var peak = capital
        var maxDrawdown = 0.0
        val trades = mutableListOf<BacktestTrade>()
        var signalIndex = 0
        while (signalIndex < candles.lastIndex) {
            val signal = model.signal(candles.subList(0, signalIndex + 1), parameters)
            if (signal == null || signal.direction == Direction.FLAT || signal.score < config.minimumScore) {
                signalIndex++
                continue
            }
            val entryIndex = signalIndex + 1
            val entryBar = candles[entryIndex]
            val long = signal.direction == Direction.LONG
            val entry = entryBar.open * (1.0 + if (long) config.slippageRate else -config.slippageRate)
            val stop = if (long) entry * (1 - config.stopLossFraction) else entry * (1 + config.stopLossFraction)
            val target = if (long) entry * (1 + config.takeProfitFraction) else entry * (1 - config.takeProfitFraction)
            val lastIndex = min(candles.lastIndex, entryIndex + config.maxHoldingBars - 1)
            var exitIndex = lastIndex
            var exit = candles[lastIndex].close
            var reason = if (lastIndex == candles.lastIndex) ExitReason.END_OF_DATA else ExitReason.TIME_LIMIT

            for (index in entryIndex..lastIndex) {
                val bar = candles[index]
                // Conservative tie-break: when stop and target both occur inside one candle, count stop first.
                val stopped = if (long) bar.low <= stop else bar.high >= stop
                val targeted = if (long) bar.high >= target else bar.low <= target
                if (stopped) {
                    exitIndex = index
                    exit = stop
                    reason = ExitReason.STOP_LOSS
                    break
                }
                if (targeted) {
                    exitIndex = index
                    exit = target
                    reason = ExitReason.TAKE_PROFIT
                    break
                }
            }
            exit *= 1.0 + if (long) -config.slippageRate else config.slippageRate
            val gross = if (long) exit / entry - 1.0 else entry / exit - 1.0
            val net = gross - 2.0 * config.feeRate
            capital *= 1.0 + net
            peak = max(peak, capital)
            if (peak > 0) maxDrawdown = max(maxDrawdown, (peak - capital) / peak)
            trades += BacktestTrade(entryBar.timestampMillis, candles[exitIndex].timestampMillis,
                signal.direction, entry, exit, net, reason)
            signalIndex = exitIndex + 1
        }
        return result(config.initialCapital, capital, trades, maxDrawdown * 100.0)
    }

    private fun result(initial: Double, final: Double, trades: List<BacktestTrade>, drawdown: Double) =
        BacktestResult(
            initial, final, (final / initial - 1.0) * 100.0, drawdown,
            if (trades.isEmpty()) 0.0 else trades.count { it.netReturnFraction > 0 } * 100.0 / trades.size,
            trades.size, trades,
        )
}

data class OptimizationSpace(
    val modelParameters: Map<String, List<Double>>,
    val minimumScores: List<Double>,
    val stopLossFractions: List<Double>,
    val takeProfitFractions: List<Double>,
    val maxHoldingBars: List<Int>,
    val maxCandidates: Int = 5_000,
) {
    init {
        require(maxCandidates in 1..50_000)
        require(minimumScores.isNotEmpty() && stopLossFractions.isNotEmpty() &&
            takeProfitFractions.isNotEmpty() && maxHoldingBars.isNotEmpty())
        require(modelParameters.values.all { it.isNotEmpty() })
    }
}

data class OptimizationCandidate(val config: BacktestConfig, val modelParameters: Map<String, Double>)
data class OptimizationResult(
    val candidate: OptimizationCandidate,
    val training: BacktestResult,
    val validation: BacktestResult,
    val validationObjective: Double,
)

/** Grid search with a chronological train/validation split and a hard combination-count guard. */
class HistoricalStrategyOptimizer(private val model: BacktestSignalModel) {
    fun optimize(
        candles: List<ChartCandle>,
        space: OptimizationSpace,
        baseConfig: BacktestConfig = BacktestConfig(),
        validationFraction: Double = 0.3,
        minimumValidationTrades: Int = 5,
        drawdownPenalty: Double = 0.5,
    ): List<OptimizationResult> {
        require(validationFraction in 0.1..0.5)
        require(minimumValidationTrades >= 0 && drawdownPenalty >= 0)
        require(candles.size >= 20) { "At least 20 candles are required for optimization" }
        val choices = space.modelParameters.toList()
        var combinations = 1L
        (choices.map { it.second.size } + listOf(
            space.minimumScores.size, space.stopLossFractions.size,
            space.takeProfitFractions.size, space.maxHoldingBars.size,
        )).forEach { size ->
            combinations *= size
            require(combinations <= space.maxCandidates) { "Parameter grid exceeds maxCandidates" }
        }
        val split = (candles.size * (1.0 - validationFraction)).toInt().coerceIn(2, candles.size - 2)
        val train = candles.take(split)
        val validation = candles.drop(split)
        val modelGrids = cartesianModelParameters(choices)
        val engine = HistoricalBacktestEngine(model)
        val results = buildList {
            for (params in modelGrids) for (score in space.minimumScores)
                for (stop in space.stopLossFractions) for (target in space.takeProfitFractions)
                    for (holding in space.maxHoldingBars) {
                        val config = baseConfig.copy(
                            minimumScore = score,
                            stopLossFraction = stop,
                            takeProfitFraction = target,
                            maxHoldingBars = holding,
                        )
                        val training = engine.run(train, config, params)
                        val validationResult = engine.run(validation, config, params)
                        if (validationResult.tradeCount < minimumValidationTrades) continue
                        val objective = validationResult.netReturnPercent -
                            drawdownPenalty * validationResult.maxDrawdownPercent
                        add(OptimizationResult(OptimizationCandidate(config, params), training, validationResult, objective))
                    }
        }
        return results.sortedWith(compareByDescending<OptimizationResult> { it.validationObjective }
            .thenByDescending { it.validation.tradeCount })
    }

    private fun cartesianModelParameters(entries: List<Pair<String, List<Double>>>): List<Map<String, Double>> {
        var rows = listOf(emptyMap())
        for ((key, values) in entries) rows = rows.flatMap { row -> values.map { row + (key to it) } }
        return rows
    }
}
