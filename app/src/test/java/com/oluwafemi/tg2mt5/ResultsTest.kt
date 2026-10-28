package com.oluwafemi.tg2mt5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelResultTest {
    private fun outcome(text: String): ParseResult.Outcome {
        val r = SignalParser.parse(text)
        assertTrue("Expected an outcome for '$text' but got $r", r is ParseResult.Outcome)
        return r as ParseResult.Outcome
    }

    @Test
    fun tpHitIsWin() {
        assertEquals(true, outcome("XAUUSD TP hit ✅ +100 pips").win)
        assertEquals(true, outcome("TP1 smashed 🎯").win)
    }

    @Test
    fun slHitIsLoss() {
        assertEquals(false, outcome("SL hit ❌").win)
        assertEquals(false, outcome("XAUUSD stop loss hit").win)
        assertEquals(false, outcome("Stopped out -100 pips").win)
        assertEquals(false, outcome("Gold sell SL triggered 🔴").win)
        assertEquals(false, outcome("Loss 😔 we move").win)
    }

    @Test
    fun replyWordingIsClassified() {
        // Used for replies to a signal, even when the text isn't recognised as a result by itself.
        assertEquals(false, SignalParser.outcomeOf("hit sl 🛑"))
        assertEquals(true, SignalParser.outcomeOf("+50 pips"))
        assertNull(SignalParser.outcomeOf("hold this one"))
    }

    @Test
    fun signalsAreStillSignals() {
        assertTrue(SignalParser.parse("XAUUSD 📉\nSell: 4054.5\nTP: 4044.5\nSL: 4064.5") is ParseResult.Trade)
    }
}

class TradeResultTest {
    private val sell = TrackedTrade("123", "XAUUSD", false, 0.01, 4064.5, 4044.5, false, 0L, "Gold signals")
    private val open = Deal("DEAL_ENTRY_IN", 4054.5, 0.0, 0.0, -0.05, "", "")

    @Test
    fun stillOpenGivesNull() {
        assertNull(TradeResults.classify(sell, listOf(open)))
    }

    @Test
    fun closedAtTakeProfitIsWin() {
        val o = TradeResults.classify(sell, listOf(open, Deal("DEAL_ENTRY_OUT", 4044.5, 10.0, 0.0, -0.05, "", "")))!!
        assertEquals(CloseReason.TP, o.reason)
        assertEquals(9.9, o.netProfit, 1e-9)
        assertTrue(o.isWin)
        assertEquals("win", TradeResults.describe(sell, o, "USD").first)
    }

    @Test
    fun closedAtStopLossIsLoss() {
        val o = TradeResults.classify(sell, listOf(open, Deal("DEAL_ENTRY_OUT", 4064.6, -10.1, 0.0, -0.05, "", "")))!!
        assertEquals(CloseReason.SL, o.reason)
        assertEquals("loss", TradeResults.describe(sell, o, "USD").first)
    }

    @Test
    fun brokerCommentWinsOverPrice() {
        val o = TradeResults.classify(sell, listOf(open, Deal("DEAL_ENTRY_OUT", 4050.0, -3.0, 0.0, 0.0, "", "[sl 4050.00]")))!!
        assertEquals(CloseReason.SL, o.reason)
    }

    @Test
    fun manualCloseIsOther() {
        val o = TradeResults.classify(sell, listOf(open, Deal("DEAL_ENTRY_OUT", 4052.0, 2.5, 0.0, 0.0, "DEAL_REASON_CLIENT", "")))!!
        assertEquals(CloseReason.OTHER, o.reason)
        assertTrue(o.isWin)
    }
}
