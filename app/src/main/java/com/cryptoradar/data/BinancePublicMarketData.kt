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
        validate(symbol, interval)
        require(limit in 1..1000) { "Kline limit must be between 1 and 1000" }
        val url = klineUrl(market, symbol, interval)
            .addQueryParameter("limit", limit.toString())
            .build()
        parseCandles(getJson(url.toString()).asJsonArray)
    }

    /**
     * Pages through historical klines and returns at most [maxCandles]. Calls are sequential
     * to avoid request bursts. Persist results in batches for very long downloads.
     */
    suspend fun fetchHistoricalCandles(
        market: MarketType,
        symbol: String,
        interval: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        maxCandles: Int = 100_000,
        onBatch: (suspend (List<ChartCandle>) -> Unit)? = null,
    ): List<ChartCandle> = withContext(Dispatchers.IO) {
        validate(symbol, interval)
        require(startTimeMillis >= 0 && endTimeMillis > startTimeMillis) { "Invalid history range" }
        require(maxCandles in 1..500_000) { "maxCandles must be between 1 and 500000" }
        val all = ArrayList<ChartCandle>(minOf(maxCandles, 20_000))
        var cursor = startTimeMillis
        while (cursor <= endTimeMillis && all.size < maxCandles) {
            val page = klineUrl(market, symbol, interval)
                .addQueryParameter("startTime", cursor.toString())
                .addQueryParameter("endTime", endTimeMillis.toString())
                .addQueryParameter("limit", minOf(1000, maxCandles - all.size).toString())
                .build()
            val batch = parseCandles(getJson(page.toString()).asJsonArray)
                .filter { it.timestampMillis in startTimeMillis..endTimeMillis }
            if (batch.isEmpty()) break
            val fresh = batch.filter { it.timestampMillis >= cursor }
            if (fresh.isEmpty()) break
            all.addAll(fresh.take(maxCandles - all.size))
            onBatch?.invoke(fresh.take(maxCandles))
            val nextCursor = fresh.last().timestampMillis + 1L
            if (nextCursor <= cursor) break
            cursor = nextCursor
            if (fresh.size < 1000) break
        }
        all.distinctBy { it.timestampMillis }.sortedBy { it.timestampMillis }
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
            BinanceSymbol(symbol, base, obj.get("quoteAsset")?.asString.orEmpty(), market)
        }
    }

    private fun validate(symbol: String, interval: String) {
        require(symbol.uppercase().matches(Regex("[A-Z0-9]{5,20}"))) { "Invalid Binance symbol" }
        require(interval in supportedIntervals) { "Unsupported kline interval" }
    }

    private fun klineUrl(market: MarketType, symbol: String, interval: String) = baseUrl(market).newBuilder()
        .addPathSegments(if (market == MarketType.SPOT) "api/v3/klines" else "fapi/v1/klines")
        .addQueryParameter("symbol", symbol.uppercase())
        .addQueryParameter("interval", interval)

    private fun parseCandles(rows: com.google.gson.JsonArray) = rows.map { row ->
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

    private fun getJson(url: String) = run {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "CryptoRadarSniper/0.1 (public market data)")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")?.let { "; retry after ${it}s" }.orEmpty()
                throw IOException("Binance public API returned HTTP ${response.code}$retryAfter")
            }
            JsonParser.parseString(response.body?.string() ?: throw IOException("Empty response from Binance"))
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

data class BinanceSymbol(val symbol: String, val baseAsset: String, val quoteAsset: String, val market: MarketType)
