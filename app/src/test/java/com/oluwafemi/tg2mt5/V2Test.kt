package com.oluwafemi.tg2mt5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SignalWordsTest {
    private fun trade(text: String, w: SignalWords = SignalWords.DEFAULT): Signal {
        val r = SignalParser.parse(text, w)
        assertTrue("Expected a trade for '$text' but got $r", r is ParseResult.Trade)
        return (r as ParseResult.Trade).signal
    }

    @Test
    fun originalSignalStillWorksAsMarket() {
        val s = trade("XAUUSD 📉\n\nSell: 4054.5\n\nTP: 4044.5\n\nSL: 4064.5")
        assertEquals(OrderKind.AUTO, s.kind)
        assertEquals(4054.5, s.entry!!, 1e-9)
    }

    @Test
    fun detectsLimitAndStopOrders() {
        assertEquals(OrderKind.LIMIT, trade("XAUUSD BUY LIMIT 4040\nTP 4060\nSL 4030").kind)
        assertEquals(OrderKind.STOP, trade("GOLD SELL STOP 4030\nTP 4010\nSL 4040").kind)
        assertEquals(OrderKind.MARKET, trade("GOLD BUY NOW\nTP 4060\nSL 4030").kind)
        assertEquals("SELL STOP", trade("GOLD SELL STOP 4030\nTP 4010\nSL 4040").orderName)
    }

    @Test
    fun stopLossWordIsNotMistakenForStopOrder() {
        val s = trade("XAUUSD SELL 4054\nStop Loss 4064\nTake Profit 4044")
        assertEquals(OrderKind.AUTO, s.kind)
        assertEquals(4064.0, s.stopLoss, 1e-9)
    }

    @Test
    fun channelSpecificWords() {
        val w = SignalWords.DEFAULT.copy(
            buyStop = "buy above", sellLimit = "sell zone", stopLoss = "sl, invalidation", takeProfit = "tp, objective", entry = "entry, enter",
        )
        val a = trade("Gold buy above 4050\nobjective 4070\ninvalidation 4040", w)
        assertEquals(OrderKind.STOP, a.kind); assertTrue(a.isBuy); assertEquals(4050.0, a.entry!!, 1e-9)
        assertEquals(listOf(4070.0), a.takeProfits); assertEquals(4040.0, a.stopLoss, 1e-9)
        val b = trade("XAUUSD sell zone 4060\nObjective1: 4050\nObjective2: 4040\nInvalidation: 4070", w)
        assertEquals(OrderKind.LIMIT, b.kind); assertEquals(listOf(4050.0, 4040.0), b.takeProfits)
    }

    @Test
    fun entryOnItsOwnLine() {
        val s = trade("XAUUSD SELL\nEntry: 4054.5\nTP: 4044.5\nSL: 4064.5")
        assertEquals(4054.5, s.entry!!, 1e-9)
    }

    @Test
    fun channelSampleSellAtPrice() {
        val s = trade("Sell XAUUSD @ 3625.5\n\nTP: 3610.5 (153 Pips)\n\nSL: 3633")
        assertEquals("XAUUSD", s.symbol); assertTrue(!s.isBuy)
        assertEquals(3625.5, s.entry!!, 1e-9); assertEquals(listOf(3610.5), s.takeProfits); assertEquals(3633.0, s.stopLoss, 1e-9)
        assertEquals(OrderKind.AUTO, s.kind) // a price but no limit/stop word: market order
    }

    @Test
    fun channelSampleBuyStopAfterHeadline() {
        val s = trade("Gold Buy Setup 📈\n\nBuy Stop: 3504.5\n\nTP: 3525\n\nSL: 3495\n\nSession: London Close")
        assertEquals("XAUUSD", s.symbol); assertTrue(s.isBuy)
        assertEquals(OrderKind.STOP, s.kind); assertEquals(3504.5, s.entry!!, 1e-9)
        assertEquals(listOf(3525.0), s.takeProfits); assertEquals(3495.0, s.stopLoss, 1e-9)
    }

    @Test
    fun pendingWithoutPriceIsRejected() {
        assertTrue(SignalParser.parse("GOLD BUY LIMIT\nTP 4060\nSL 4030") is ParseResult.Invalid)
    }

    @Test
    fun replacedWordsAreNotReplacedAgain() {
        // "stop" as an SL word must not turn the canonical "BUY STOP" into "BUY SL".
        val w = SignalWords.DEFAULT.copy(stopLoss = "sl, stop")
        val s = trade("GOLD buy stop 4050\nTP 4070\nstop 4040", w)
        assertEquals(OrderKind.STOP, s.kind)
        assertEquals(4040.0, s.stopLoss, 1e-9)
    }
}

class OrdersTest {
    private fun sig(buy: Boolean, kind: OrderKind, entry: Double?) =
        Signal("XAUUSD", buy, entry, if (buy) entry?.minus(10) ?: 4000.0 else entry?.plus(10) ?: 4100.0, listOf(if (buy) 4100.0 else 4000.0), kind)

    @Test
    fun followSendsPendingOrders() {
        assertEquals("ORDER_TYPE_BUY_LIMIT", Orders.decide(sig(true, OrderKind.LIMIT, 4040.0), "follow", 4049.5, 4050.0, 0.0).actionType)
        assertEquals("ORDER_TYPE_SELL_STOP", Orders.decide(sig(false, OrderKind.STOP, 4040.0), "follow", 4050.0, 4050.5, 0.0).actionType)
        assertEquals(4040.0, Orders.decide(sig(true, OrderKind.LIMIT, 4040.0), "follow", 4049.5, 4050.0, 0.0).openPrice!!, 1e-9)
    }

    @Test
    fun marketRuleAlwaysMarket() {
        val d = Orders.decide(sig(true, OrderKind.LIMIT, 4040.0), "market", 4049.5, 4050.0, 0.0)
        assertEquals("ORDER_TYPE_BUY", d.actionType); assertNull(d.openPrice)
    }

    @Test
    fun reachedLimitBecomesMarket() {
        val d = Orders.decide(sig(true, OrderKind.LIMIT, 4040.0), "follow", 4038.0, 4038.5, 0.0)
        assertEquals("ORDER_TYPE_BUY", d.actionType); assertTrue(d.note.isNotBlank())
    }

    @Test
    fun passedStopIsSkippedUnlessClose() {
        try {
            Orders.decide(sig(true, OrderKind.STOP, 4050.0), "follow", 4059.5, 4060.0, 0.0)
            fail("expected skip")
        } catch (e: SkipException) {
        }
        assertEquals("ORDER_TYPE_BUY", Orders.decide(sig(true, OrderKind.STOP, 4050.0), "follow", 4051.5, 4052.0, 3.0).actionType)
    }

    @Test
    fun barePriceIsMarket() {
        assertEquals("ORDER_TYPE_SELL", Orders.decide(sig(false, OrderKind.AUTO, 4054.5), "follow", 4054.0, 4054.4, 0.0).actionType)
    }
}

class PipsTest {
    @Test
    fun marketStandardPipSizes() {
        assertEquals(4.0, Pips.toPrice(40.0, "XAUUSD"), 1e-9)       // 3998 → 4002
        assertEquals(40.0, Pips.between(3998.0, 4002.0, "XAUUSD"), 1e-9)
        assertEquals(0.0040, Pips.toPrice(40.0, "EURUSD"), 1e-12)
        assertEquals(0.40, Pips.toPrice(40.0, "USDJPY"), 1e-12)
        assertEquals(0.40, Pips.toPrice(40.0, "XAGUSD"), 1e-12)
    }

    @Test
    fun oldGoldDollarSettingBecomesPips() {
        assertEquals(30.0, Pips.fromGoldPrice(3.0), 1e-9)
        assertEquals(0.0, Pips.fromGoldPrice(0.0), 1e-9)
    }
}

class LinkRulesTest {
    private val a = Mt5Account(id = "a", name = "Exness Cent", channelId = 1L)
    private val b = Mt5Account(id = "b", name = "Exness Std", channelId = null)

    @Test
    fun flexibleAllowsManyAccountsPerChannel() {
        assertNull(LinkRules.canLink(listOf(a, b), "b", 1L, strictPairs = false))
    }

    @Test
    fun pairsAllowsOneAccountPerChannel() {
        assertNotNull(LinkRules.canLink(listOf(a, b), "b", 1L, strictPairs = true))
        assertNull(LinkRules.canLink(listOf(a, b), "a", 1L, strictPairs = true)) // re-saving the same account is fine
        assertNull(LinkRules.canLink(listOf(a, b), "b", 2L, strictPairs = true))
    }

    @Test
    fun restoringIntoPairsUnlinksExtras() {
        val c = Mt5Account(id = "c", name = "Third", channelId = 1L, copyOn = true)
        val (out, unlinked) = SetupStoreData.enforcePairs(listOf(a, b, c))
        assertEquals(1, unlinked)
        assertEquals(1L, out[0].channelId)
        assertNull(out[2].channelId); assertEquals(false, out[2].copyOn)
    }
}

class MigrationTest {
    private fun old(mode: String, metaId: String, channels: Set<Long>) = Migration.OldSettings(
        channels, mode, metaId, "london", "cent", 0.01, "risk", 0.02, 1.5, 0.5, false, "", "c", "", "pending", "first", 3.0, 5.0,
    )

    @Test
    fun autoModeBecomesOneCopyingAccount() {
        val (ch, acc) = Migration.migrate(old("auto", "abc", setOf(10L, 20L)), mapOf(10L to "Gold VIP"))
        assertEquals(2, ch.size); assertEquals("Gold VIP", ch[0].title)
        assertEquals(1, acc.size)
        val a = acc[0]
        assertEquals("abc", a.metaAccountId); assertEquals(10L, a.channelId); assertTrue(a.copyOn)
        assertEquals("cent", a.accountType); assertEquals("risk", a.lotMode); assertEquals(1.5, a.riskPercent, 1e-9)
        assertEquals("c", a.symbolSuffix); assertEquals("follow", a.orderRule); assertEquals("london", a.region)
    }

    @Test
    fun alertModeKeepsAlertsAndNoCopying() {
        val (ch, acc) = Migration.migrate(old("alert", "", setOf(10L)), emptyMap())
        assertTrue(ch[0].alerts)
        assertTrue(acc.isEmpty())
    }
}
