package com.oluwafemi.tg2mt5

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The app's own record of what each signal channel posted. It is written the moment a message
 * arrives, so later deletions or edits by the channel admin don't erase it.
 */
data class ChannelSignal(
    val key: String,
    val chatId: Long,
    val chat: String,
    val postedAt: Long,          // seconds since 1970, as posted in Telegram
    val symbol: String,
    val isBuy: Boolean,
    val entry: Double?,
    val stopLoss: Double,
    val takeProfit: Double,      // first TP: what the price check uses
    val text: String,
    // What the channel said about it
    val claim: String? = null,   // "win" | "loss" | "unclear"
    val claimKey: String? = null,
    val claimAt: Long = 0,
    val claimText: String = "",
    val claimDeletedAt: Long = 0,
    val claimEditedAt: Long = 0,
    val signalDeletedAt: Long = 0,
    val signalEditedAt: Long = 0,
    // What real prices say (optional, needs MetaApi)
    val verified: String? = null, // "win" | "loss" | "both" | "expired"
    val verifiedAt: Long = 0,
    val verifyNote: String = "",
) {
    val direction get() = if (isBuy) "BUY" else "SELL"
    val claimVisible get() = claim != null && claimDeletedAt == 0L

    fun toJson(): JSONObject = JSONObject().apply {
        put("k", key); put("c", chatId); put("cn", chat); put("t", postedAt); put("s", symbol); put("b", isBuy)
        entry?.let { put("e", it) }; put("sl", stopLoss); put("tp", takeProfit); put("x", text)
        claim?.let { put("cl", it) }; claimKey?.let { put("ck", it) }; put("ca", claimAt); put("ct", claimText)
        put("cd", claimDeletedAt); put("ce", claimEditedAt); put("sd", signalDeletedAt); put("se", signalEditedAt)
        verified?.let { put("v", it) }; put("va", verifiedAt); put("vn", verifyNote)
    }

    companion object {
        fun fromJson(o: JSONObject) = ChannelSignal(
            o.getString("k"), o.getLong("c"), o.optString("cn"), o.getLong("t"), o.getString("s"), o.getBoolean("b"),
            if (o.has("e")) o.getDouble("e") else null, o.getDouble("sl"), o.getDouble("tp"), o.optString("x"),
            if (o.has("cl")) o.getString("cl") else null, if (o.has("ck")) o.getString("ck") else null,
            o.optLong("ca"), o.optString("ct"), o.optLong("cd"), o.optLong("ce"), o.optLong("sd"), o.optLong("se"),
            if (o.has("v")) o.getString("v") else null, o.optLong("va"), o.optString("vn"),
        )
    }
}

/** A result message the channel posted that could not be matched to a recorded signal. */
data class LooseResult(
    val key: String,
    val chatId: Long,
    val chat: String,
    val postedAt: Long,
    val win: Boolean?,
    val text: String,
    val deletedAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("k", key).put("c", chatId).put("cn", chat).put("t", postedAt)
        .put("w", if (win == null) "?" else if (win) "w" else "l").put("x", text).put("d", deletedAt)

    companion object {
        fun fromJson(o: JSONObject) = LooseResult(
            o.getString("k"), o.getLong("c"), o.optString("cn"), o.getLong("t"),
            when (o.optString("w")) { "w" -> true; "l" -> false; else -> null }, o.optString("x"), o.optLong("d"),
        )
    }
}

/** What happened as a result of a call, so the app can tell the user. */
sealed class LedgerEvent {
    data class ResultLinked(val signal: ChannelSignal, val win: Boolean?) : LedgerEvent()
    data class ResultLoose(val result: LooseResult) : LedgerEvent()
    data class ResultDeleted(val signal: ChannelSignal?, val win: Boolean?, val chat: String) : LedgerEvent()
    data class SignalDeleted(val signal: ChannelSignal) : LedgerEvent()
    data class SignalEdited(val signal: ChannelSignal, val newText: String) : LedgerEvent()
    data class ResultEdited(val signal: ChannelSignal, val oldClaim: String?, val newClaim: String?) : LedgerEvent()
}

/** Pure logic for the ledger, kept separate from storage so it can be unit-tested. */
object Ledger {
    private const val LINK_WINDOW_SEC = 3 * 24 * 3600L

    fun claimOf(win: Boolean?) = when (win) { true -> "win"; false -> "loss"; null -> "unclear" }

    /**
     * Finds which signal a result message belongs to: the one it replies to, otherwise the newest
     * signal in the same chat that has no result yet (preferring one whose symbol the message names).
     */
    fun findSignalFor(signals: List<ChannelSignal>, chatId: Long, replyToKey: String?, text: String, postedAt: Long): ChannelSignal? {
        replyToKey?.let { k -> signals.firstOrNull { it.key == k }?.let { return it } }
        val candidates = signals.filter {
            it.chatId == chatId && it.claim == null && it.postedAt <= postedAt && postedAt - it.postedAt <= LINK_WINDOW_SEC
        }.sortedByDescending { it.postedAt }
        if (candidates.isEmpty()) return null
        val upper = text.uppercase()
        val named = candidates.filter { s ->
            upper.contains(s.symbol) || (s.symbol == "XAUUSD" && upper.contains("GOLD")) || (s.symbol == "XAGUSD" && upper.contains("SILVER"))
        }
        return when {
            named.isNotEmpty() -> named.first()
            mentionsAnySymbol(upper) -> null // names a different symbol than any open signal
            else -> candidates.first()
        }
    }

    private fun mentionsAnySymbol(upper: String) =
        Regex("""\b(XAU|XAG|GOLD|SILVER|BTC|ETH|US30|NAS100|US100|SPX500|GER40|OIL|WTI)""").containsMatchIn(upper) ||
            Regex("""\b(EUR|GBP|USD|JPY|CHF|CAD|AUD|NZD)\s*/?\s*(EUR|GBP|USD|JPY|CHF|CAD|AUD|NZD)\b""").containsMatchIn(upper)

    /**
     * Decides from 1-minute candles (oldest first) whether price reached TP or SL first after the signal was posted.
     * Candle prices are bid prices, so this is a close approximation of what a trader would have got.
     */
    fun verify(s: ChannelSignal, candles: List<Candle>, nowSec: Long): Pair<String, String>? {
        val minuteStart = s.postedAt - (s.postedAt % 60)
        for (c in candles.sortedBy { it.time }) {
            if (c.time < minuteStart) continue
            val hitTp = if (s.isBuy) c.high >= s.takeProfit else c.low <= s.takeProfit
            val hitSl = if (s.isBuy) c.low <= s.stopLoss else c.high >= s.stopLoss
            val at = java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.US).format(java.util.Date(c.time * 1000))
            when {
                hitTp && hitSl -> return "both" to "TP and SL both touched in the minute of $at; can't tell which came first"
                hitTp -> return "win" to "Price reached TP ${fmt(s.takeProfit)} at $at"
                hitSl -> return "loss" to "Price reached SL ${fmt(s.stopLoss)} at $at"
            }
        }
        if (nowSec - s.postedAt > 7 * 24 * 3600L) return "expired" to "Neither TP nor SL reached within 7 days"
        return null
    }

    data class Stats(
        val signals: Int,
        val claimedWins: Int,
        val claimedLosses: Int,
        val visibleWins: Int,
        val visibleLosses: Int,
        val deletedLossResults: Int,
        val deletedWinResults: Int,
        val deletedSignals: Int,
        val editedSignals: Int,
        val noResultPosted: Int,
        val priceWins: Int,
        val priceLosses: Int,
        val priceOpen: Int,
        val silentLosses: Int,
        val mismatches: Int,
    ) {
        private fun rate(w: Int, l: Int) = if (w + l == 0) null else w * 100 / (w + l)
        val visibleRate get() = rate(visibleWins, visibleLosses)
        val claimedRate get() = rate(claimedWins, claimedLosses)
        val priceRate get() = rate(priceWins, priceLosses)
    }

    fun stats(signals: List<ChannelSignal>, loose: List<LooseResult>, sinceSec: Long, chatId: Long?): Stats {
        val s = signals.filter { it.postedAt >= sinceSec && (chatId == null || it.chatId == chatId) }
        val l = loose.filter { it.postedAt >= sinceSec && (chatId == null || it.chatId == chatId) }
        val claimedW = s.count { it.claim == "win" } + l.count { it.win == true }
        val claimedL = s.count { it.claim == "loss" } + l.count { it.win == false }
        val visW = s.count { it.claim == "win" && it.claimDeletedAt == 0L } + l.count { it.win == true && it.deletedAt == 0L }
        val visL = s.count { it.claim == "loss" && it.claimDeletedAt == 0L } + l.count { it.win == false && it.deletedAt == 0L }
        return Stats(
            signals = s.size,
            claimedWins = claimedW,
            claimedLosses = claimedL,
            visibleWins = visW,
            visibleLosses = visL,
            deletedLossResults = s.count { it.claim == "loss" && it.claimDeletedAt > 0 } + l.count { it.win == false && it.deletedAt > 0 },
            deletedWinResults = s.count { it.claim == "win" && it.claimDeletedAt > 0 } + l.count { it.win == true && it.deletedAt > 0 },
            deletedSignals = s.count { it.signalDeletedAt > 0 },
            editedSignals = s.count { it.signalEditedAt > 0 },
            noResultPosted = s.count { it.claim == null && it.verified in setOf("win", "loss") },
            priceWins = s.count { it.verified == "win" },
            priceLosses = s.count { it.verified == "loss" },
            priceOpen = s.count { it.verified == null },
            silentLosses = s.count { it.verified == "loss" && (it.claim == null || !it.claimVisible) },
            mismatches = s.count { it.verified == "loss" && it.claim == "win" },
        )
    }
}

data class Candle(val time: Long, val high: Double, val low: Double)

/** Stores the ledger on the phone and applies Telegram events to it. */
class ChannelLedger(context: Context) {
    private val file = File(context.filesDir, "channel_ledger.json")
    private val _signals = MutableStateFlow<List<ChannelSignal>>(emptyList())
    val signals: StateFlow<List<ChannelSignal>> = _signals
    private val _loose = MutableStateFlow<List<LooseResult>>(emptyList())
    val loose: StateFlow<List<LooseResult>> = _loose

    init {
        load()
    }

    private fun now() = System.currentTimeMillis() / 1000

    @Synchronized
    fun recordSignal(key: String, chatId: Long, chat: String, postedAt: Long, sig: Signal, text: String) {
        if (_signals.value.any { it.key == key }) return
        val s = ChannelSignal(key, chatId, chat, postedAt, sig.symbol, sig.isBuy, sig.entry, sig.stopLoss, sig.takeProfits.first(), text.take(500))
        _signals.value = (_signals.value + s).sortedByDescending { it.postedAt }.take(MAX)
        save()
    }

    @Synchronized
    fun recordResult(key: String, chatId: Long, chat: String, postedAt: Long, replyToKey: String?, win: Boolean?, text: String): LedgerEvent? {
        if (_signals.value.any { it.claimKey == key } || _loose.value.any { it.key == key }) return null
        val target = Ledger.findSignalFor(_signals.value, chatId, replyToKey, text, postedAt)
        if (target != null && (target.claim == null || replyToKey == target.key)) {
            val updated = target.copy(claim = Ledger.claimOf(win), claimKey = key, claimAt = postedAt, claimText = text.take(300))
            replace(updated)
            return LedgerEvent.ResultLinked(updated, win)
        }
        val r = LooseResult(key, chatId, chat, postedAt, win, text.take(300))
        _loose.value = (_loose.value + r).sortedByDescending { it.postedAt }.take(MAX)
        save()
        return LedgerEvent.ResultLoose(r)
    }

    /** Telegram reported these messages as deleted from the channel. */
    @Synchronized
    fun onDeleted(chatId: Long, messageIds: LongArray): List<LedgerEvent> {
        val events = mutableListOf<LedgerEvent>()
        val t = now()
        for (id in messageIds) {
            val key = "$chatId:$id"
            _signals.value.firstOrNull { it.key == key && it.signalDeletedAt == 0L }?.let {
                val u = it.copy(signalDeletedAt = t); replace(u); events += LedgerEvent.SignalDeleted(u)
            }
            _signals.value.firstOrNull { it.claimKey == key && it.claimDeletedAt == 0L }?.let {
                val u = it.copy(claimDeletedAt = t); replace(u)
                events += LedgerEvent.ResultDeleted(u, if (u.claim == "win") true else if (u.claim == "loss") false else null, u.chat)
            }
            _loose.value.firstOrNull { it.key == key && it.deletedAt == 0L }?.let { r ->
                _loose.value = _loose.value.map { if (it.key == key) it.copy(deletedAt = t) else it }
                events += LedgerEvent.ResultDeleted(null, r.win, r.chat)
            }
        }
        if (events.isNotEmpty()) save()
        return events
    }

    /** A message in a watched chat was edited. The original signal levels are kept; the edit is flagged. */
    @Synchronized
    fun onEdited(chatId: Long, messageId: Long, newText: String): LedgerEvent? {
        val key = "$chatId:$messageId"
        _signals.value.firstOrNull { it.key == key }?.let { s ->
            if (newText.trim() == s.text.trim()) return null
            val u = s.copy(signalEditedAt = now()); replace(u)
            return LedgerEvent.SignalEdited(u, newText)
        }
        _signals.value.firstOrNull { it.claimKey == key }?.let { s ->
            val newClaim = Ledger.claimOf(SignalParser.outcomeOf(newText))
            val u = s.copy(claimEditedAt = now(), claim = newClaim, claimText = newText.take(300)); replace(u)
            return LedgerEvent.ResultEdited(u, s.claim, newClaim)
        }
        return null
    }

    @Synchronized
    fun setVerified(key: String, result: String, note: String) {
        _signals.value.firstOrNull { it.key == key }?.let { replace(it.copy(verified = result, verifiedAt = now(), verifyNote = note)) }
    }

    @Synchronized
    fun clear() {
        _signals.value = emptyList(); _loose.value = emptyList(); save()
    }

    private fun replace(s: ChannelSignal) {
        _signals.value = _signals.value.map { if (it.key == s.key) s else it }
        save()
    }

    private fun load() {
        try {
            val o = JSONObject(file.readText())
            val a = o.optJSONArray("s") ?: JSONArray()
            _signals.value = (0 until a.length()).map { ChannelSignal.fromJson(a.getJSONObject(it)) }
            val b = o.optJSONArray("r") ?: JSONArray()
            _loose.value = (0 until b.length()).map { LooseResult.fromJson(b.getJSONObject(it)) }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        try {
            val a = JSONArray(); _signals.value.forEach { a.put(it.toJson()) }
            val b = JSONArray(); _loose.value.forEach { b.put(it.toJson()) }
            file.writeText(JSONObject().put("s", a).put("r", b).toString())
        } catch (_: Exception) {
        }
    }

    companion object {
        const val MAX = 2000
    }
}
