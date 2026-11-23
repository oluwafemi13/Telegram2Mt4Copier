package com.oluwafemi.tg2mt5

/** The order type a signal asks for. AUTO = a price was given but no order type word. */
enum class OrderKind(val label: String) { MARKET("market"), LIMIT("limit"), STOP("stop"), AUTO("") }

/** A trade signal read from a Telegram message. Prices are as written in the signal. */
data class Signal(
    val symbol: String,
    val isBuy: Boolean,
    val entry: Double?,          // null when the signal says "buy now" without a price
    val stopLoss: Double,
    val takeProfits: List<Double>,
    val kind: OrderKind = if (entry == null) OrderKind.MARKET else OrderKind.AUTO,
) {
    val direction: String get() = if (isBuy) "BUY" else "SELL"
    /** e.g. "SELL LIMIT", "BUY", "BUY STOP" */
    val orderName: String get() = when (kind) {
        OrderKind.LIMIT -> "$direction LIMIT"
        OrderKind.STOP -> "$direction STOP"
        else -> direction
    }
    fun summary(): String {
        val tps = takeProfits.joinToString(" / ") { fmt(it) }
        return "$symbol $orderName ${entry?.let { "@ ${fmt(it)}" } ?: "now"}  TP $tps  SL ${fmt(stopLoss)}"
    }
}

sealed class ParseResult {
    data class Trade(val signal: Signal) : ParseResult()
    /** A message reporting that an earlier signal hit TP or SL. Logged only; MT5 closes those trades itself. */
    data class Outcome(val text: String, val win: Boolean?) : ParseResult()
    data class Invalid(val reason: String) : ParseResult()
    object NotASignal : ParseResult()
}

fun fmt(v: Double): String =
    if (v == Math.floor(v) && Math.abs(v) < 1e12) v.toLong().toString()
    else String.format(java.util.Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')

object SignalParser {

    private val aliases = linkedMapOf(
        "GOLD" to "XAUUSD", "XAUUSD" to "XAUUSD", "XAU/USD" to "XAUUSD",
        "SILVER" to "XAGUSD", "XAGUSD" to "XAGUSD", "XAG/USD" to "XAGUSD",
        "BTCUSD" to "BTCUSD", "BITCOIN" to "BTCUSD", "ETHUSD" to "ETHUSD",
        "US30" to "US30", "DJ30" to "US30", "DOW" to "US30",
        "NAS100" to "NAS100", "US100" to "NAS100", "NASDAQ" to "NAS100",
        "SPX500" to "SPX500", "US500" to "SPX500", "GER40" to "GER40", "DE40" to "GER40",
        "USOIL" to "USOIL", "WTI" to "USOIL", "UKOIL" to "UKOIL",
    )
    private val currencies = setOf("USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "SGD", "HKD", "ZAR", "MXN", "NOK", "SEK", "TRY", "PLN")

    private const val NUM = "([0-9]+(?:[.,][0-9]+)?)"
    private val directionRe = Regex("""\b(BUY|SELL)\b((?:\s*(?:LIMIT|STOP|NOW|NEAR|ZONE|AT|@|MARKET))*)\s*[:@=\-]?\s*$NUM?""")
    private val entryRe = Regex("""@\s*[:=\-]?\s*$NUM""")

    /**
     * Rewrites a channel's own words into the standard words the parser reads, in one pass so a
     * replaced word is never replaced again. E.g. with SL = "stoploss, s/l" and Buy stop = "buy above",
     * "Buy above 4050, s/l 4040" becomes "BUY STOP 4050, SL 4040".
     */
    fun normalize(upperText: String, w: SignalWords): String {
        val pairs = mutableListOf<Pair<String, String>>()
        fun add(list: String, canon: String) = list.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.forEach { pairs += it to canon }
        add(w.buyLimit, "BUY LIMIT"); add(w.sellLimit, "SELL LIMIT"); add(w.buyStop, "BUY STOP"); add(w.sellStop, "SELL STOP")
        add(w.stopLoss, "SL"); add(w.takeProfit, "TP"); add(w.entry, "@"); add(w.market, "NOW"); add(w.buy, "BUY"); add(w.sell, "SELL")
        if (pairs.isEmpty()) return upperText
        val byPhrase = LinkedHashMap<String, String>()
        pairs.sortedByDescending { it.first.length }.forEach { if (it.first !in byPhrase) byPhrase[it.first] = it.second }
        val alternation = byPhrase.keys.joinToString("|") { phrase ->
            phrase.split(Regex("\\s+")).joinToString("""\s*""") { Regex.escape(it) }
        }
        // A digit may follow (TP1, TARGET2), a letter may not (so "BUY" doesn't match "BUYER").
        val re = Regex("""(?<![A-Z0-9])(?:$alternation)(?![A-Z])""")
        return re.replace(upperText) { m ->
            val key = m.value.replace(Regex("\\s+"), " ")
            " " + (byPhrase[key] ?: byPhrase.entries.firstOrNull { it.key.replace(" ", "") == key.replace(" ", "") }?.value ?: m.value) + " "
        }
    }
    private val tpRe = Regex("""\b(?:TP|TAKE\s*PROFIT)\s*(?:[0-9](?![0-9.,]))?\s*[:@=\-]?\s*$NUM""")
    private val slRe = Regex("""\b(?:SL|STOP\s*LOSS)\s*[:@=\-]?\s*$NUM""")
    private val outcomeRe = Regex("""\b(HIT|HITTED|SMASHED|CLOSED|PIPS|BOOM|RUNNING|SECURED|STOPPED|STOP OUT|STOPPED OUT|TRIGGERED|LOSS|LOST|PROFIT|BREAKEVEN|BE)\b|✅|❌|🎯|💰|🔴|🛑|⛔|💔|😔|😞""")
    private val lossRe = Regex("""\b(SL|STOP\s*LOSS)\s*(WAS\s*)?(HIT|HITTED|TRIGGERED|TAKEN|REACHED)\b|\bSTOPPED\s*OUT\b|\bSTOP\s*OUT\b|\b(LOSS|LOST)\b|-\s*[0-9]+\s*PIPS""")
    private val winRe = Regex("""\b(TP|TAKE\s*PROFIT)\s*[0-9]?\s*(WAS\s*)?(HIT|HITTED|SMASHED|REACHED|DONE|SECURED)\b|\bPROFIT\b|\+\s*[0-9]+\s*PIPS""")
    private val lossEmoji = listOf("❌", "🔴", "🛑", "⛔", "💔", "😔", "😞")
    private val winEmoji = listOf("✅", "🎯", "💰", "🟢", "🔥")

    /** true = the message reports a win (TP hit), false = a loss (SL hit), null = can't tell. */
    fun outcomeOf(raw: String): Boolean? {
        val t = raw.uppercase()
        val loss = lossRe.containsMatchIn(t) || lossEmoji.any { raw.contains(it) }
        val win = winRe.containsMatchIn(t) || winEmoji.any { raw.contains(it) }
        return when {
            loss && !win -> false
            win && !loss -> true
            loss && win -> !lossRe.containsMatchIn(t) // words beat emojis when both appear
            else -> null
        }
    }

    private val resultWordRe = Regex("""\b(TP|SL|TAKE PROFIT|STOP LOSS|PIPS|LOSS|LOST|PROFIT|STOPPED|BREAKEVEN)\b""")

    fun parse(raw: String, words: SignalWords = SignalWords.DEFAULT): ParseResult {
        // Upper-case and drop emojis/other symbols, but keep the original for the outcome check.
        val cleaned = raw.uppercase()
            .replace(Regex("""[^\x20-\x7E\n]"""), " ")
            .replace(Regex("""[ \t]+"""), " ")
        val text = normalize(cleaned, words).replace(Regex("""[ \t]+"""), " ")

        // A message can say BUY/SELL more than once, e.g. "Gold Buy Setup ... Buy Stop: 3504.5".
        // Prefer the mention that carries an order type (limit/stop), then one with a price, then the first.
        val dirs = directionRe.findAll(text).toList()
        val dir = dirs.firstOrNull { it.groupValues[2].contains("LIMIT") || it.groupValues[2].contains("STOP") }
            ?: dirs.firstOrNull { it.groupValues[3].isNotEmpty() }
            ?: dirs.firstOrNull()
        val tps = tpRe.findAll(text).map { num(it.groupValues[1]) }.toList()
        val sl = slRe.find(text)?.let { num(it.groupValues[1]) }

        if (dir == null || sl == null || tps.isEmpty()) {
            val looksLikeOutcome = outcomeRe.containsMatchIn(raw.uppercase()) &&
                (resultWordRe.containsMatchIn(text) || (lossEmoji + winEmoji).any { raw.contains(it) })
            if (looksLikeOutcome) return ParseResult.Outcome(raw.trim(), outcomeOf(raw))
            if (dir != null && (sl == null || tps.isEmpty())) {
                return ParseResult.Invalid("Signal has no ${if (sl == null) "SL" else "TP"}")
            }
            return ParseResult.NotASignal
        }

        val symbol = findSymbol(text) ?: return ParseResult.Invalid("Could not find the symbol")
        val isBuy = dir.groupValues[1] == "BUY"
        val entry = dir.groupValues[3].takeIf { it.isNotEmpty() }?.let { num(it) }
            ?: entryRe.find(text)?.let { num(it.groupValues[1]) }
        val modifiers = dir.groupValues[2]
        val kind = when {
            modifiers.contains("LIMIT") -> OrderKind.LIMIT
            modifiers.contains("STOP") -> OrderKind.STOP
            modifiers.contains("NOW") || modifiers.contains("MARKET") || entry == null -> OrderKind.MARKET
            else -> OrderKind.AUTO
        }
        if ((kind == OrderKind.LIMIT || kind == OrderKind.STOP) && entry == null) return ParseResult.Invalid("$kind order has no entry price")

        // Basic sanity: SL must be on the losing side, TPs on the winning side.
        val ref = entry ?: (tps.first() + sl) / 2
        if (isBuy && !(sl < ref && tps.all { it > ref })) return ParseResult.Invalid("BUY levels don't make sense (SL must be below entry, TP above)")
        if (!isBuy && !(sl > ref && tps.all { it < ref })) return ParseResult.Invalid("SELL levels don't make sense (SL must be above entry, TP below)")

        return ParseResult.Trade(Signal(symbol, isBuy, entry, sl, tps, kind))
    }

    private fun num(s: String) = s.replace(',', '.').toDouble()

    private fun findSymbol(text: String): String? {
        for ((alias, symbol) in aliases) {
            if (Regex("""(^|[^A-Z])${Regex.escape(alias)}([^A-Z]|$)""").containsMatchIn(text)) return symbol
        }
        for (m in Regex("""\b([A-Z]{3})\s*/?\s*([A-Z]{3})\b""").findAll(text)) {
            val (a, b) = m.destructured
            if (a in currencies && b in currencies && a != b) return a + b
        }
        return null
    }
}
