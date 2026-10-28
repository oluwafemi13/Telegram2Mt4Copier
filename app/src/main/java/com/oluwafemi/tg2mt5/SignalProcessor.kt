package com.oluwafemi.tg2mt5

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Everything decided about a signal before it is sent to MT5. */
data class TradePlan(
    val signal: Signal,
    val brokerSymbol: String,
    val actionType: String,
    val volume: Double,
    val openPrice: Double?,
    val stopLoss: Double,
    val takeProfit: Double,
    val marketPrice: Double,
    val digits: Int,
    val lotNote: String,
) {
    /** e.g. "SELL XAUUSDm 0.01 lots (market ~4054.3)  SL 4064.5  TP 4044.5" */
    fun describe() = "${actionType.removePrefix("ORDER_TYPE_").replace('_', ' ')} $brokerSymbol ${fmt(volume)} lots" +
        (openPrice?.let { " @ ${fmt(it)}" } ?: " (market ~${fmt(marketPrice)})") +
        "  SL ${fmt(stopLoss)}  TP ${fmt(takeProfit)}"
}

class SkipException(message: String) : Exception(message)

class SignalProcessor(private val app: App) {
    private val prefs get() = app.prefs
    private val log get() = app.log

    /**
     * Called for every new message in a selected Telegram chat.
     * replyToKey is set when the message is a reply to an earlier message (e.g. a "TP hit" reply to the signal).
     */
    suspend fun onMessage(
        text: String, source: String, messageKey: String, ageSeconds: Long, replyToKey: String? = null,
        chatId: Long = 0, postedAt: Long = System.currentTimeMillis() / 1000,
    ) {
        val repliesToSignal = replyToKey != null && (prefs.wasProcessed(replyToKey) || app.ledger.signals.value.any { it.key == replyToKey })
        when (val r = SignalParser.parse(text)) {
            is ParseResult.NotASignal -> {
                // A reply to one of the channel's signals is that signal's result, whatever words it uses.
                if (repliesToSignal) channelResult(SignalParser.outcomeOf(text), text, source, messageKey, chatId, postedAt, replyToKey)
                return
            }
            is ParseResult.Outcome -> {
                channelResult(r.win, r.text, source, messageKey, chatId, postedAt, replyToKey)
                return
            }
            is ParseResult.Invalid -> {
                if (repliesToSignal) {
                    channelResult(SignalParser.outcomeOf(text), text, source, messageKey, chatId, postedAt, replyToKey)
                    return
                }
                log.add("error", "Unreadable signal from $source: ${r.reason}", text.take(300))
                return
            }
            is ParseResult.Trade -> {
                // Every signal goes into the channel record, even old ones or ones not traded.
                app.ledger.recordSignal(messageKey, chatId, source, postedAt, r.signal, text)
                if (prefs.alreadyProcessed(messageKey)) return
                val maxAge = (prefs.maxAgeMinutes * 60).toLong()
                if (maxAge > 0 && ageSeconds > maxAge) {
                    log.add("skipped", "SKIPPED: old signal (${ageSeconds / 60} min old) · ${r.signal.symbol} ${r.signal.direction}", r.signal.summary())
                    return
                }
                log.add("signal", "Signal from $source", r.signal.summary())
                if (prefs.isAuto) autoTrade(r.signal, source) else alert(r.signal, source)
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
        if (prefs.notifyChannelResults) Notifier.signal(app, "$label$about", "${text.trim().take(150)}\nFrom $source")
    }

    /** Messages deleted or edited in a watched chat: flag anything that was in the channel record. */
    fun onDeleted(chatId: Long, ids: LongArray) {
        for (ev in app.ledger.onDeleted(chatId, ids)) {
            when (ev) {
                is LedgerEvent.ResultDeleted -> {
                    val what = when (ev.win) { false -> "LOSS result"; true -> "win result"; null -> "result" }
                    val about = ev.signal?.let { " for ${it.symbol} ${it.direction}" } ?: ""
                    log.add("deleted", "DELETED: channel removed a $what$about · ${ev.chat}", ev.signal?.claimText ?: "")
                    if (ev.win == false || prefs.notifyChannelResults) Notifier.signal(app, "⚠ ${ev.chat} deleted a $what$about", "The app kept its own record. See the Channel tab.")
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

    /** Checks open channel signals against real prices (TP or SL first). Needs MetaApi. */
    suspend fun verifyChannelSignals() {
        if (!prefs.verifyPrices || prefs.metaToken.isBlank() || prefs.metaAccountId.isBlank()) return
        val api = client()
        val now = System.currentTimeMillis() / 1000
        val open = app.ledger.signals.value.filter { it.verified == null } // deleted signals are checked too.take(30)
        for (s in open) {
            try {
                val minutes = ((now - s.postedAt) / 60 + 2).toInt()
                val (tf, limit) = when {
                    minutes <= 1000 -> "1m" to minutes
                    minutes <= 5000 -> "5m" to minutes / 5 + 2
                    else -> "15m" to minOf(minutes / 15 + 2, 1000)
                }
                val candles = api.candles(prefs.brokerSymbol(s.symbol), tf, limit)
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

    private fun alert(signal: Signal, source: String) {
        val lotText = suggestedLotText(signal)
        Notifier.signal(app, "${signal.symbol} ${signal.direction}", "${signal.summary()}\n$lotText\nFrom $source")
        log.add("alert", "Alert sent: ${signal.symbol} ${signal.direction}", lotText)
    }

    fun suggestedLotText(signal: Signal): String {
        val unit = if (prefs.isCent) "cent lots" else "lots"
        if (prefs.lotMode == "fixed") return "Lot: ${fmt(prefs.fixedLot)} $unit (fixed)"
        val entry = signal.entry ?: return "Lot: set an entry price to estimate"
        if (prefs.alertEquity <= 0) return "Lot: enter your equity in Settings to see a risk-based lot"
        val r = LotCalculator.estimateLots(signal.symbol, prefs.alertEquity, prefs.isCent, prefs.centFactor, prefs.riskPercent, entry, signal.stopLoss)
        return if (r.ok) "Suggested lot: ${fmt(r.lots)} $unit (${r.note})" else "Lot: ${r.note}"
    }

    private suspend fun autoTrade(signal: Signal, source: String) {
        try {
            val plan = plan(signal)
            val res = execute(plan)
            if (res.ok) {
                log.add("trade", "Trade placed: ${plan.describe()}", "${res.code} ${res.message}\n${plan.lotNote}")
                Notifier.signal(app, "Trade placed ✔", plan.describe())
                track(plan, res, source)
            } else {
                log.add("failed", "FAILED: MT5 rejected · ${plan.describe()}", "${res.code} ${res.message}\n${plan.lotNote}")
                Notifier.signal(app, "Trade FAILED", "${plan.describe()}\n${res.code}: ${res.message}")
            }
        } catch (e: SkipException) {
            log.add("skipped", "SKIPPED: ${signal.symbol} ${signal.direction} · ${e.message}", signal.summary())
            Notifier.signal(app, "Signal skipped", "${signal.summary()}\n${e.message}")
        } catch (e: Exception) {
            log.add("failed", "FAILED: ${signal.symbol} ${signal.direction} not placed", "${e.message ?: e}\n${signal.summary()}")
            Notifier.signal(app, "Trade FAILED", "${signal.summary()}\n${e.message ?: "Unknown error"}")
        }
    }

    /** Remembers a placed trade so its final result (TP/SL, profit or loss) can be logged when it closes. */
    fun track(plan: TradePlan, res: TradeResult, source: String) {
        val id = res.positionId ?: res.orderId ?: return
        app.trades.add(
            TrackedTrade(
                id, plan.brokerSymbol, plan.signal.isBuy, plan.volume, plan.stopLoss, plan.takeProfit,
                plan.openPrice != null, System.currentTimeMillis(), source,
            ),
        )
    }

    /** Checks MT5 for trades that have closed since the last check and logs each one's result. Returns how many are still open. */
    suspend fun checkResults(): Int {
        val open = app.trades.all()
        if (open.isEmpty() || prefs.metaToken.isBlank() || prefs.metaAccountId.isBlank()) return open.size
        val api = client()
        val currency = try { api.accountInfo().currency } catch (e: Exception) { "" }
        var stillOpen = 0
        for (t in open) {
            try {
                val outcome = TradeResults.classify(t, api.dealsByPosition(t.positionId))
                if (outcome == null) {
                    val ageDays = (System.currentTimeMillis() - t.openedAt) / 86_400_000L
                    if (ageDays >= 14) {
                        app.trades.remove(t.positionId)
                        log.add("info", "Stopped tracking ${t.symbol} ${t.direction} after 14 days", if (t.pending) "Pending order probably never triggered or was cancelled." else "No close found in MT5 history.")
                    } else {
                        stillOpen++
                    }
                    continue
                }
                val (kind, text) = TradeResults.describe(t, outcome, currency)
                val title = text.substringBefore("\n")
                log.add(kind, title, text.substringAfter("\n"))
                Notifier.signal(app, title.substringBefore(" · "), title.substringAfter(" · "))
                app.trades.remove(t.positionId)
            } catch (e: Exception) {
                stillOpen++ // network or API problem: try again next time
            }
        }
        return stillOpen
    }

    fun client(): MetaApiClient {
        if (prefs.metaToken.isBlank() || prefs.metaAccountId.isBlank()) throw SkipException("MetaApi token or account ID not set")
        return MetaApiClient(prefs.metaToken, prefs.metaAccountId, prefs.metaRegion)
    }

    /** Works out symbol, order type, lot size and prices without placing anything. */
    suspend fun plan(signal: Signal): TradePlan {
        val api = client()
        val sym = prefs.brokerSymbol(signal.symbol)
        val price = api.price(sym)
        val spec = api.spec(sym)
        val market = if (signal.isBuy) price.ask else price.bid
        if (market <= 0) throw SkipException("No price for $sym (market closed?)")

        signal.entry?.let { entry ->
            if (prefs.maxDistance > 0 && abs(market - entry) > prefs.maxDistance)
                throw SkipException("price ${fmt(market)} is more than ${fmt(prefs.maxDistance)} away from entry ${fmt(entry)}")
        }

        val tp = if (prefs.tpChoice == "last") signal.takeProfits.last() else signal.takeProfits.first()
        val pending = prefs.orderType == "pending" && signal.entry != null
        val action: String
        val openPrice: Double?
        val riskEntry: Double
        if (pending) {
            val e = signal.entry!!
            action = if (signal.isBuy) (if (e < price.ask) "ORDER_TYPE_BUY_LIMIT" else "ORDER_TYPE_BUY_STOP")
            else (if (e > price.bid) "ORDER_TYPE_SELL_LIMIT" else "ORDER_TYPE_SELL_STOP")
            openPrice = e
            riskEntry = e
        } else {
            if (signal.isBuy && (market <= signal.stopLoss || market >= tp)) throw SkipException("price ${fmt(market)} is already past SL or TP")
            if (!signal.isBuy && (market >= signal.stopLoss || market <= tp)) throw SkipException("price ${fmt(market)} is already past SL or TP")
            action = if (signal.isBuy) "ORDER_TYPE_BUY" else "ORDER_TYPE_SELL"
            openPrice = null
            riskEntry = market
        }

        val lot = if (prefs.lotMode == "fixed") {
            LotCalculator.finish(prefs.fixedLot, spec.minVolume, spec.maxVolume, spec.volumeStep, true, prefs.maxLot, "fixed lot")
        } else {
            val info = api.accountInfo()
            LotCalculator.riskLots(
                info.equity, prefs.riskPercent, riskEntry, signal.stopLoss, spec.tickSize, price.lossTickValue,
                spec.minVolume, spec.maxVolume, spec.volumeStep, prefs.useMinLot, prefs.maxLot,
            )
        }
        if (!lot.ok || lot.lots <= 0) throw SkipException(lot.note)

        return TradePlan(signal, sym, action, lot.lots, openPrice, signal.stopLoss, tp, market, spec.digits, lot.note)
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
        return client().trade(body)
    }

    private fun round(v: Double, digits: Int) =
        BigDecimal(v).setScale(digits.coerceIn(0, 8), RoundingMode.HALF_UP).toDouble()
}
