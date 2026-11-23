package com.oluwafemi.tg2mt5

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Everything decided about a signal for one account before it is sent to MT5. */
data class TradePlan(
    val signal: Signal,
    val account: Mt5Account,
    val brokerSymbol: String,
    val actionType: String,
    val volume: Double,
    val openPrice: Double?,
    val stopLoss: Double,
    val takeProfit: Double,
    val marketPrice: Double,
    val digits: Int,
    val lotNote: String,
    val orderNote: String = "",
) {
    /** e.g. "SELL LIMIT XAUUSDm 0.01 lots @ 4054.5  SL 4064.5  TP 4044.5" */
    fun describe() = "${actionType.removePrefix("ORDER_TYPE_").replace('_', ' ')} $brokerSymbol ${fmt(volume)} lots" +
        (openPrice?.let { " @ ${fmt(it)}" } ?: " (market ~${fmt(marketPrice)})") +
        "  SL ${fmt(stopLoss)}  TP ${fmt(takeProfit)}"
}

class SkipException(message: String) : Exception(message)

/** Which MT5 order to send for a signal. Pure logic, unit-tested. */
object Orders {
    data class Decision(val actionType: String, val openPrice: Double?, val note: String = "")

    /**
     * rule "follow": market signals → market order; LIMIT → limit; STOP → stop; a bare price → market.
     * rule "market": always a market order.
     * A limit whose price has already been reached is sent as a market order (you get the same or a better price).
     * A stop whose price has already been passed is skipped, unless it is still within maxDistance.
     */
    fun decide(s: Signal, rule: String, bid: Double, ask: Double, maxDistance: Double): Decision {
        val market = if (s.isBuy) ask else bid
        val mkt = Decision(if (s.isBuy) "ORDER_TYPE_BUY" else "ORDER_TYPE_SELL", null)
        if (rule == "market" || s.entry == null) return mkt
        val e = s.entry
        return when (s.kind) {
            OrderKind.LIMIT -> {
                val reached = if (s.isBuy) ask <= e else bid >= e
                if (reached) mkt.copy(note = "Limit price ${fmt(e)} already reached (price ${fmt(market)}), so sent as a market order")
                else Decision(if (s.isBuy) "ORDER_TYPE_BUY_LIMIT" else "ORDER_TYPE_SELL_LIMIT", e)
            }
            OrderKind.STOP -> {
                val passed = if (s.isBuy) ask >= e else bid <= e
                if (!passed) Decision(if (s.isBuy) "ORDER_TYPE_BUY_STOP" else "ORDER_TYPE_SELL_STOP", e)
                else if (maxDistance > 0 && abs(market - e) <= maxDistance) mkt.copy(note = "Stop price ${fmt(e)} already passed but within your pip limit, so sent as a market order")
                else throw SkipException("${s.orderName} price ${fmt(e)} was already passed (price ${fmt(market)})")
            }
            else -> mkt
        }
    }
}

class SignalProcessor(private val app: App) {
    private val prefs get() = app.prefs
    private val log get() = app.log
    private val store get() = app.setups

    /**
     * Called for every new message in a watched Telegram chat.
     * replyToKey is set when the message is a reply to an earlier message (e.g. a "TP hit" reply to the signal).
     */
    suspend fun onMessage(
        text: String, source: String, messageKey: String, ageSeconds: Long, replyToKey: String? = null,
        chatId: Long = 0, postedAt: Long = System.currentTimeMillis() / 1000,
    ) {
        val channel = store.channel(chatId) ?: return
        val repliesToSignal = replyToKey != null && (prefs.wasProcessed(replyToKey) || app.ledger.signals.value.any { it.key == replyToKey })
        when (val r = SignalParser.parse(text, channel.words)) {
            is ParseResult.NotASignal -> {
                // A reply to one of the channel's signals is that signal's result, whatever words it uses.
                if (repliesToSignal) channelResult(SignalParser.outcomeOf(text), text, source, messageKey, chatId, postedAt, replyToKey)
            }
            is ParseResult.Outcome -> channelResult(r.win, r.text, source, messageKey, chatId, postedAt, replyToKey)
            is ParseResult.Invalid -> {
                if (repliesToSignal) channelResult(SignalParser.outcomeOf(text), text, source, messageKey, chatId, postedAt, replyToKey)
                else log.add("error", "Unreadable signal from $source: ${r.reason}", "${text.take(300)}\nTip: set how this channel writes its signals in Overview › channel › Signal wording.")
            }
            is ParseResult.Trade -> {
                // Every signal goes into the channel record, even old ones or ones not traded.
                app.ledger.recordSignal(messageKey, chatId, source, postedAt, r.signal, text)
                if (prefs.alreadyProcessed(messageKey)) return
                if (!prefs.copierEnabled) return // master switch off: record only
                val linked = store.accountsFor(chatId)
                log.add("signal", "Signal from $source", r.signal.summary())
                if (channel.alerts && ageSeconds <= ALERT_MAX_AGE_SEC) alert(r.signal, source, linked.firstOrNull())
                for (acc in linked.filter { it.copyOn }) {
                    val maxAge = (acc.maxAgeMinutes * 60).toLong()
                    if (maxAge > 0 && ageSeconds > maxAge) {
                        log.add("skipped", "SKIPPED: old signal (${ageSeconds / 60} min old) · ${r.signal.symbol} ${r.signal.orderName} · ${acc.name}", r.signal.summary())
                        continue
                    }
                    autoTrade(r.signal, source, acc)
                }
            }
        }
    }

    /** A result message from the channel: record it, log it, and tell the user straight away. */
    private fun channelResult(win: Boolean?, text: String, source: String, key: String, chatId: Long, postedAt: Long, replyToKey: String?) {
        val ev = app.ledger.recordResult(key, chatId, source, postedAt, replyToKey, win, text) ?: return
        val label = when (win) {
            true -> "Channel result: TP hit (win)"
            false -> "Channel result: SL hit (loss)"
            null -> "Channel result"
        }
        val about = (ev as? LedgerEvent.ResultLinked)?.signal?.let { " for ${it.symbol} ${it.direction}" } ?: ""
        log.add(if (win == false) "outcome_loss" else if (win == true) "outcome_win" else "outcome", "$label$about · $source", text.trim().take(200))
        if (prefs.notifyChannelResults && prefs.copierEnabled) Notifier.signal(app, "$label$about", "${text.trim().take(150)}\nFrom $source")
    }

    /** Messages deleted or edited in a watched chat: flag anything that was in the channel record. */
    fun onDeleted(chatId: Long, ids: LongArray) {
        for (ev in app.ledger.onDeleted(chatId, ids)) {
            when (ev) {
                is LedgerEvent.ResultDeleted -> {
                    val what = when (ev.win) { false -> "LOSS result"; true -> "win result"; null -> "result" }
                    val about = ev.signal?.let { " for ${it.symbol} ${it.direction}" } ?: ""
                    log.add("deleted", "DELETED: channel removed a $what$about · ${ev.chat}", ev.signal?.claimText ?: "")
                    if (ev.win == false || prefs.notifyChannelResults) Notifier.signal(app, "⚠ ${ev.chat} deleted a $what$about", "The app kept its own record. See the Results tab.")
                }
                is LedgerEvent.SignalDeleted -> {
                    log.add("deleted", "DELETED: channel removed signal ${ev.signal.symbol} ${ev.signal.direction} · ${ev.signal.chat}", ev.signal.text.take(200))
                    Notifier.signal(app, "⚠ ${ev.signal.chat} deleted a signal", "${ev.signal.symbol} ${ev.signal.direction}, SL ${fmt(ev.signal.stopLoss)}, TP ${fmt(ev.signal.takeProfit)}")
                }
                else -> {}
            }
        }
    }

    fun onEdited(chatId: Long, messageId: Long, newText: String) {
        when (val ev = app.ledger.onEdited(chatId, messageId, newText)) {
            is LedgerEvent.SignalEdited -> {
                log.add("edited", "EDITED: signal ${ev.signal.symbol} ${ev.signal.direction} changed after posting · ${ev.signal.chat}", "Now reads: ${newText.take(200)}\nThe original levels are kept in the channel record.")
                Notifier.signal(app, "⚠ ${ev.signal.chat} edited a signal", "${ev.signal.symbol} ${ev.signal.direction}. Original levels kept.")
            }
            is LedgerEvent.ResultEdited -> {
                log.add("edited", "EDITED: result for ${ev.signal.symbol} ${ev.signal.direction} changed from ${ev.oldClaim} to ${ev.newClaim} · ${ev.signal.chat}", newText.take(200))
                if (ev.oldClaim != ev.newClaim) Notifier.signal(app, "⚠ ${ev.signal.chat} edited a result", "${ev.signal.symbol}: ${ev.oldClaim} → ${ev.newClaim}")
            }
            else -> {}
        }
    }

    private fun hasMeta(a: Mt5Account) = a.metaAccountId.isNotBlank() && a.token(prefs.metaToken).isNotBlank()

    /** Checks open channel signals against real prices (TP or SL first), using an MT5 account linked to that channel. */
    suspend fun verifyChannelSignals() {
        if (!prefs.verifyPrices) return
        val accounts = store.accounts.value.filter { hasMeta(it) }
        if (accounts.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        val open = app.ledger.signals.value.filter { it.verified == null }.take(30) // deleted signals are checked too
        for (s in open) {
            val acc = accounts.firstOrNull { it.channelId == s.chatId } ?: accounts.first()
            try {
                val minutes = ((now - s.postedAt) / 60 + 2).toInt()
                val (tf, limit) = when {
                    minutes <= 1000 -> "1m" to minutes
                    minutes <= 5000 -> "5m" to minutes / 5 + 2
                    else -> "15m" to minOf(minutes / 15 + 2, 1000)
                }
                val candles = client(acc).candles(acc.brokerSymbol(s.symbol), tf, limit)
                val res = Ledger.verify(s, candles, now) ?: continue
                app.ledger.setVerified(s.key, res.first, res.second)
                val claimNote = when {
                    res.first == "loss" && s.claim == "win" -> " but the channel said TP hit"
                    res.first == "loss" && s.claim == null -> " and the channel posted no result"
                    res.first == "loss" && s.claimDeletedAt > 0 -> " and the channel deleted its result"
                    else -> ""
                }
                val kind = when (res.first) { "win" -> "price_win"; "loss" -> "price_loss"; else -> "info" }
                log.add(kind, "PRICE CHECK: ${s.symbol} ${s.direction} from ${s.chat} → ${res.first.uppercase()}$claimNote", res.second)
                if (claimNote.isNotEmpty()) Notifier.signal(app, "⚠ Hidden loss: ${s.symbol} ${s.direction}", "${res.second}$claimNote · ${s.chat}")
            } catch (_: Exception) {
                // market closed, symbol name wrong or network problem: try again next round
            }
        }
    }

    private fun alert(signal: Signal, source: String, account: Mt5Account?) {
        val lotText = suggestedLotText(signal, account)
        Notifier.signal(app, "${signal.symbol} ${signal.orderName}", "${signal.summary()}\n$lotText\nFrom $source")
        log.add("alert", "Alert sent: ${signal.symbol} ${signal.orderName} · $source", lotText)
    }

    /** Suggested lot for an alert, using the linked account's settings and its last known equity. */
    fun suggestedLotText(signal: Signal, account: Mt5Account?): String {
        val a = account ?: return "Link an MT5 account to this channel to see a lot size"
        val unit = if (a.isCent) "cent lots" else "lots"
        if (a.lotMode == "fixed") return "Lot: ${fmt(a.fixedLot)} $unit (fixed, ${a.name})"
        val entry = signal.entry ?: return "Lot: no entry price to estimate from"
        val equity = a.status.equity ?: return "Lot: risk ${fmt(a.riskPercent)}% (equity not known yet for ${a.name})"
        val r = LotCalculator.estimateLots(signal.symbol, equity, a.isCent, a.centFactor, a.riskPercent, entry, signal.stopLoss)
        return if (r.ok) "Suggested lot: ${fmt(r.lots)} $unit for ${a.name} (${r.note})" else "Lot: ${r.note}"
    }

    private suspend fun autoTrade(signal: Signal, source: String, acc: Mt5Account) {
        try {
            val plan = plan(signal, acc)
            val res = execute(plan)
            if (res.ok) {
                log.add("trade", "Trade placed on ${acc.name}: ${plan.describe()}", listOf("${res.code} ${res.message}", plan.lotNote, plan.orderNote).filter { it.isNotBlank() }.joinToString("\n"))
                Notifier.signal(app, "Trade placed ✔ ${acc.name}", plan.describe())
                track(plan, res, source)
            } else {
                log.add("failed", "FAILED: MT5 rejected · ${acc.name} · ${plan.describe()}", "${res.code} ${res.message}\n${plan.lotNote}")
                Notifier.signal(app, "Trade FAILED · ${acc.name}", "${plan.describe()}\n${res.code}: ${res.message}")
            }
        } catch (e: SkipException) {
            log.add("skipped", "SKIPPED: ${signal.symbol} ${signal.orderName} · ${acc.name} · ${e.message}", signal.summary())
            Notifier.signal(app, "Signal skipped · ${acc.name}", "${signal.summary()}\n${e.message}")
        } catch (e: Exception) {
            log.add("failed", "FAILED: ${signal.symbol} ${signal.orderName} not placed · ${acc.name}", "${e.message ?: e}\n${signal.summary()}")
            Notifier.signal(app, "Trade FAILED · ${acc.name}", "${signal.summary()}\n${e.message ?: "Unknown error"}")
        }
    }

    /** Remembers a placed trade so its final result (TP/SL, profit or loss) can be logged when it closes. */
    fun track(plan: TradePlan, res: TradeResult, source: String) {
        val id = res.positionId ?: res.orderId ?: return
        app.trades.add(
            TrackedTrade(
                id, plan.brokerSymbol, plan.signal.isBuy, plan.volume, plan.stopLoss, plan.takeProfit,
                plan.openPrice != null, System.currentTimeMillis(), source, plan.account.id, plan.account.name,
            ),
        )
    }

    /** Checks MT5 for trades that have closed since the last check and logs each one's result. Returns how many are still open. */
    suspend fun checkResults(): Int {
        val open = app.trades.all()
        if (open.isEmpty()) return 0
        var stillOpen = 0
        for ((accId, trades) in open.groupBy { it.accountId }) {
            // Trades from version 1.x have no account id: they belong to the first (migrated) account.
            val acc = store.account(accId) ?: store.accounts.value.firstOrNull()
            if (acc == null || !hasMeta(acc)) { stillOpen += trades.size; continue }
            val api = client(acc)
            val currency = try { api.accountInfo().currency } catch (e: Exception) { "" }
            for (t in trades) {
                try {
                    val outcome = TradeResults.classify(t, api.dealsByPosition(t.positionId))
                    if (outcome == null) {
                        val ageDays = (System.currentTimeMillis() - t.openedAt) / 86_400_000L
                        if (ageDays >= 14) {
                            app.trades.remove(t.positionId)
                            log.add("info", "Stopped tracking ${t.symbol} ${t.direction} on ${acc.name} after 14 days", if (t.pending) "Pending order probably never triggered or was cancelled." else "No close found in MT5 history.")
                        } else {
                            stillOpen++
                        }
                        continue
                    }
                    val (kind, text) = TradeResults.describe(t.copy(accountName = t.accountName.ifBlank { acc.name }), outcome, currency)
                    val title = text.substringBefore("\n")
                    log.add(kind, title, text.substringAfter("\n"))
                    Notifier.signal(app, title.substringBefore(" · "), title.substringAfter(" · "))
                    app.trades.remove(t.positionId)
                } catch (e: Exception) {
                    stillOpen++ // network or API problem: try again next time
                }
            }
        }
        return stillOpen
    }

    fun client(acc: Mt5Account): MetaApiClient {
        val token = acc.token(prefs.metaToken)
        if (token.isBlank()) throw SkipException("MetaApi token not set (Setup › MetaApi)")
        if (acc.metaAccountId.isBlank()) throw SkipException("MetaApi account ID not set for ${acc.name}")
        return MetaApiClient(token, acc.metaAccountId, acc.region.ifBlank { "new-york" })
    }

    /** Asks MetaApi whether the account is linked and running, and saves the answer for the status badge. */
    suspend fun refreshStatus(acc: Mt5Account): MetaStatus {
        val now = System.currentTimeMillis()
        val st = try {
            val token = acc.token(prefs.metaToken)
            if (token.isBlank()) throw MetaApiException("Add your MetaApi token in Setup › MetaApi")
            if (acc.metaAccountId.isBlank()) throw MetaApiException("Add this account's MetaApi account ID")
            // The account-management API works without knowing the region; it tells us the region.
            var s = MetaApiClient(token, acc.metaAccountId, acc.region.ifBlank { "new-york" }).accountStatus()
            if (s.state == "DEPLOYED" && s.connection == "CONNECTED") {
                try {
                    val info = MetaApiClient(token, acc.metaAccountId, s.region.ifBlank { acc.region.ifBlank { "new-york" } }).accountInfo()
                    s = s.copy(broker = info.broker, balance = info.balance, equity = info.equity, currency = info.currency, login = info.login.ifBlank { s.login })
                } catch (_: Exception) {
                }
            }
            s.copy(checkedAt = now)
        } catch (e: Exception) {
            acc.status.copy(error = e.message ?: e.toString(), checkedAt = now)
        }
        store.updateStatus(acc.id, st)
        return st
    }

    suspend fun refreshAllStatuses() {
        for (a in store.accounts.value) if (a.metaAccountId.isNotBlank()) refreshStatus(a)
    }

    /** Works out symbol, order type, lot size and prices for one account without placing anything. */
    suspend fun plan(signal: Signal, acc: Mt5Account): TradePlan {
        val api = client(acc)
        val sym = acc.brokerSymbol(signal.symbol)
        val price = api.price(sym)
        val spec = api.spec(sym)
        val market = if (signal.isBuy) price.ask else price.bid
        if (market <= 0) throw SkipException("No price for $sym (market closed or wrong symbol name?)")

        val tp = if (acc.tpChoice == "last") signal.takeProfits.last() else signal.takeProfits.first()
        val maxDistance = Pips.toPrice(acc.maxDistancePips, signal.symbol)
        val decision = Orders.decide(signal, acc.orderRule, price.bid, price.ask, maxDistance)
        val isMarket = decision.openPrice == null

        if (isMarket) {
            signal.entry?.let { entry ->
                if (maxDistance > 0 && abs(market - entry) > maxDistance && signal.kind != OrderKind.LIMIT)
                    throw SkipException("price ${fmt(market)} is ${fmt(Math.round(Pips.between(market, entry, signal.symbol) * 10) / 10.0)} pips from entry ${fmt(entry)}, more than your ${fmt(acc.maxDistancePips)}-pip limit")
            }
            if (signal.isBuy && (market <= signal.stopLoss || market >= tp)) throw SkipException("price ${fmt(market)} is already past SL or TP")
            if (!signal.isBuy && (market >= signal.stopLoss || market <= tp)) throw SkipException("price ${fmt(market)} is already past SL or TP")
        }
        val riskEntry = decision.openPrice ?: market

        val lot = if (acc.lotMode == "fixed") {
            LotCalculator.finish(acc.fixedLot, spec.minVolume, spec.maxVolume, spec.volumeStep, true, acc.maxLot, "fixed lot")
        } else {
            val info = api.accountInfo()
            LotCalculator.riskLots(
                info.equity, acc.riskPercent, riskEntry, signal.stopLoss, spec.tickSize, price.lossTickValue,
                spec.minVolume, spec.maxVolume, spec.volumeStep, acc.useMinLot, acc.maxLot,
            )
        }
        if (!lot.ok || lot.lots <= 0) throw SkipException(lot.note)

        return TradePlan(signal, acc, sym, decision.actionType, lot.lots, decision.openPrice, signal.stopLoss, tp, market, spec.digits, lot.note, decision.note)
    }

    suspend fun execute(plan: TradePlan): TradeResult {
        val body = JSONObject()
            .put("actionType", plan.actionType)
            .put("symbol", plan.brokerSymbol)
            .put("volume", plan.volume)
            .put("stopLoss", round(plan.stopLoss, plan.digits))
            .put("takeProfit", round(plan.takeProfit, plan.digits))
            .put("comment", "TG2MT5")
        plan.openPrice?.let { body.put("openPrice", round(it, plan.digits)) }
        return client(plan.account).trade(body)
    }

    private fun round(v: Double, digits: Int) =
        BigDecimal(v).setScale(digits.coerceIn(0, 8), RoundingMode.HALF_UP).toDouble()

    companion object {
        /** Alerts for signals older than this are not sent (they arrived while the phone was offline). */
        const val ALERT_MAX_AGE_SEC = 10 * 60L
    }
}
