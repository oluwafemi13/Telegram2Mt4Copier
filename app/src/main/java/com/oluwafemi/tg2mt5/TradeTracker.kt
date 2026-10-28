package com.oluwafemi.tg2mt5

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** A trade the app placed on MT5, kept until its final result (TP, SL or other close) is known. */
data class TrackedTrade(
    val positionId: String,
    val symbol: String,
    val isBuy: Boolean,
    val volume: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val pending: Boolean,
    val openedAt: Long,
    val source: String,
) {
    val direction get() = if (isBuy) "BUY" else "SELL"

    fun toJson(): JSONObject = JSONObject()
        .put("id", positionId).put("sym", symbol).put("buy", isBuy).put("vol", volume)
        .put("sl", stopLoss).put("tp", takeProfit).put("pend", pending).put("at", openedAt).put("src", source)

    companion object {
        fun fromJson(o: JSONObject) = TrackedTrade(
            o.getString("id"), o.getString("sym"), o.getBoolean("buy"), o.getDouble("vol"),
            o.getDouble("sl"), o.getDouble("tp"), o.optBoolean("pend"), o.getLong("at"), o.optString("src"),
        )
    }
}

/** One deal from MetaApi's deal history (only the fields used here). */
data class Deal(
    val entryType: String,
    val price: Double,
    val profit: Double,
    val swap: Double,
    val commission: Double,
    val reason: String,
    val comment: String,
)

enum class CloseReason(val label: String) { TP("TP hit"), SL("SL hit"), OTHER("closed") }

data class TradeOutcome(val reason: CloseReason, val netProfit: Double, val closePrice: Double) {
    val isWin get() = netProfit > 0
}

object TradeResults {
    /**
     * Works out whether a position has closed, and how. Returns null while it is still open.
     * Uses the broker's close reason/comment when given, otherwise compares the close price with the trade's SL and TP.
     */
    fun classify(trade: TrackedTrade, deals: List<Deal>): TradeOutcome? {
        val exits = deals.filter { it.entryType.contains("OUT") }
        if (exits.isEmpty()) return null
        val net = deals.sumOf { it.profit + it.swap + it.commission }
        val last = exits.last()
        val hint = (last.reason + " " + last.comment).uppercase()
        val reason = when {
            hint.contains("REASON_TP") || hint.contains("[TP") -> CloseReason.TP
            hint.contains("REASON_SL") || hint.contains("[SL") -> CloseReason.SL
            else -> {
                val tolerance = max(abs(trade.takeProfit - trade.stopLoss) * 0.05, 1e-9)
                when {
                    abs(last.price - trade.takeProfit) <= tolerance -> CloseReason.TP
                    abs(last.price - trade.stopLoss) <= tolerance -> CloseReason.SL
                    else -> CloseReason.OTHER
                }
            }
        }
        return TradeOutcome(reason, Math.round(net * 100) / 100.0, last.price)
    }

    fun describe(trade: TrackedTrade, o: TradeOutcome, currency: String): Pair<String, String> {
        val money = (if (o.netProfit >= 0) "+" else "−") + fmt(abs(o.netProfit)) + if (currency.isNotBlank()) " $currency" else ""
        val kind = when {
            o.netProfit > 0 -> "win"
            o.netProfit < 0 -> "loss"
            else -> "breakeven"
        }
        val word = if (o.isWin) "WIN" else if (o.netProfit == 0.0) "BREAKEVEN" else "LOSS"
        val title = "$word: ${o.reason.label} · ${trade.symbol} ${trade.direction} ${fmt(trade.volume)} lots · $money"
        val detail = "Closed at ${fmt(o.closePrice)} (SL ${fmt(trade.stopLoss)}, TP ${fmt(trade.takeProfit)}). Signal from ${trade.source}."
        return kind to "$title\n$detail"
    }
}

/** Saves open tracked trades on the phone so results are still found after a restart. */
class TradeStore(context: Context) {
    private val file = File(context.filesDir, "open_trades.json")

    @Synchronized
    fun all(): List<TrackedTrade> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { TrackedTrade.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun add(t: TrackedTrade) = save(all().filter { it.positionId != t.positionId } + t)

    @Synchronized
    fun remove(positionId: String) = save(all().filter { it.positionId != positionId })

    private fun save(list: List<TrackedTrade>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
