package com.cryptoradar.data

import com.cryptoradar.analysis.MarketType
import com.cryptoradar.chart.ChartCandle
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Public Binance market-data client. No API key, account access, or order endpoints are used. */
class BinancePublicMarketData(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun fetchCandles(
        market: MarketType,
        symbol: String,
        interval: String,
        limit: Int = 200,
    ): List<ChartCandle> = withContext(Dispatchers.IO) {
        val safeSymbol = symbol.uppercase()
        require(safeSymbol.matches(Regex("[A-Z0-9]{5,20}"))) { "Invalid Binance symbol" }
        require(interval in supportedIntervals) { "Unsupported kline interval" }
        require(limit in 1..1000) { "Kline limit must be between 1 and 1000" }

        val url = baseUrl(market).newBuilder()
            .addPathSegments(if (market == MarketType.SPOT) "api/v3/klines" else "fapi/v1/klines")
            .addQueryParameter("symbol", safeSymbol)
            .addQueryParameter("interval", interval)
            .addQueryParameter("limit", limit.toString())
            .build()
        val root = getJson(url.toString()).asJsonArray
        root.map { row ->
            val values = row.asJsonArray
            ChartCandle(
                timestampMillis = values[0].asLong,
                open = values[1].asString.toDouble(),
                high = values[2].asString.toDouble(),
                low = values[3].asString.toDouble(),
                close = values[4].asString.toDouble(),
                volume = values[5].asString.toDouble(),
            )
        }
    }

    /** Returns currently trading symbols and optionally filters stablecoins by base asset. */
    suspend fun fetchSymbols(
        market: MarketType,
        excludedStablecoins: Set<String> = defaultStablecoins,
    ): List<BinanceSymbol> = withContext(Dispatchers.IO) {
        val path = if (market == MarketType.SPOT) "api/v3/exchangeInfo" else "fapi/v1/exchangeInfo"
        val symbols = getJson(baseUrl(market).newBuilder().addPathSegments(path).build().toString())
            .asJsonObject.getAsJsonArray("symbols")
        val excluded = excludedStablecoins.map { it.uppercase() }.toSet()
        symbols.mapNotNull { item ->
            val obj = item.asJsonObject
            val status = obj.get("status")?.asString ?: return@mapNotNull null
            val base = obj.get("baseAsset")?.asString ?: return@mapNotNull null
            val symbol = obj.get("symbol")?.asString ?: return@mapNotNull null
            if (status != "TRADING" || base.uppercase() in excluded) return@mapNotNull null
            if (market == MarketType.FUTURES && obj.get("contractType")?.asString != "PERPETUAL") return@mapNotNull null
            BinanceSymbol(
                symbol = symbol,
                baseAsset = base,
                quoteAsset = obj.get("quoteAsset")?.asString.orEmpty(),
                market = market,
            )
        }
    }

    private fun getJson(url: String) = run {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "CryptoRadarSniper/0.1 (public market data)")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")?.let { "; retry after ${it}s" }.orEmpty()
                throw IOException("Binance public API returned HTTP ${response.code}$retryAfter")
            }
            val body = response.body?.string() ?: throw IOException("Empty response from Binance")
            JsonParser.parseString(body)
        }
    }

    private fun baseUrl(market: MarketType) =
        (if (market == MarketType.SPOT) "https://api.binance.com" else "https://fapi.binance.com").toHttpUrl()

    companion object {
        val supportedIntervals = setOf(
            "1s", "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h",
            "1d", "3d", "1w", "1M",
        )
        val defaultStablecoins = setOf("USDT", "USDC", "FDUSD", "DAI", "BUSD", "TUSD", "USDP", "USDE")
    }
}

data class BinanceSymbol(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val market: MarketType,
)
