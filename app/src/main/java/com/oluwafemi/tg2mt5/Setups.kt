package com.oluwafemi.tg2mt5

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * How one Telegram channel writes its signals. Each field is a comma-separated list of words or phrases
 * that mean the same thing in that channel, e.g. SL = "sl, stop loss, s/l".
 */
data class SignalWords(
    val buy: String = "buy",
    val sell: String = "sell",
    val buyLimit: String = "buy limit",
    val sellLimit: String = "sell limit",
    val buyStop: String = "buy stop",
    val sellStop: String = "sell stop",
    val market: String = "now, market, market execution, instant, cmp",
    val entry: String = "entry, entry price, enter, open price",
    val stopLoss: String = "sl, s/l, stop loss, stoploss",
    val takeProfit: String = "tp, t/p, take profit, takeprofit, target",
) {
    fun toJson(): JSONObject = JSONObject().put("buy", buy).put("sell", sell).put("bl", buyLimit).put("sl_", sellLimit)
        .put("bs", buyStop).put("ss", sellStop).put("mk", market).put("en", entry).put("sl", stopLoss).put("tp", takeProfit)

    companion object {
        val DEFAULT = SignalWords()
        fun fromJson(o: JSONObject?): SignalWords {
            if (o == null) return DEFAULT
            val d = DEFAULT
            return SignalWords(
                o.optString("buy", d.buy), o.optString("sell", d.sell), o.optString("bl", d.buyLimit), o.optString("sl_", d.sellLimit),
                o.optString("bs", d.buyStop), o.optString("ss", d.sellStop), o.optString("mk", d.market), o.optString("en", d.entry),
                o.optString("sl", d.stopLoss), o.optString("tp", d.takeProfit),
            )
        }
    }
}

/** A Telegram channel or group that posts signals. */
data class Channel(
    val chatId: Long,
    val title: String,
    val alerts: Boolean = true,          // "Alert me about new signals"
    val words: SignalWords = SignalWords.DEFAULT,
) {
    fun toJson(): JSONObject = JSONObject().put("id", chatId).put("t", title).put("a", alerts).put("w", words.toJson())

    companion object {
        fun fromJson(o: JSONObject) = Channel(o.getLong("id"), o.optString("t"), o.optBoolean("a", true), SignalWords.fromJson(o.optJSONObject("w")))
    }
}

/** What MetaApi last said about an account. */
data class MetaStatus(
    val state: String = "",              // DEPLOYED, UNDEPLOYED, DEPLOYING, ...
    val connection: String = "",         // CONNECTED, DISCONNECTED, DISCONNECTED_FROM_BROKER
    val region: String = "",
    val broker: String = "",
    val login: String = "",
    val server: String = "",
    val balance: Double? = null,
    val equity: Double? = null,
    val currency: String = "",
    val error: String = "",
    val checkedAt: Long = 0,
) {
    /** One plain-language status shown on the account card. */
    val level: Level get() = when {
        checkedAt == 0L -> Level.UNKNOWN
        error.isNotBlank() -> Level.ERROR
        state == "DEPLOYED" && connection == "CONNECTED" -> Level.RUNNING
        state == "DEPLOYED" && connection == "DISCONNECTED_FROM_BROKER" -> Level.BROKER
        state == "DEPLOYED" || state == "DEPLOYING" || state == "CREATED" -> Level.STARTING
        state == "UNDEPLOYED" || state == "UNDEPLOYING" -> Level.PAUSED
        else -> Level.ERROR
    }

    enum class Level(val label: String) {
        RUNNING("MetaApi linked · running"),
        STARTING("MetaApi linked · connecting"),
        BROKER("Linked · broker disconnected"),
        PAUSED("Linked · paused (undeployed)"),
        ERROR("Not linked"),
        UNKNOWN("Not checked yet"),
    }

    fun toJson(): JSONObject = JSONObject().put("st", state).put("cn", connection).put("rg", region).put("br", broker).put("lg", login)
        .put("sv", server).put("ba", balance ?: JSONObject.NULL).put("eq", equity ?: JSONObject.NULL).put("cu", currency)
        .put("er", error).put("at", checkedAt)

    companion object {
        fun fromJson(o: JSONObject?): MetaStatus = if (o == null) MetaStatus() else MetaStatus(
            o.optString("st"), o.optString("cn"), o.optString("rg"), o.optString("br"), o.optString("lg"), o.optString("sv"),
            if (o.isNull("ba")) null else o.optDouble("ba"), if (o.isNull("eq")) null else o.optDouble("eq"),
            o.optString("cu"), o.optString("er"), o.optLong("at"),
        )
    }
}

/** One MT5 account connected through MetaApi, with its own trading settings and the channel it copies. */
data class Mt5Account(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "My MT5 account",
    val metaAccountId: String = "",
    val tokenOverride: String = "",      // empty = use the token from Setup › MetaApi
    val region: String = "",             // filled in automatically from MetaApi
    val channelId: Long? = null,         // the one channel this account copies
    val copyOn: Boolean = false,         // "Copy trades"
    val accountType: String = "standard", // standard | cent
    val centFactor: Double = 0.01,
    val lotMode: String = "fixed",       // fixed | risk
    val fixedLot: Double = 0.01,
    val riskPercent: Double = 1.0,
    val maxLot: Double = 0.0,
    val useMinLot: Boolean = true,
    val symbolPrefix: String = "",
    val symbolSuffix: String = "",
    val symbolMap: String = "",
    val orderRule: String = "follow",    // follow (what the signal says) | market (always market)
    val tpChoice: String = "first",      // first | last
    val maxDistancePips: Double = 0.0,  // skip if price moved more than this many market-standard pips
    val maxAgeMinutes: Double = 3.0,
    val status: MetaStatus = MetaStatus(),
) {
    val isCent get() = accountType == "cent"

    fun token(globalToken: String) = tokenOverride.ifBlank { globalToken }

    /** Turns a signal symbol (e.g. XAUUSD) into this broker's name (e.g. XAUUSDm). */
    fun brokerSymbol(symbol: String): String {
        for (line in symbolMap.lines()) {
            val parts = line.split("=", ":").map { it.trim() }
            if (parts.size == 2 && parts[0].equals(symbol, ignoreCase = true) && parts[1].isNotEmpty()) return parts[1]
        }
        return symbolPrefix + symbol + symbolSuffix
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("n", name); put("ma", metaAccountId); put("to", tokenOverride); put("rg", region)
        put("ch", channelId ?: JSONObject.NULL); put("co", copyOn); put("at", accountType); put("cf", centFactor)
        put("lm", lotMode); put("fl", fixedLot); put("rp", riskPercent); put("ml", maxLot); put("um", useMinLot)
        put("sp", symbolPrefix); put("ss", symbolSuffix); put("sm", symbolMap); put("or", orderRule); put("tc", tpChoice)
        put("mdp", maxDistancePips); put("mx", maxAgeMinutes); put("st", status.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): Mt5Account {
            val d = Mt5Account(id = "")
            return Mt5Account(
                o.getString("id"), o.optString("n", d.name), o.optString("ma"), o.optString("to"), o.optString("rg"),
                if (o.isNull("ch") || !o.has("ch")) null else o.getLong("ch"), o.optBoolean("co"), o.optString("at", d.accountType),
                o.optDouble("cf", d.centFactor), o.optString("lm", d.lotMode), o.optDouble("fl", d.fixedLot), o.optDouble("rp", d.riskPercent),
                o.optDouble("ml", d.maxLot), o.optBoolean("um", d.useMinLot), o.optString("sp"), o.optString("ss"), o.optString("sm"),
                o.optString("or", d.orderRule), o.optString("tc", d.tpChoice), if (o.has("mdp")) o.optDouble("mdp") else Pips.fromGoldPrice(o.optDouble("md", 0.0)), o.optDouble("mx", d.maxAgeMinutes),
                MetaStatus.fromJson(o.optJSONObject("st")),
            )
        }
    }
}

/** Market-standard pip sizes, so distances can be set in pips. */
object Pips {
    /** Gold 0.10, silver 0.01, JPY pairs 0.01, other forex 0.0001, oil 0.01, indices and crypto 1.0. */
    fun size(symbol: String): Double {
        val s = symbol.uppercase()
        return when {
            s.startsWith("XAU") || s.contains("GOLD") -> 0.1
            s.startsWith("XAG") || s.contains("SILVER") -> 0.01
            s.contains("OIL") || s.startsWith("WTI") || s.startsWith("BRENT") -> 0.01
            s.startsWith("BTC") || s.startsWith("ETH") -> 1.0
            Regex("""^(US30|NAS100|US100|SPX500|US500|GER40|DE40|UK100|JP225)""").containsMatchIn(s) -> 1.0
            s.length >= 6 && s.substring(3, 6) == "JPY" -> 0.01
            else -> 0.0001
        }
    }

    fun toPrice(pips: Double, symbol: String) = pips * size(symbol)
    fun between(a: Double, b: Double, symbol: String) = Math.abs(a - b) / size(symbol)

    /** Version 1.x and early 2.0 stored this distance in gold dollars; convert it to pips. */
    fun fromGoldPrice(price: Double) = if (price > 0) Math.round(price / 0.1 * 10) / 10.0 else 0.0
}

/** The rules for which channel an account may link to. Kept pure so it can be unit-tested. */
object LinkRules {
    /**
     * Flexible: a channel can feed any number of accounts (each account follows one channel).
     * Pairs: a channel can be linked to only one account.
     * Returns null if the link is allowed, otherwise the reason it isn't.
     */
    fun canLink(accounts: List<Mt5Account>, accountId: String, channelId: Long, strictPairs: Boolean): String? {
        if (!strictPairs) return null
        val other = accounts.firstOrNull { it.channelId == channelId && it.id != accountId } ?: return null
        return "This channel is already paired with \"${other.name}\". In the Pairs app each channel has exactly one MT5 account."
    }
}

/** Saves channels and MT5 accounts on the phone. */
class SetupStore(context: Context) {
    private val file = File(context.filesDir, "setups.json")
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels
    private val _accounts = MutableStateFlow<List<Mt5Account>>(emptyList())
    val accounts: StateFlow<List<Mt5Account>> = _accounts

    val exists get() = file.exists()

    init {
        load()
    }

    fun channel(chatId: Long) = _channels.value.firstOrNull { it.chatId == chatId }
    fun account(id: String) = _accounts.value.firstOrNull { it.id == id }
    fun channelIds(): Set<Long> = _channels.value.map { it.chatId }.toSet()
    fun accountsFor(chatId: Long) = _accounts.value.filter { it.channelId == chatId }

    @Synchronized
    fun saveChannel(c: Channel) {
        _channels.value = if (_channels.value.any { it.chatId == c.chatId }) _channels.value.map { if (it.chatId == c.chatId) c else it } else _channels.value + c
        save()
    }

    /** Removes a channel; accounts that copied it become unlinked (not deleted). */
    @Synchronized
    fun deleteChannel(chatId: Long) {
        _channels.value = _channels.value.filter { it.chatId != chatId }
        _accounts.value = _accounts.value.map { if (it.channelId == chatId) it.copy(channelId = null, copyOn = false) else it }
        save()
    }

    @Synchronized
    fun saveAccount(a: Mt5Account) {
        _accounts.value = if (_accounts.value.any { it.id == a.id }) _accounts.value.map { if (it.id == a.id) a else it } else _accounts.value + a
        save()
    }

    @Synchronized
    fun deleteAccount(id: String) {
        _accounts.value = _accounts.value.filter { it.id != id }
        save()
    }

    @Synchronized
    fun updateStatus(id: String, status: MetaStatus) {
        _accounts.value = _accounts.value.map {
            if (it.id == id) it.copy(status = status, region = status.region.ifBlank { it.region }) else it
        }
        save()
    }

    /** Fills in channel names once Telegram has loaded the chat list. */
    @Synchronized
    fun updateTitles(titles: Map<Long, String>) {
        var changed = false
        _channels.value = _channels.value.map { c ->
            val t = titles[c.chatId]
            if (t != null && t != c.title) { changed = true; c.copy(title = t) } else c
        }
        if (changed) save()
    }

    @Synchronized
    fun replaceAll(channels: List<Channel>, accounts: List<Mt5Account>) {
        _channels.value = channels; _accounts.value = accounts; save()
    }

    fun toJson(): JSONObject {
        val c = JSONArray(); _channels.value.forEach { c.put(it.toJson()) }
        val a = JSONArray(); _accounts.value.forEach { a.put(it.toJson()) }
        return JSONObject().put("version", 2).put("channels", c).put("accounts", a)
    }

    fun loadJson(o: JSONObject) {
        val c = o.optJSONArray("channels") ?: JSONArray()
        val a = o.optJSONArray("accounts") ?: JSONArray()
        _channels.value = (0 until c.length()).map { Channel.fromJson(c.getJSONObject(it)) }
        _accounts.value = (0 until a.length()).map { Mt5Account.fromJson(a.getJSONObject(it)) }
    }

    private fun load() {
        try {
            loadJson(JSONObject(file.readText()))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun save() {
        try {
            file.writeText(toJson().toString())
        } catch (_: Exception) {
        }
    }
}

/** Moves the settings of the single-account app (version 1.x) into the new channels and accounts, once. */
object Migration {
    data class OldSettings(
        val channelIds: Set<Long>, val mode: String, val metaAccountId: String, val metaRegion: String,
        val accountType: String, val centFactor: Double, val lotMode: String, val fixedLot: Double, val riskPercent: Double,
        val maxLot: Double, val useMinLot: Boolean, val symbolPrefix: String, val symbolSuffix: String, val symbolMap: String,
        val orderType: String, val tpChoice: String, val maxDistance: Double, val maxAgeMinutes: Double,
    )

    fun migrate(old: OldSettings, titles: Map<Long, String>): Pair<List<Channel>, List<Mt5Account>> {
        val wasAuto = old.mode == "auto"
        val channels = old.channelIds.sorted().map { Channel(it, titles[it] ?: "Channel $it", alerts = !wasAuto) }
        val hadAccount = old.metaAccountId.isNotBlank() || wasAuto
        val accounts = if (!hadAccount) emptyList() else listOf(
            Mt5Account(
                name = "My MT5 account", metaAccountId = old.metaAccountId, region = old.metaRegion,
                channelId = channels.firstOrNull()?.chatId, copyOn = wasAuto && channels.isNotEmpty(),
                accountType = old.accountType, centFactor = old.centFactor, lotMode = old.lotMode, fixedLot = old.fixedLot,
                riskPercent = old.riskPercent, maxLot = old.maxLot, useMinLot = old.useMinLot, symbolPrefix = old.symbolPrefix,
                symbolSuffix = old.symbolSuffix, symbolMap = old.symbolMap,
                orderRule = if (old.orderType == "pending") "follow" else "market",
                tpChoice = old.tpChoice, maxDistancePips = Pips.fromGoldPrice(old.maxDistance), maxAgeMinutes = old.maxAgeMinutes,
            ),
        )
        return channels to accounts
    }
}
