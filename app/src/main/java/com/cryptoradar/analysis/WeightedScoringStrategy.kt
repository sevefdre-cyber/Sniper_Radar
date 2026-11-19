package com.cryptoradar.analysis

/** Configurable inputs for the analysis desk. Zero weight disables a criterion. */
data class AnalysisSettings(
    val markets: Set<MarketType> = setOf(MarketType.SPOT, MarketType.FUTURES),
    val excludedStablecoins: Set<String> = setOf("USDT", "USDC", "FDUSD", "DAI", "BUSD", "TUSD"),
    val weights: Map<ScoreCriterion, Int> = ScoreCriterion.defaultWeights,
    val minimumScore: Int = 65,
    val minimumConfirmations: Int = 3,
) {
    init {
        require(weights.values.all { it >= 0 }) { "Weights cannot be negative" }
        require(weights.values.sum() > 0) { "At least one score weight must be enabled" }
        require(minimumScore in 1..100)
        require(minimumConfirmations in 1..weights.count { it.value > 0 })
    }
}

enum class MarketType { SPOT, FUTURES }

enum class ScoreCriterion {
    RSI, MACD, MOVING_AVERAGES, SUPER_TREND, PRICE_CHANGE, CHANGE_SPEED, CHANGE_FREQUENCY;

    companion object {
        /** Defaults sum to 100; users can change each weight in Settings. */
        val defaultWeights = mapOf(
            RSI to 15,
            MACD to 20,
            MOVING_AVERAGES to 15,
            SUPER_TREND to 15,
            PRICE_CHANGE to 15,
            CHANGE_SPEED to 10,
            CHANGE_FREQUENCY to 10,
        )
    }
}

enum class SignalDirection { BULLISH, BEARISH, NEUTRAL }
enum class AnalysisStage { OBSERVATION, CANDIDATE, CONFIRMATION }

data class CriterionReading(
    /** Directional reading in [-1, 1]; 0 is neutral. Indicator calculations are supplied upstream. */
    val value: Double,
    val explanation: String? = null,
) {
    init { require(value.isFinite() && value in -1.0..1.0) }
}

data class MarketSnapshot(
    val symbol: String,
    val baseAsset: String,
    val market: MarketType,
    val observedAtMillis: Long,
    val readings: Map<ScoreCriterion, CriterionReading>,
)

data class RankedSignal(
    val symbol: String,
    val market: MarketType,
    val observedAtMillis: Long,
    val direction: SignalDirection,
    /** Agreement score in [0, 100], not a probability or a guarantee of performance. */
    val score: Int,
    val confirmations: Int,
    val stage: AnalysisStage,
    val rationale: List<String>,
)

/** Deterministic weighted scoring/ranking. It does not place orders or provide financial advice. */
class WeightedScoringStrategy(private val settings: AnalysisSettings) {
    fun rank(snapshots: List<MarketSnapshot>): List<RankedSignal> = snapshots
        .asSequence()
        .filter { it.market in settings.markets }
        .filter { it.baseAsset.uppercase() !in settings.excludedStablecoins.map(String::uppercase).toSet() }
        .mapNotNull(::score)
        .sortedWith(compareByDescending<RankedSignal> { it.score }.thenBy { it.symbol })
        .toList()

    private fun score(snapshot: MarketSnapshot): RankedSignal? {
        val active = settings.weights.mapNotNull { (criterion, weight) ->
            val reading = snapshot.readings[criterion]
            if (weight > 0 && reading != null) Triple(criterion, weight, reading) else null
        }
        if (active.isEmpty()) return null

        val totalWeight = active.sumOf { it.second }
        val weightedDirection = active.sumOf { (_, weight, reading) -> weight * reading.value } / totalWeight
        val confirmations = active.count { (_, _, reading) ->
            if (weightedDirection >= 0) reading.value > 0 else reading.value < 0
        }
        val direction = when {
            weightedDirection > 0.0 -> SignalDirection.BULLISH
            weightedDirection < 0.0 -> SignalDirection.BEARISH
            else -> SignalDirection.NEUTRAL
        }
        val score = (kotlin.math.abs(weightedDirection) * 100).toInt().coerceIn(0, 100)
        val stage = when {
            direction == SignalDirection.NEUTRAL || score < settings.minimumScore -> AnalysisStage.OBSERVATION
            confirmations < settings.minimumConfirmations -> AnalysisStage.CANDIDATE
            else -> AnalysisStage.CONFIRMATION
        }
        return RankedSignal(
            symbol = snapshot.symbol,
            market = snapshot.market,
            observedAtMillis = snapshot.observedAtMillis,
            direction = direction,
            score = score,
            confirmations = confirmations,
            stage = stage,
            rationale = active.mapNotNull { (criterion, _, reading) ->
                reading.explanation ?: if (reading.value != 0.0) "${criterion.name}: ${"%.2f".format(reading.value)}" else null
            },
        )
    }
}

data class AnalysisReport(
    val generatedAtMillis: Long,
    val rankedSignals: List<RankedSignal>,
    val scannedMarkets: Set<MarketType>,
    val excludedStablecoins: Set<String>,
) {
    val confirmedCount: Int get() = rankedSignals.count { it.stage == AnalysisStage.CONFIRMATION }
    val candidateCount: Int get() = rankedSignals.count { it.stage == AnalysisStage.CANDIDATE }
}
