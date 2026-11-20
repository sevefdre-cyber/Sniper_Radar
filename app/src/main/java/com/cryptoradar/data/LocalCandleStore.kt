package com.cryptoradar.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cryptoradar.analysis.MarketType
import com.cryptoradar.chart.ChartCandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App-private SQLite archive for downloaded OHLCV history; no network access is performed here. */
class LocalCandleStore(context: Context) : SQLiteOpenHelper(context, "crypto_radar_history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE candles (
                market TEXT NOT NULL, symbol TEXT NOT NULL, interval TEXT NOT NULL,
                open_time INTEGER NOT NULL, open REAL NOT NULL, high REAL NOT NULL,
                low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL,
                PRIMARY KEY (market, symbol, interval, open_time)
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX candle_history_lookup ON candles(market, symbol, interval, open_time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    suspend fun upsert(market: MarketType, symbol: String, interval: String, candles: List<ChartCandle>) =
        withContext(Dispatchers.IO) {
            if (candles.isEmpty()) return@withContext
            val db = writableDatabase
            db.beginTransaction()
            try {
                candles.forEach { candle ->
                    val values = ContentValues().apply {
                        put("market", market.name)
                        put("symbol", symbol.uppercase())
                        put("interval", interval)
                        put("open_time", candle.timestampMillis)
                        put("open", candle.open)
                        put("high", candle.high)
                        put("low", candle.low)
                        put("close", candle.close)
                        put("volume", candle.volume)
                    }
                    db.insertWithOnConflict("candles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

    suspend fun read(
        market: MarketType,
        symbol: String,
        interval: String,
        fromMillis: Long,
        toMillis: Long,
        limit: Int = 100_000,
    ): List<ChartCandle> = withContext(Dispatchers.IO) {
        require(toMillis >= fromMillis && limit in 1..500_000)
        readableDatabase.query(
            "candles", arrayOf("open_time", "open", "high", "low", "close", "volume"),
            "market=? AND symbol=? AND interval=? AND open_time BETWEEN ? AND ?",
            arrayOf(market.name, symbol.uppercase(), interval, fromMillis.toString(), toMillis.toString()),
            null, null, "open_time ASC", limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    ChartCandle(
                        cursor.getLong(0), cursor.getDouble(1), cursor.getDouble(2),
                        cursor.getDouble(3), cursor.getDouble(4), cursor.getDouble(5),
                    ),
                )
            }
        }
    }

    suspend fun count(market: MarketType, symbol: String, interval: String): Long = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM candles WHERE market=? AND symbol=? AND interval=?",
            arrayOf(market.name, symbol.uppercase(), interval),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }
}
